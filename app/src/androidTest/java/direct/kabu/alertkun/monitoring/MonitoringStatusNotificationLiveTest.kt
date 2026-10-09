package direct.kabu.alertkun.monitoring

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.R
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.push.FcmRegistrationCoordinator
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in natural FCM test. Only the new channel is changed through Android Settings; monitoring restored. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
class MonitoringStatusNotificationLiveTest {
    @Test fun naturalFcmPostsThenUpdatesAfterAndroidSilentSettingAndOffCancels() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("monitoringNotificationLive") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(app)
        val before = settings.settings.first()
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifier = MonitoringStatusNotifier(app)
        val log = File(app.filesDir, "observability/monitoring-observation.log")
        val otherChannels = manager.notificationChannels.filter { it.id != MonitoringStatusNotifier.CHANNEL_ID }
            .associate { it.id to Triple(it.importance, it.sound, it.shouldVibrate()) }
        var turnedOff = false
        suspend fun monitoring(enabled: Boolean) {
            MonitoringDevicePreparation.apply(app, before, enabled)
        }
        fun status() = manager.activeNotifications.singleOrNull { it.id == MonitoringStatusNotifier.NOTIFICATION_ID }?.notification
        suspend fun receiptAfter(start: Int, event: String, silent: Boolean): Notification {
            val timeoutAt = android.os.SystemClock.elapsedRealtime() + 9 * 60_000L
            while (android.os.SystemClock.elapsedRealtime() < timeoutAt) {
                val lines = log.readLines().drop(start)
                val receipt = lines.indexOfFirst { it.contains(" FCM_RECEIVED ") && it.contains("action=CHECK_MAIL") }
                val post = lines.indexOfFirst { it.contains(" $event ") && it.contains("silent=$silent") }
                val request = lines.indexOfFirst { it.contains(" WORK_ENQUEUE_REQUEST ") && it.contains("trigger=FCM") }
                val end = lines.lastOrNull { it.contains(" WORKER_END ") && it.contains("trigger=FCM") && it.contains("checkResult=SUCCESS") }
                if (receipt >= 0 && post > receipt && request > post && end != null && status() != null) {
                    val id = Regex("workId=([^ ]+)").find(end)!!.groupValues[1]
                    val work = lines.filter { it.contains("workId=$id ") }
                    assertTrue(work.any { it.contains(" WORKER_START ") })
                    assertTrue(work.any { it.contains(" MAIL_CONNECT_SUCCESS ") && it.contains("protocol=POP3S") })
                    assertTrue(work.any { it.contains(" CHECK_SUCCESS ") })
                    assertTrue(lines[receipt].contains("priority=") && lines[receipt].contains("originalPriority=") && lines[receipt].contains("sentTime="))
                    assertEquals(1, manager.activeNotifications.count { it.id == MonitoringStatusNotifier.NOTIFICATION_ID })
                    return status()!!
                }
                delay(1000)
            }
            error("Natural FCM notification and successful POP3S check were not observed")
        }
        try {
            assertFalse("Opt-in test starts with monitoring OFF", before.monitoringEnabled)
            assertNull("Status notification initially absent", status())
            notifier.createChannel()
            val initial = manager.getNotificationChannel(MonitoringStatusNotifier.CHANNEL_ID)
            assertEquals("New channel starts at DEFAULT", NotificationManager.IMPORTANCE_DEFAULT, initial.importance)
            val firstStart = log.readLines().size
            FcmRegistrationCoordinator.start(app)
            monitoring(true)
            val first = receiptAfter(firstStart, "MONITORING_NOTIFICATION_POST", false)
            val firstText = first.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertTrue(firstText.contains(app.getString(R.string.monitoring_status_silent_recommendation)))

            app.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, MonitoringStatusNotifier.CHANNEL_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val settingsTimeout = android.os.SystemClock.elapsedRealtime() + 15_000
            var clicked = false
            while (android.os.SystemClock.elapsedRealtime() < settingsTimeout && !clicked) {
                val root = automation.rootInActiveWindow
                if (root != null) clicked = clickSilent(root)
                if (!clicked) delay(200)
            }
            assertTrue("Silent chosen in Android channel Settings", clicked)
            val channelTimeout = android.os.SystemClock.elapsedRealtime() + 5_000
            while (android.os.SystemClock.elapsedRealtime() < channelTimeout && manager.getNotificationChannel(MonitoringStatusNotifier.CHANNEL_ID).importance > NotificationManager.IMPORTANCE_LOW) delay(100)
            val silentChannel = manager.getNotificationChannel(MonitoringStatusNotifier.CHANNEL_ID)
            assertTrue(silentChannel.importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW)
            val secondStart = log.readLines().size
            val second = receiptAfter(secondStart, "MONITORING_NOTIFICATION_UPDATE", true)
            assertTrue(second.`when` > first.`when`)
            val secondText = second.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertFalse(secondText.contains(app.getString(R.string.monitoring_status_silent_recommendation)))
            assertEquals(2, secondText.lines().size)

            monitoring(false)
            turnedOff = true
            val cancelTimeout = android.os.SystemClock.elapsedRealtime() + 5_000
            while (android.os.SystemClock.elapsedRealtime() < cancelTimeout && status() != null) delay(20)
            assertNull("Monitoring OFF cancels status", status())
            val thirdStart = log.readLines().size
            monitoring(true)
            turnedOff = false
            receiptAfter(thirdStart, "MONITORING_NOTIFICATION_POST", true)
            // Exercise denial without revoking the user's OS permission; existing FCM work still executes.
            val deniedStart = log.readLines().size
            val generation = MonitoringStatusRepository(app).executionStatus().scheduleGeneration
            MonitoringStatusNotifier(app, { true to generation }, { false }).onFcmReceived(System.currentTimeMillis(), "permission-test")
            MonitoringScheduler.runNow(app, MailCheckTrigger.FCM, "permission-test")
            val deniedTimeout = android.os.SystemClock.elapsedRealtime() + 60_000
            var continued = false
            while (android.os.SystemClock.elapsedRealtime() < deniedTimeout) {
                val lines = log.readLines().drop(deniedStart)
                if (lines.any { it.contains(" MONITORING_NOTIFICATION_BLOCKED ") && it.contains("reason=PERMISSION_DENIED") } &&
                    lines.any { it.contains(" CHECK_SUCCESS ") && it.contains("trigger=FCM") }) {
                    continued = true
                    break
                }
                delay(200)
            }
            assertTrue("Mail check continues after notification permission denial", continued)
            otherChannels.forEach { (id, saved) ->
                val current = manager.getNotificationChannel(id)
                assertEquals("Existing channel settings preserved", saved, Triple(current.importance, current.sound, current.shouldVibrate()))
            }
        } finally {
            if (!turnedOff) monitoring(false)
        }
        assertEquals(before, settings.settings.first())
    }

    private fun clickSilent(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString() in listOf("Silent", "サイレント", "マナー")) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                target = target.parent
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (clickSilent(child)) return true
        }
        return false
    }
}
