package direct.kabu.alertkun.monitoring

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** No user settings/credentials writes, no network or real mail checks. Own notification only. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
class MonitoringStatusNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun localizedBodyAndOnlyAlertOnceUseIndependentIdAndChannel() {
        val ja = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.JAPANESE) })
        val en = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val now = System.currentTimeMillis()
        listOf(ja, en).forEach { localized ->
            val notifier = MonitoringStatusNotifier(localized)
            val normal = notifier.notification(now, false)
            val silent = notifier.notification(now, true)
            val normalText = normal.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            val silentText = silent.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertEquals(3, normalText.lines().size)
            assertEquals(2, silentText.lines().size)
            assertTrue(normalText.contains(localized.getString(R.string.monitoring_status_silent_recommendation)))
            assertFalse(silentText.contains(localized.getString(R.string.monitoring_status_silent_recommendation)))
            assertTrue(normal.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(0, normal.flags and Notification.FLAG_FOREGROUND_SERVICE)
            assertNull(normal.fullScreenIntent)
            assertEquals(if (localized === ja) "アラートくん" else "Alert-kun",
                normal.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(MonitoringStatusNotifier.CHANNEL_ID, normal.channelId)
        }
        assertNotEquals(MonitoringHealthNotifier.CHANNEL_ID, MonitoringStatusNotifier.CHANNEL_ID)
        assertNotEquals(MonitoringHealthNotifier.NOTIFICATION_ID, MonitoringStatusNotifier.NOTIFICATION_ID)
    }

    @Test fun fixedIdUpdatesOnceAndCancelsWithoutTouchingOtherNotifications() {
        assertTrue("Status notification initially absent", manager.activeNotifications.none { it.id == MonitoringStatusNotifier.NOTIFICATION_ID })
        val events = mutableListOf<String>()
        val notifier = MonitoringStatusNotifier(context, { true to 123L }, observation = { event, _ -> events.add(event) })
        val others = manager.activeNotifications.filter { it.id != MonitoringStatusNotifier.NOTIFICATION_ID }.map { it.id }.toSet()
        try {
            // Keep this fixture independent of the preceding test's package notification rate.
            android.os.SystemClock.sleep(1_000)
            val now = System.currentTimeMillis()
            notifier.onFcmReceived(now, "test-first")
            waitUntil { manager.activeNotifications.any { it.id == MonitoringStatusNotifier.NOTIFICATION_ID } }
            android.os.SystemClock.sleep(250)
            notifier.onFcmReceived(now + 60_000, "test-second")
            waitUntil { manager.activeNotifications.any { it.id == MonitoringStatusNotifier.NOTIFICATION_ID && it.notification.`when` == now + 60_000 } }
            assertEquals(1, manager.activeNotifications.count { it.id == MonitoringStatusNotifier.NOTIFICATION_ID })
            assertTrue(events.contains("MONITORING_NOTIFICATION_POST"))
            assertTrue(events.contains("MONITORING_NOTIFICATION_UPDATE"))
            notifier.cancel(123)
            waitUntil { manager.activeNotifications.none { it.id == MonitoringStatusNotifier.NOTIFICATION_ID } }
            assertTrue(events.contains("MONITORING_NOTIFICATION_CANCEL"))
            assertTrue(others.all { id -> manager.activeNotifications.any { it.id == id } })
        } finally { notifier.cancel(123) }
    }

    @Test fun deniedPermissionAndStateReadFailureDoNotThrowOrPost() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val denied = MonitoringStatusNotifier(context, { true to 123L }, { false }, { event, values -> events.add(event to values) })
        denied.onFcmReceived(System.currentTimeMillis(), "test-denied")
        assertTrue(events.any { it.first == "MONITORING_NOTIFICATION_BLOCKED" && it.second["reason"] == "PERMISSION_DENIED" })
        val failedRead = MonitoringStatusNotifier(context, { error("State unavailable") }, observation = { event, values -> events.add(event to values) })
        failedRead.onFcmReceived(System.currentTimeMillis(), "test-state-failure")
        assertTrue(events.any { it.second["reason"] == "NOTIFICATION_ERROR" })
        assertTrue(manager.activeNotifications.none { it.id == MonitoringStatusNotifier.NOTIFICATION_ID })
    }

    @Test fun matchedContentReusesAlarmResourcesInBothLanguagesWithoutLoggingMailContent() {
        val alarm = AlarmEvent("fixture-mail", "fixture-rule", "Fixture rule", "Private fixture subject", null,
            VibrationPattern.NONE, AlarmMode.ONCE, mailFrom = "fixture@example.invalid", receivedAtEpochMillis = 42L)
        listOf(Locale.JAPANESE, Locale.ENGLISH).forEach { locale ->
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            val notification = MonitoringStatusNotifier(localized).notification(System.currentTimeMillis(), true, alarm)
            assertEquals(localized.getString(R.string.alarm_notification_title, alarm.ruleName), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(alarm.mailSubject, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertTrue(text.contains(alarm.mailSubject!!)); assertTrue(text.contains(alarm.mailFrom!!))
            assertTrue(text.contains(alarm.ruleName))
            assertFalse(text.contains(localized.getString(R.string.monitoring_status_silent_recommendation)))
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        }
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val denied = MonitoringStatusNotifier(context, { true to 123L }, { false }, { event, fields -> events += event to fields })
        denied.onCheckCompleted(System.currentTimeMillis(), alarm, 123L, MailCheckTrigger.FCM, "fixture-work")
        assertTrue(events.any { it.second["resultState"] == "RULE_MATCH" && it.second["notificationPhase"] == "CHECK_RESULT" })
        val logged = events.toString()
        listOf(alarm.mailSubject!!, alarm.mailFrom!!, alarm.ruleName).forEach { assertFalse(logged.contains(it)) }
    }

    @Test fun oldGenerationResultCannotReplaceCurrentNotification() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val notifier = MonitoringStatusNotifier(context, { true to 124L }, observation = { event, fields -> events += event to fields })
        notifier.onCheckCompleted(System.currentTimeMillis(), null, 123L, MailCheckTrigger.FCM, "old-work")
        assertTrue(events.any { it.second["reason"] == "GENERATION_MISMATCH" })
        assertFalse(events.any { it.first in listOf("MONITORING_NOTIFICATION_POST", "MONITORING_NOTIFICATION_UPDATE") })
    }

    @Test fun checkingAndFailureAreLocalizedAndNeverClaimNormal() {
        for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            val notifier = MonitoringStatusNotifier(localized)
            val at = 123_456L
            val states = listOf(MonitoringStatusResult.Checking,
                MonitoringStatusResult.Failure(1, 3), MonitoringStatusResult.Failure(2, 3),
                MonitoringStatusResult.Failure(3, 3), MonitoringStatusResult.Failure(4, 3),
                MonitoringStatusResult.Failure(1, 1), MonitoringStatusResult.Failure(1, 99))
            for (state in states) {
                val notification = notifier.notification(at, true, state)
                val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                assertFalse(text.contains(localized.getString(R.string.monitoring_status_no_issues)))
                val expected = when (state) {
                    MonitoringStatusResult.Checking -> localized.getString(R.string.monitoring_status_checking)
                    is MonitoringStatusResult.Failure -> if (state.count < state.threshold)
                        localized.getString(R.string.monitoring_status_check_failed, state.count, state.threshold)
                    else localized.getString(R.string.monitoring_status_consecutive_failures, state.count)
                    else -> error("Unexpected fixture")
                }
                assertTrue(text.contains(expected))
                assertEquals(at, notification.`when`)
                assertEquals(MonitoringStatusNotifier.CHANNEL_ID, notification.channelId)
                assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertFalse(text.contains(localized.getString(R.string.monitoring_status_silent_recommendation)))
                val audible = notifier.notification(at, false, state).extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                assertTrue(audible.contains(localized.getString(R.string.monitoring_status_silent_recommendation)))
            }
        }
        assertNotEquals("alertkun_monitoring_failure", MonitoringStatusNotifier.CHANNEL_ID)
        assertNotEquals(6_002, MonitoringStatusNotifier.NOTIFICATION_ID)
        assertNotEquals(direct.kabu.alertkun.alarm.AlarmController.NOTIFICATION_ID, MonitoringStatusNotifier.NOTIFICATION_ID)
    }

    @Test fun checkingFailureAndRecoveryReplaceSameIdUsingFinalCompletionTime() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val notifier = MonitoringStatusNotifier(context, { true to 123L }, observation = { event, values -> events += event to values })
        val at = System.currentTimeMillis()
        try {
            // Android limits package notification updates per second. Real polls are minutes apart;
            // let preceding integration-test posts expire and pace this synthetic burst below 5/s.
            android.os.SystemClock.sleep(1_000)
            notifier.onFcmReceived(at, "fixture-receipt")
            waitUntil { manager.activeNotifications.any { it.id == MonitoringStatusNotifier.NOTIFICATION_ID && it.notification.`when` == at } }
            assertTrue(manager.activeNotifications.single { it.id == MonitoringStatusNotifier.NOTIFICATION_ID }.notification
                .extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains(context.getString(R.string.monitoring_status_checking)))
            for (count in 1..4) {
                android.os.SystemClock.sleep(250)
                notifier.onCheckResult(at + count, MonitoringStatusResult.Failure(count, 3), 123L,
                    MailCheckTrigger.SCHEDULED_WORK, "fixture-work")
                waitUntil { manager.activeNotifications.any { it.id == MonitoringStatusNotifier.NOTIFICATION_ID && it.notification.`when` == at + count } }
                val active = manager.activeNotifications.filter { it.id == MonitoringStatusNotifier.NOTIFICATION_ID }
                assertEquals(1, active.size)
                assertFalse(active.single().notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                    .contains(context.getString(R.string.monitoring_status_no_issues)))
            }
            assertFalse(events.any { it.second["resultState"] == "NORMAL" })
            assertTrue(events.any { it.second["resultState"] == "CHECKING" && it.second["notificationPhase"] == "RECEIPT" })
            assertTrue(events.any { it.second["resultState"] == "FAILURE" && it.second["notificationPhase"] == "CHECK_RESULT" })
            android.os.SystemClock.sleep(250)
            notifier.onCheckResult(at + 5, MonitoringStatusResult.Success(null), 123L, MailCheckTrigger.MANUAL, "fixture-recovery")
            waitUntil { manager.activeNotifications.any { it.id == MonitoringStatusNotifier.NOTIFICATION_ID && it.notification.`when` == at + 5 } }
            val recovered = manager.activeNotifications.single { it.id == MonitoringStatusNotifier.NOTIFICATION_ID }.notification
            assertTrue(recovered.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                .contains(context.getString(R.string.monitoring_status_no_issues)))
        } finally {
            notifier.cancel(123L)
            waitUntil { manager.activeNotifications.none { it.id == MonitoringStatusNotifier.NOTIFICATION_ID } }
        }
    }

    @Test fun failureResultsRespectOffAndGenerationGuards() {
        for (state in listOf(false to 123L, true to 124L)) {
            val events = mutableListOf<Pair<String, Map<String, Any?>>>()
            val notifier = MonitoringStatusNotifier(context, { state }, observation = { event, fields -> events += event to fields })
            notifier.onCheckResult(System.currentTimeMillis(), MonitoringStatusResult.Failure(3, 3), 123L,
                MailCheckTrigger.FCM, "fixture-work")
            assertTrue(events.any { it.second["reason"] == if (state.first) "GENERATION_MISMATCH" else "MONITORING_OFF" })
            assertFalse(events.any { it.first in listOf("MONITORING_NOTIFICATION_POST", "MONITORING_NOTIFICATION_UPDATE") })
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val limit = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < limit) {
            if (condition()) return
            android.os.SystemClock.sleep(20)
        }
        assertTrue("Notification state became observable", condition())
    }
}
