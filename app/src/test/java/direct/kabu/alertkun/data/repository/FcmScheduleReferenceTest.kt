package direct.kabu.alertkun.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import direct.kabu.alertkun.push.fcmRegistrationTargets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class FcmScheduleReferenceTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
    private val store = MemoryStore()
    private val settings = SettingsRepository.forStore(store)
    private val status = MonitoringStatusRepository.forStore(store)
    private val time = System.currentTimeMillis() + 60_000L
    private suspend fun configure(interval: Int = 3, enabled: Boolean = true) {
        settings.updateMonitoringSettings(interval, 3, enabled, true)
    }
    private suspend fun register(at: Long = time) {
        status.recordGatewayRegistrationSuccess(at, settings.fcmRegistrationTargets().first().revision)
    }

    @Test fun intervalChangeRejectsOldFcmAndUsesNewSuccessfulRegistration() = runBlocking {
        configure()
        register()
        status.recordFcmReceived(time + 1_000)
        val oldRevision = settings.fcmRegistrationTargets().first().revision
        configure(5)
        assertNull(status.current().lastFcmReceivedAtEpochMillis)
        assertNull(status.current().lastGatewayRegistrationAtEpochMillis)
        status.recordFcmReceived(time + 2_000)
        assertNull(status.current().lastFcmReceivedAtEpochMillis)
        status.recordGatewayRegistrationSuccess(time + 2_000, oldRevision)
        assertNull(status.current().lastGatewayRegistrationAtEpochMillis)
        register(time + 3_000)
        assertEquals(time + 3_000, status.current().lastGatewayRegistrationAtEpochMillis)
        assertEquals(5, status.current().referenceIntervalMinutes)
        status.recordFcmReceived(time + 4_000)
        assertEquals(time + 4_000, status.state.first().lastFcmReceivedAtEpochMillis)
    }
    @Test fun offOnAndSameIntervalRoundTripCannotReusePreviousSession() = runBlocking {
        configure()
        register()
        status.recordFcmReceived(time)
        val oldRevision = settings.fcmRegistrationTargets().first().revision
        configure(enabled = false)
        status.recordFcmReceived(time + 1_000)
        assertFalse(status.current().isMonitoring)
        configure()
        assertTrue(status.current().isMonitoring)
        assertNull(status.current().lastFcmReceivedAtEpochMillis)
        status.recordGatewayRegistrationSuccess(time + 2_000, oldRevision)
        assertNull(status.current().lastGatewayRegistrationAtEpochMillis)
        register(time + 3_000)
        assertEquals(time + 3_000, status.current().lastGatewayRegistrationAtEpochMillis)
        configure(5)
        configure(3)
        assertNull(status.current().lastGatewayRegistrationAtEpochMillis)
    }
    @Test fun failedOrPendingRegistrationNeverPublishesSuccessAndStaleReceiptIsIgnored() = runBlocking {
        configure()
        assertNull(status.current().lastGatewayRegistrationAtEpochMillis)
        status.recordFcmReceived(1)
        assertNull(status.current().lastFcmReceivedAtEpochMillis)
        register()
        status.recordGatewayRegistrationSuccess(0, settings.fcmRegistrationTargets().first().revision)
        assertEquals(time, status.current().lastGatewayRegistrationAtEpochMillis)
    }
    @Test fun backupRebaseSkipAndSuccessDoNotChangePrimaryReference() = runBlocking {
        configure()
        register()
        status.recordFcmReceived(time + 1_000)
        val generation = status.changeSchedule(true, time + 900_000, "PERIODIC", 15)
        status.recordCheckStarted(time + 1_500, generation)
        status.recordSuccess(time + 2_000, MailCheckTrigger.FCM, generation)
        assertTrue(direct.kabu.alertkun.monitoring.BackupMonitoringPolicy.shouldSkipBackup(
            time + 900_000, generation, 15, status.executionStatus()))
        status.advanceBackupWindow(time + 900_000, generation, 15)
        assertEquals(time + 1_000, status.current().lastFcmReceivedAtEpochMillis)
        status.recordSuccess(time + 2_000, MailCheckTrigger.SCHEDULED_WORK, generation)
        status.advanceBackupWindow(time + 900_000, generation, 15)
        status.changeSchedule(true, time + 1_800_000, "PERIODIC", 15)
        assertEquals(time + 1_000, status.current().lastFcmReceivedAtEpochMillis)
        assertEquals(time, status.current().lastGatewayRegistrationAtEpochMillis)
        assertEquals(time + 2_000, status.current().lastSuccessfulCheckAtEpochMillis)
        assertEquals(status.current(), MonitoringStatusRepository.forStore(store).current())
    }
    @Test fun unrelatedSettingsNeverResetPrimarySchedule() = runBlocking {
        configure()
        register()
        val before = settings.fcmRegistrationTargets().first()
        settings.updateMonitoringSettings(3, 7, true, false, false)
        assertEquals(before, settings.fcmRegistrationTargets().first())
        assertEquals(time, status.current().lastGatewayRegistrationAtEpochMillis)
    }
    @Test fun tokenReregistrationResetsReferenceAndRejectsDelayedSaveOfEarlierReceipt() = runBlocking {
        configure()
        register()
        status.recordFcmReceived(time + 1_000)
        register(time + 2_000)
        assertNull(status.current().lastFcmReceivedAtEpochMillis)
        assertEquals(time + 2_000, status.current().lastGatewayRegistrationAtEpochMillis)
        status.recordFcmReceived(time + 1_500)
        assertNull(status.current().lastFcmReceivedAtEpochMillis)
        status.recordFcmReceived(time + 3_000)
        assertEquals(time + 3_000, status.current().lastFcmReceivedAtEpochMillis)
    }
    @Test fun receiptAfterSuccessSurvivesRegistrationSaveRace() = runBlocking {
        configure()
        register()
        status.recordFcmReceived(time + 3_000)
        register(time + 2_000)
        assertEquals(time + 3_000, status.current().lastFcmReceivedAtEpochMillis)
    }
}
