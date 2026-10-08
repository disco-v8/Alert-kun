package direct.kabu.alertkun.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import direct.kabu.alertkun.monitoring.BackupMonitoringPolicy
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import direct.kabu.alertkun.monitoring.MailCheckWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class MonitoringBackupStatusTest {
    @Test fun gatewayRegistrationPersistsAndPublishesWithoutChangingMonitoringExecution() = runBlocking {
        val store = MemoryPreferences()
        val repository = MonitoringStatusRepository.forStore(store)
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        repository.recordSuccess(due - 20, MailCheckTrigger.MANUAL, generation)
        val executionBefore = repository.executionStatus()
        val healthBefore = repository.healthStatus()
        assertNull(repository.current().lastGatewayRegistrationAtEpochMillis)
        repository.recordGatewayRegistrationSuccess(due - 10)
        assertEquals(executionBefore, repository.executionStatus())
        assertEquals(healthBefore, repository.healthStatus())
        assertEquals(due - 10, repository.state.first().lastGatewayRegistrationAtEpochMillis)
        assertEquals(due - 10, MonitoringStatusRepository.forStore(store).current().lastGatewayRegistrationAtEpochMillis)
        repository.recordGatewayRegistrationSuccess(0)
        assertEquals(due - 10, repository.current().lastGatewayRegistrationAtEpochMillis)
        repository.recordFcmReceived(due)
        repository.recordGatewayRegistrationSuccess(due + 10)
        assertNull(repository.current().lastFcmReceivedAtEpochMillis)
        assertEquals(due + 10, repository.current().lastGatewayRegistrationAtEpochMillis)
    }
    @Test fun fcmReceiptPersistsWithoutChangingSuccessOrBackupState() = runBlocking {
        val store = MemoryPreferences()
        val repository = MonitoringStatusRepository.forStore(store)
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        repository.recordSuccess(due - 20, MailCheckTrigger.MANUAL, generation)
        val before = repository.executionStatus()
        repository.recordFcmReceived(due - 10)
        assertEquals(before, repository.executionStatus())
        assertEquals(due - 10, MonitoringStatusRepository.forStore(store).current().lastFcmReceivedAtEpochMillis)
        repository.recordSuccess(due, MailCheckTrigger.SCHEDULED_WORK, generation)
        repository.advanceBackupWindow(due, generation, 15)
        assertEquals(due - 10, repository.current().lastFcmReceivedAtEpochMillis)
        repository.recordFcmReceived(due + 10)
        repository.recordFcmReceived(due + 5)
        assertEquals(due + 10, repository.current().lastFcmReceivedAtEpochMillis)
    }
    @Test fun allRealSuccessTriggersPublishLatestTimestampToStateFlowAndHealth() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val uiState = repository.state.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            direct.kabu.alertkun.model.MonitoringState(false, null, null, 0))
        try {
            MailCheckTrigger.entries.forEachIndexed { index, trigger ->
                val completed = due + index * 60_000L
                repository.recordCheckStarted(completed - 1, generation)
                repository.recordSuccess(completed, trigger, generation)
                assertEquals(completed, uiState.first { it.lastSuccessfulCheckAtEpochMillis == completed }.lastSuccessfulCheckAtEpochMillis)
                assertEquals(completed, repository.healthStatus().lastSuccessfulCheckAt)
                assertEquals(trigger, repository.executionStatus().lastSuccessfulTrigger)
            }
        } finally { scope.cancel() }
    }

    @Test fun failureSkipAndStaleGenerationNeverReplaceTheLastRealSuccess() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        repository.recordCheckStarted(due - 20, generation)
        repository.recordSuccess(due - 10, MailCheckTrigger.FCM, generation)
        assertTrue(BackupMonitoringPolicy.shouldSkipBackup(due, generation, 15, repository.executionStatus()))
        repository.advanceBackupWindow(due, generation, 15)
        assertEquals(due - 10, repository.current().lastSuccessfulCheckAtEpochMillis)
        repository.recordCheckStarted(due + 1, generation)
        repository.recordFailure(due + 2, 3, generation)
        assertEquals(due - 10, repository.current().lastSuccessfulCheckAtEpochMillis)
        repository.changeSchedule(false, null)
        repository.recordSuccess(due + 3, MailCheckTrigger.STARTUP, generation)
        assertEquals(due - 10, repository.current().lastSuccessfulCheckAtEpochMillis)
    }
    private val due = 60 * 60_000L

    @Test fun successTriggerGenerationAndBackupAnchorSurviveRepositoryRecreation() = runBlocking {
        val store = MemoryPreferences()
        val repository = MonitoringStatusRepository.forStore(store)
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        repository.recordCheckStarted(due - 10, generation)
        repository.recordSuccess(due - 1, MailCheckTrigger.FCM, generation)
        val reopened = MonitoringStatusRepository.forStore(store)
        val saved = reopened.executionStatus()
        assertEquals(MailCheckTrigger.FCM, saved.lastSuccessfulTrigger)
        assertEquals(generation, saved.lastSuccessfulGeneration)
        assertEquals(due, saved.nextBackupAtEpochMillis)
        assertTrue(BackupMonitoringPolicy.shouldSkipBackup(due, generation, 15, saved))
        reopened.advanceBackupWindow(due, generation, 15)
        assertEquals(due + 15 * 60_000L, reopened.executionStatus().nextBackupAtEpochMillis)
        assertEquals(due - 1, reopened.current().lastSuccessfulCheckAtEpochMillis)
    }

    @Test fun failureCannotBecomeFcmSuccessAndManualSuccessOverridesAttribution() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        repository.recordCheckStarted(due - 100, generation)
        repository.recordSuccess(due - 90, MailCheckTrigger.FCM, generation)
        repository.recordCheckStarted(due - 50, generation)
        repository.recordFailure(due - 40, 3, generation)
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(due, generation, 15, repository.executionStatus()))
        assertEquals(1, repository.current().consecutiveFailureCount)
        repository.recordCheckStarted(due - 30, generation)
        repository.recordSuccess(due - 20, MailCheckTrigger.MANUAL, generation)
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(due, generation, 15, repository.executionStatus()))
        assertEquals(0, repository.current().consecutiveFailureCount)
    }

    @Test fun staleGenerationCannotPublishSuccessFailureOrAdvanceTheNewSchedule() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        val oldGeneration = repository.changeSchedule(true, due, "PERIODIC", 15)
        val newGeneration = repository.changeSchedule(true, due + 30 * 60_000L, "PERIODIC", 30)
        val before = repository.executionStatus()
        repository.recordCheckStarted(due, oldGeneration)
        repository.recordSuccess(due, MailCheckTrigger.FCM, oldGeneration)
        assertFalse(repository.recordFailure(due, 1, oldGeneration).shouldNotify)
        repository.advanceBackupWindow(due, oldGeneration, 15)
        assertFalse(repository.setBackupScheduleIfCurrent(due, oldGeneration, 15))
        assertEquals(before, repository.executionStatus())
        assertEquals(newGeneration, repository.executionStatus().scheduleGeneration)
    }

    @Test fun backupWaitingOnActualWorkerMutexReadsTheNewFcmSuccessAfterAcquiringIt() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        val fcmEntered = CompletableDeferred<Unit>()
        val finishFcm = CompletableDeferred<Unit>()
        var skip = false
        val fcm = launch {
            MailCheckWorker.executionMutex.withLock {
                repository.recordCheckStarted(due - 10, generation)
                fcmEntered.complete(Unit)
                finishFcm.await()
                repository.recordSuccess(due - 1, MailCheckTrigger.FCM, generation)
            }
        }
        fcmEntered.await()
        val waiting = CompletableDeferred<Unit>()
        val backup = launch {
            waiting.complete(Unit)
            MailCheckWorker.executionMutex.withLock {
                skip = BackupMonitoringPolicy.shouldSkipBackup(due, generation, 15, repository.executionStatus())
            }
        }
        waiting.await()
        assertFalse(backup.isCompleted)
        finishFcm.complete(Unit)
        fcm.join(); backup.join()
        assertTrue(skip)
    }

    @Test fun missingLegacyMetadataDoesNotSuppressBackupOrEraseSettings() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        val generation = repository.changeSchedule(true, due, "PERIODIC")
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(due, generation, 15, repository.executionStatus()))
        assertEquals(due, repository.current().nextScheduledCheckAtEpochMillis)
    }

    @Test fun uncompletedAttemptInvalidatesSuccessEvenIfClockTimestampsAreIdentical() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        val generation = repository.changeSchedule(true, due, "PERIODIC", 15)
        repository.recordCheckStarted(due - 1, generation)
        repository.recordSuccess(due - 1, MailCheckTrigger.FCM, generation)
        repository.recordCheckStarted(due - 1, generation)
        assertFalse(repository.executionStatus().lastAttemptSucceeded!!)
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(due, generation, 15, repository.executionStatus()))
    }

    @Test fun manualCheckOnFreshOffStateCanPersistSuccessWithoutCreatingASchedule() = runBlocking {
        val repository = MonitoringStatusRepository.forStore(MemoryPreferences())
        repository.recordCheckStarted(due - 1, 0)
        repository.recordSuccess(due, MailCheckTrigger.MANUAL, 0)
        assertEquals(due, repository.current().lastSuccessfulCheckAtEpochMillis)
        assertFalse(repository.current().isMonitoring)
        assertNull(repository.current().nextScheduledCheckAtEpochMillis)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val writeMutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = writeMutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
