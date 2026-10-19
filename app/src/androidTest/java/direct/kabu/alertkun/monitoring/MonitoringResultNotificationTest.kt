package direct.kabu.alertkun.monitoring

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Disposable TCP mailbox and in-memory store; never edits user rules, mail, accounts or credentials. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
class MonitoringResultNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val controller = AlarmController.getInstance(context)
    private val secrets = object : CredentialStore {
        override fun load(key: String) = "fixture-secret"
        override fun save(key: String, password: String) = Unit
        override fun delete(key: String) = Unit
    }

    @Test fun localMailboxRuleMatchesMirrorRealAlarmThenNormalCheckRestoresSingleStatus() = runBlocking {
        val channelBefore = manager.getNotificationChannel(MonitoringStatusNotifier.CHANNEL_ID)
        val otherChannels = manager.notificationChannels.filter { it.id != MonitoringStatusNotifier.CHANNEL_ID }
            .associate { it.id to Triple(it.importance, it.sound, it.shouldVibrate()) }
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val log = java.io.File(context.filesDir, "observability")
        val logStart = if (log.exists()) observationTestLines(log).size else 0
        var enabled = true
        val notifier = MonitoringStatusNotifier(context, { enabled to 123L }, observation = { event, fields ->
            events += event to fields
            // Explicitly distinguish fixture events from real FCM/Worker activity in overnight evidence.
            MonitoringObservation.record(context, event, *(fields + ("trigger" to "INSTRUMENTATION")).toList().toTypedArray())
        })
        try {
            for ((keyword, sound, mode) in listOf(Triple("WARN", AlarmSoundType.NOTIFICATION, AlarmMode.FIVE_TIMES),
                Triple("ALERT", AlarmSoundType.ALARM, AlarmMode.ONCE))) {
                controller.stop()
                Pop3TestServer(listOf("disposable")).use { server ->
                    server.subjects = mapOf("disposable" to "$keyword fixture")
                    val rule = AlertRule("fixture-rule", "$keyword fixture rule", keyword, RuleTarget.SUBJECT, 1,
                        null, VibrationPattern.PATTERN_3, mode, true, soundType = sound)
                    val store = MemoryStore()
                    var handoffs = 0
                    val check = MailCheckCoordinator(store, object : MonitoringRuleSource {
                        override suspend fun allRules() = listOf(rule)
                    }, AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), secrets),
                        object : MonitoringAlarmSink { override fun start(event: AlarmEvent) {
                            handoffs++
                            controller.start(event)
                            await { player()?.isPlaying == true }
                            await { vibrationDump().substringAfter("CurrentVibration:").substringBefore("NextVibration:").contains(context.packageName) }
                        } })
                    val now = System.currentTimeMillis()
                    notifier.onFcmReceived(now, "fixture-receipt")
                    await { status()?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        ?.contains(context.getString(R.string.monitoring_status_checking)) == true }
                    assertFalse(status()!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                        .contains(context.getString(R.string.monitoring_status_no_issues)))
                    val result = check.check()
                    assertTrue("Fixture check failed: ${result.error?.javaClass?.simpleName}", result.success)
                    assertEquals(1, handoffs); assertNotNull(result.selectedAlarm)
                    await { manager.activeNotifications.any { it.id in setOf(AlarmController.NOTIFICATION_ID, AlarmController.PLAYBACK_NOTIFICATION_ID) } }
                    notifier.onCheckCompleted(System.currentTimeMillis(), result.selectedAlarm, 123L, MailCheckTrigger.FCM, "fixture-$keyword")
                    await { status()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "$keyword fixture" }
                    val alarm = manager.activeNotifications.first { it.id in setOf(AlarmController.NOTIFICATION_ID, AlarmController.PLAYBACK_NOTIFICATION_ID) }.notification
                    val mirrored = status()!!
                    assertEquals(alarm.extras.getCharSequence(Notification.EXTRA_TITLE).toString(), mirrored.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                    assertEquals(alarm.extras.getCharSequence(Notification.EXTRA_TEXT).toString(), mirrored.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                    val details = mirrored.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                    assertTrue(details.startsWith(alarm.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()))
                    assertEquals(MonitoringStatusNotifier.CHANNEL_ID, mirrored.channelId)
                    assertNotEquals(alarm.channelId, mirrored.channelId)
                    assertTrue(mirrored.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                    assertNull(mirrored.fullScreenIntent)
                    assertEquals(0, mirrored.flags and Notification.FLAG_FOREGROUND_SERVICE)
                    assertEquals(1, manager.activeNotifications.count { it.id == MonitoringStatusNotifier.NOTIFICATION_ID })
                    val next = check.check()
                    assertTrue(next.success); assertNull(next.selectedAlarm); assertEquals(1, handoffs)
                    notifier.onCheckCompleted(System.currentTimeMillis(), next.selectedAlarm, 123L, MailCheckTrigger.SCHEDULED_WORK, "fixture-normal")
                    await { status()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == context.getString(R.string.monitoring_status_title) }
                    assertTrue(status()!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains(context.getString(R.string.monitoring_status_no_issues)))
                    assertTrue(manager.activeNotifications.any { it.id in setOf(AlarmController.NOTIFICATION_ID, AlarmController.PLAYBACK_NOTIFICATION_ID) })
                    assertFalse(server.commands.any { it.startsWith("DELE") })
                }
            }
            assertTrue(events.any { it.first == "MONITORING_NOTIFICATION_UPDATE" && it.second["resultState"] == "RULE_MATCH" })
            assertTrue(events.any { it.first == "MONITORING_NOTIFICATION_UPDATE" && it.second["resultState"] == "NORMAL" && it.second["notificationPhase"] == "CHECK_RESULT" })
            assertTrue(events.any { it.second["resultState"] == "CHECKING" && it.second["notificationPhase"] == "RECEIPT" })
            assertFalse(events.any { it.second["resultState"] == "NORMAL" && it.second["notificationPhase"] == "RECEIPT" })
            assertFalse(events.toString().contains("fixture@example.invalid"))
            val persisted = observationTestLines(log).drop(logStart).filter { it.contains("trigger=INSTRUMENTATION") }.joinToString("\n")
            assertTrue(persisted.contains("resultState=RULE_MATCH"))
            assertTrue(persisted.contains("resultState=NORMAL") && persisted.contains("notificationPhase=CHECK_RESULT"))
            assertFalse(persisted.contains("fixture@example.invalid"))
            assertFalse(persisted.contains("WARN fixture rule")); assertFalse(persisted.contains("ALERT fixture rule"))
            enabled = false
            notifier.onCheckCompleted(System.currentTimeMillis(), null, 123L, MailCheckTrigger.FCM, "fixture-off")
            await { status() == null }
            otherChannels.forEach { (id, saved) -> manager.getNotificationChannel(id).let {
                assertEquals(saved, Triple(it.importance, it.sound, it.shouldVibrate()))
            } }
            if (channelBefore != null) manager.getNotificationChannel(MonitoringStatusNotifier.CHANNEL_ID).let {
                assertEquals(Triple(channelBefore.importance, channelBefore.sound, channelBefore.shouldVibrate()), Triple(it.importance, it.sound, it.shouldVibrate()))
            }
        } finally { notifier.cancel(123L); controller.stop() }
    }

    private fun status() = manager.activeNotifications.singleOrNull { it.id == MonitoringStatusNotifier.NOTIFICATION_ID }?.notification
    private fun player(): MediaPlayer? = synchronized(controller) {
        AlarmController::class.java.getDeclaredField("player").apply { isAccessible = true }.get(controller) as MediaPlayer?
    }
    private fun vibrationDump() = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys vibrator_manager").use {
        java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Fixture notification/playback condition was not satisfied", condition())
    }
    private class MemoryStore : MonitoringMailStore {
        private val rows = mutableListOf<ReceivedMail>()
        override suspend fun receivedMails() = rows.filter { it.processingState == MailProcessingState.RECEIVED }
        override suspend fun saveReceived(mail: ReceivedMail): Boolean {
            if (rows.any { it.accountId == mail.accountId && it.remoteMessageId == mail.remoteMessageId }) return false
            rows += mail
            return true
        }
        override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {
            val index = rows.indexOfFirst { it.id == id }
            rows[index] = rows[index].copy(processingState = MailProcessingState.PROCESSED, alertTriggered = alertTriggered, matchedRuleId = matchedRuleId, backgroundColor = backgroundColor)
        }
    }
}
