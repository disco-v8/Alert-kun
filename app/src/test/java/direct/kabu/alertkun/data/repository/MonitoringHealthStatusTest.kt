package direct.kabu.alertkun.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import direct.kabu.alertkun.model.MonitoringSettings
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import direct.kabu.alertkun.monitoring.MailCheckWorker
import direct.kabu.alertkun.monitoring.MonitoringHealthNotificationPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class MonitoringHealthStatusTest {
    private val day = MonitoringHealthNotificationPolicy.DAY_MILLIS
    private val initial = 3 * day
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
    private suspend fun setup(store: MemoryStore): Pair<MonitoringStatusRepository, Long> {
        SettingsRepository.forStore(store).update(MonitoringSettings(monitoringEnabled = true))
        val status = MonitoringStatusRepository.forStore(store)
        val generation = status.changeSchedule(true, initial + 900_000, "PERIODIC", 15)
        success(status, generation, initial)
        return status to generation
    }
    private suspend fun success(status: MonitoringStatusRepository, generation: Long, time: Long,
        trigger: MailCheckTrigger = MailCheckTrigger.FCM) {
        status.recordCheckStarted(time - 1, generation)
        status.recordSuccess(time, trigger, generation)
    }

    @Test fun firstSuccessStartsEvidenceAndNotificationClaimSurvivesRecreation() = runBlocking {
        val store = MemoryStore()
        val (status, generation) = setup(store)
        assertFalse(status.claimHealthNotification(initial, generation))
        assertEquals(initial, status.healthStatus().observingSince)
        success(status, generation, initial + day - 1_000)
        assertFalse(status.claimHealthNotification(initial + day - 1_000, generation))
        success(status, generation, initial + day)
        assertTrue(status.claimHealthNotification(initial + day, generation))
        val reopened = MonitoringStatusRepository.forStore(store)
        assertEquals(initial + day, reopened.healthStatus().lastNotificationAt)
        assertFalse(reopened.claimHealthNotification(initial + day, generation))
        success(reopened, generation, initial + 2 * day)
        assertTrue(reopened.claimHealthNotification(initial + 2 * day, generation))
    }

    @Test fun concurrentRepositoryClaimsAreAtomicWithoutAnAdditionalNotificationMutex() = runBlocking {
        val store = MemoryStore()
        val (status, generation) = setup(store)
        success(status, generation, initial + day)
        val claims = List(10) { async { MonitoringStatusRepository.forStore(store).claimHealthNotification(initial + day, generation) } }
        assertEquals(1, claims.count { it.await() })
    }

    @Test fun closeFcmAndBackupSuccessInsideExistingExecutionMutexNotifyOnce() = runBlocking {
        val (status, generation) = setup(MemoryStore())
        val claims = listOf(MailCheckTrigger.FCM, MailCheckTrigger.SCHEDULED_WORK).mapIndexed { index, trigger ->
            async {
                MailCheckWorker.executionMutex.withLock {
                    val time = initial + day + index
                    success(status, generation, time, trigger)
                    status.claimHealthNotification(time, generation)
                }
            }
        }
        assertEquals(1, claims.count { it.await() })
    }

    @Test fun recoveredFailureHistorySurvivesSuccessAndRepositoryRecreation() = runBlocking {
        val store = MemoryStore()
        val (status, generation) = setup(store)
        status.recordCheckStarted(initial + day - 20, generation)
        status.recordFailure(initial + day - 10, 3, generation)
        success(status, generation, initial + day)
        val reopened = MonitoringStatusRepository.forStore(store)
        assertEquals(initial + day - 10, reopened.healthStatus().lastFailedCheckAt)
        assertEquals(0, reopened.current().consecutiveFailureCount)
        assertFalse(reopened.claimHealthNotification(initial + day, generation))
        success(reopened, generation, initial + 2 * day)
        assertTrue(reopened.claimHealthNotification(initial + 2 * day, generation))
    }

    @Test fun settingOffAndReenableWaitANewDayWithoutChangingMonitoringOrSchedule() = runBlocking {
        val store = MemoryStore()
        val (status, generation) = setup(store)
        val settings = SettingsRepository.forStore(store)
        val execution = status.executionStatus()
        settings.updateHealthNotificationEnabled(false)
        assertEquals(execution, status.executionStatus())
        success(status, generation, initial + day)
        assertFalse(status.claimHealthNotification(initial + day, generation))
        settings.updateHealthNotificationEnabled(true)
        success(status, generation, initial + day + 1)
        assertFalse(status.claimHealthNotification(initial + day + 1, generation))
        success(status, generation, initial + 2 * day + 1)
        assertTrue(status.claimHealthNotification(initial + 2 * day + 1, generation))
    }

    @Test fun monitoringSaveCannotOverwriteAnIndependentlyDisabledHealthSetting() = runBlocking {
        val store = MemoryStore()
        setup(store)
        val settings = SettingsRepository.forStore(store)
        settings.updateHealthNotificationEnabled(false)
        settings.updateMonitoringSettings(5, 3, true, true)
        assertFalse(settings.settings.first().healthNotificationEnabled)
        assertTrue(settings.settings.first().monitoringEnabled)
    }

    @Test fun monitoringFormSavesHealthPreferenceTogetherAndReenableWaitsANewDay() = runBlocking {
        val store = MemoryStore()
        val (status, generation) = setup(store)
        val settings = SettingsRepository.forStore(store)
        assertTrue(settings.settings.first().healthNotificationEnabled)
        val execution = status.executionStatus()
        settings.updateMonitoringSettings(3, 4, true, false, healthNotificationEnabled = false)
        assertEquals(execution, status.executionStatus())
        val saved = settings.settings.first()
        assertEquals(3, saved.pollIntervalMinutes)
        assertEquals(4, saved.consecutiveFailureThreshold)
        assertFalse(saved.checkImmediatelyOnAppStart)
        assertFalse(saved.healthNotificationEnabled)
        success(status, generation, initial + day)
        assertFalse(status.claimHealthNotification(initial + day, generation))
        settings.updateMonitoringSettings(3, 4, true, false, healthNotificationEnabled = true)
        success(status, generation, initial + day + 1)
        assertFalse(status.claimHealthNotification(initial + day + 1, generation))
        success(status, generation, initial + 2 * day + 1)
        assertTrue(status.claimHealthNotification(initial + 2 * day + 1, generation))
        settings.updateMonitoringSettings(3, 4, false, false, healthNotificationEnabled = true)
        assertFalse(settings.settings.first().monitoringEnabled)
        assertTrue(settings.settings.first().healthNotificationEnabled)
    }

    @Test fun monitoringOffManualSuccessDoesNotReportAndReenableRestartsEvidence() = runBlocking {
        val store = MemoryStore()
        val (status, _) = setup(store)
        val settings = SettingsRepository.forStore(store)
        settings.update(settings.settings.first().copy(monitoringEnabled = false))
        val offGeneration = status.changeSchedule(false, null)
        success(status, offGeneration, initial + day, MailCheckTrigger.MANUAL)
        assertFalse(status.claimHealthNotification(initial + day, offGeneration))
        assertNull(status.healthStatus().observingSince)
        settings.update(settings.settings.first().copy(monitoringEnabled = true))
        val onGeneration = status.changeSchedule(true, initial + 2 * day, "PERIODIC", 15)
        success(status, onGeneration, initial + day + 1)
        assertFalse(status.claimHealthNotification(initial + day + 1, onGeneration))
    }

    @Test fun staleAttemptFailureRemainsHistoricalEvidenceButCannotChangeScheduleOrClaim() = runBlocking {
        val (status, generation) = setup(MemoryStore())
        success(status, generation, initial + day)
        val next = status.changeSchedule(true, initial + day + 900_000, "PERIODIC", 15)
        val before = status.executionStatus()
        status.recordFailure(initial + day, 1, generation)
        assertEquals(before, status.executionStatus())
        assertEquals(initial + day, status.healthStatus().lastFailedCheckAt)
        assertFalse(status.claimHealthNotification(initial + day, generation))
        assertFalse(status.claimHealthNotification(initial + day, next))
    }

    @Test fun backupSkipDoesNotChangeSuccessfulCheckOrHealthHistory() = runBlocking {
        val (status, generation) = setup(MemoryStore())
        val before = status.healthStatus()
        status.advanceBackupWindow(initial + 900_000, generation, 15)
        assertEquals(before, status.healthStatus())
        assertFalse(status.claimHealthNotification(initial + 900_000, generation))
    }

    @Test fun unfinishedCheckRestartsEvidenceAndCancellationDoesNotChangeFailureCounter() = runBlocking {
        val (status, generation) = setup(MemoryStore())
        status.recordCheckStarted(initial + day, generation)
        success(status, generation, initial + day + 1)
        assertEquals(initial + day + 1, status.healthStatus().observingSince)
        assertFalse(status.claimHealthNotification(initial + day + 1, generation))
        status.recordHealthInterruption(initial + day + 2)
        assertEquals(0, status.current().consecutiveFailureCount)
        assertEquals(initial + day + 2, status.healthStatus().lastFailedCheckAt)
        assertNull(status.healthStatus().observingSince)
    }
}
