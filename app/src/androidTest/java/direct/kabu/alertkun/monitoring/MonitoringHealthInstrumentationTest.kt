package direct.kabu.alertkun.monitoring

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.entity.ReceivedMailEntity
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MonitoringSettings
import direct.kabu.alertkun.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Pixel-safe: isolated DataStore + in-memory Room, no network, no real settings/credentials writes. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
class MonitoringHealthInstrumentationTest {
    private val day = MonitoringHealthNotificationPolicy.DAY_MILLIS
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun posted(): Notification {
        val limit = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < limit) {
            manager.activeNotifications.singleOrNull { it.id == MonitoringHealthNotifier.NOTIFICATION_ID }?.let { return it.notification }
            android.os.SystemClock.sleep(20)
        }
        error("Health notification did not become active")
    }

    private fun isolated(test: suspend (MonitoringStatusRepository, SettingsRepository, Long, Long) -> Unit) = runBlocking {
        check(manager.activeNotifications.none { it.id == MonitoringHealthNotifier.NOTIFICATION_ID }) {
            "An existing health notification must not be overwritten by this test"
        }
        val file = File(context.cacheDir, "health-test-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val settings = SettingsRepository.forStore(store)
            settings.update(MonitoringSettings(monitoringEnabled = true))
            val status = MonitoringStatusRepository.forStore(store)
            val generation = status.changeSchedule(true, System.currentTimeMillis() + 900_000, "PERIODIC", 15)
            val now = System.currentTimeMillis()
            status.recordCheckStarted(now - day - 1, generation)
            status.recordSuccess(now - day, MailCheckTrigger.FCM, generation)
            status.recordCheckStarted(now - 1, generation)
            status.recordSuccess(now, MailCheckTrigger.FCM, generation)
            MailCheckWorker.executionMutex.withLock { test(status, settings, generation, now) }
        } finally {
            manager.cancel(MonitoringHealthNotifier.NOTIFICATION_ID)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Suppress("DEPRECATION") // Also verify pre-channel sound/vibration fields used on supported API 24/25.
    @Test fun healthyReportPostsOnceWithZeroAlertsAndSilentLowChannel() = isolated { status, _, generation, now ->
        val notifier = MonitoringHealthNotifier(context)
        assertTrue("Notification permission is needed for this device check", notifier.canPost())
        val reporter = MonitoringHealthReporter(context, status) { _, _ -> 0 }
        assertTrue(reporter.report(now, generation))
        assertFalse(reporter.report(now, generation))
        val channel = manager.getNotificationChannel(MonitoringHealthNotifier.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        val posted = posted()
        assertNull(posted.sound)
        assertNull(posted.vibrate)
        assertNull(posted.fullScreenIntent)
        assertEquals(0, posted.defaults)
        assertEquals(now, posted.`when`)
        assertEquals(Notification.PRIORITY_LOW, posted.priority)
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("0"))
        assertEquals(now, status.healthStatus().lastNotificationAt)
    }

    @Test fun alertCountUsesProcessedTriggeredRowsAndInclusiveTimeBoundaries() = isolated { status, _, generation, now ->
        val db = Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build()
        try {
            val dao = db.receivedMailDao()
            suspend fun mail(id: String, time: Long, triggered: Boolean, state: MailProcessingState = MailProcessingState.PROCESSED,
                rule: String? = "test-rule") {
                dao.insertReceivedMail(ReceivedMailEntity(id, "health-test", id, time, null, "{}", null, null,
                    false, state, triggered, rule))
            }
            mail("start-boundary", now - day, true)
            mail("inside", now - 1000, true)
            mail("end-boundary", now, true)
            mail("old", now - day - 1, true)
            mail("future", now + 1, true)
            mail("matching-not-adopted", now, false)
            mail("unmatched", now, false, rule = null)
            mail("received-not-finalized", now, true, MailProcessingState.RECEIVED)
            assertEquals(3, dao.countTriggeredAlerts(now - day, now))
            assertEquals(0, dao.countTriggeredAlerts(now + 2, now + 3))
            assertTrue(MonitoringHealthReporter(context, status, dao::countTriggeredAlerts).report(now, generation))
            val notification = posted()
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("3"))
        } finally { db.close() }
    }

    @Test fun settingOffAndMonitoringOffPreventPostingWithoutTouchingRealMonitoring() = isolated { status, settings, generation, now ->
        val reporter = MonitoringHealthReporter(context, status) { _, _ -> error("OFF must not query mail") }
        settings.updateHealthNotificationEnabled(false)
        assertFalse(reporter.report(now, generation))
        settings.update(MonitoringSettings(monitoringEnabled = false, healthNotificationEnabled = true))
        status.changeSchedule(false, null)
        assertFalse(reporter.report(now, generation))
        assertTrue(manager.activeNotifications.none { it.id == MonitoringHealthNotifier.NOTIFICATION_ID })
    }

    @Test fun reportCountFailureDoesNotTurnMailSuccessIntoMonitoringFailure() = isolated { status, _, generation, now ->
        assertFalse(MonitoringHealthReporter(context, status) { _, _ -> error("isolated count failure") }.report(now, generation))
        assertTrue(status.healthStatus().lastAttemptSucceeded)
        assertEquals(0, status.current().consecutiveFailureCount)
        assertNull(status.healthStatus().lastNotificationAt)
    }

    @Test fun japaneseAndEnglishReportContainCountAndSuccessfulCheckDate() {
        for (tag in listOf("ja", "en")) {
            val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(tag))
            })
            val now = System.currentTimeMillis()
            val notification = MonitoringHealthNotifier(localized).notification(now, 3)
            val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            val timestamp = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
            assertEquals(localized.getString(R.string.monitoring_health_message, 3, timestamp), text)
            assertEquals(localized.getString(R.string.monitoring_health_title), notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        }
    }

    @Test fun healthClaimAndFailureHistoryPersistAcrossStoreReopening() = runBlocking {
        val file = File(context.cacheDir, "health-reopen-${UUID.randomUUID()}.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val now = System.currentTimeMillis()
        try {
            val store = PreferenceDataStoreFactory.create(scope = firstScope) { file }
            SettingsRepository.forStore(store).update(MonitoringSettings(monitoringEnabled = true))
            val status = MonitoringStatusRepository.forStore(store)
            val generation = status.changeSchedule(true, now + 900_000, "PERIODIC", 15)
            status.recordCheckStarted(now - day - 1, generation)
            status.recordSuccess(now - day, MailCheckTrigger.FCM, generation)
            status.recordCheckStarted(now - 1, generation)
            status.recordSuccess(now, MailCheckTrigger.FCM, generation)
            assertTrue(status.claimHealthNotification(now, generation))
            status.recordCheckStarted(now + 1, generation)
            status.recordFailure(now + 2, 3, generation)
        } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = MonitoringStatusRepository.forStore(PreferenceDataStoreFactory.create(scope = reopenedScope) { file })
            assertEquals(now, reopened.healthStatus().lastNotificationAt)
            assertEquals(now + 2, reopened.healthStatus().lastFailedCheckAt)
            assertEquals(now, reopened.healthStatus().lastSuccessfulCheckAt)
        } finally {
            reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
