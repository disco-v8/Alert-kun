package direct.kabu.alertkun.alarm

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic alarms; no Room, accounts, credentials, monitoring settings or app-data reset. */
@SdkSuppress(minSdkVersion = 26)
class AlarmSessionInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = AlarmController.getInstance(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Test fun continuousSessionSurvivesScreenOffDismissalAndRepeatedHandoffAndStopsFromBothSurfaces() {
        // Never replace a human's live or pending alarm.
        assertNull("A live alarm must be stopped before this test", controller.pendingToken())
        assertNull(controller.session.value)
        assertTrue(controller.canScheduleExactAlarms())
        assertTrue(controller.canPostNotifications())
        val event = AlarmEvent("session-instrumentation", "test-rule", "Session test", null, null,
            VibrationPattern.PATTERN_2, AlarmMode.UNTIL_STOPPED, soundType = AlarmSoundType.ALARM)
        try {
            controller.start(event)
            await { controller.session.value != null && player() != null }
            val session = controller.session.value!!
            val originalPlayer = player()!!
            val token = controller.pendingToken()!!
            val focus = AlarmController::class.java.getDeclaredField("focusRequest")
                .apply { isAccessible = true }.get(controller) as AudioFocusRequest
            assertEquals(AlarmSoundMapping.usage(event.soundType), focus.audioAttributes.usage)
            assertTrue(originalPlayer.isLooping)
            assertTrue(originalPlayer.isPlaying)
            await { notification() != null }
            assertTrue(notification()!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertNotNull(notification()!!.deleteIntent)
            assertTrue(notification()!!.actions.isNotEmpty())
            controller.start(event.copy())
            assertSame(originalPlayer, player())
            assertEquals(token, controller.pendingToken())
            notification()!!.deleteIntent.send()
            SystemClock.sleep(200)
            assertEquals(session, controller.session.value)
            assertSame(originalPlayer, player())

            shell("input keyevent KEYCODE_SLEEP")
            SystemClock.sleep(35_000)
            assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
            assertEquals(session, controller.session.value)
            assertSame(originalPlayer, player())
            assertTrue(originalPlayer.isPlaying)
            assertTrue(serviceRunning())
            assertNotNull(notification())
            val uid = android.os.Process.myUid()
            val vibrator = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            else context.getSystemService(Vibrator::class.java)
            android.util.Log.i("AlarmSessionTest", "api=${Build.VERSION.SDK_INT} hasVibrator=${vibrator.hasVibrator()}")
            if (vibrator.hasVibrator()) {
                val currentVibration = if (Build.VERSION.SDK_INT >= 31)
                    shell("dumpsys vibrator_manager").substringAfter("CurrentVibration:").substringBefore("NextVibration:")
                else shell("dumpsys vibrator").substringBefore("Previous vibrations:")
                assertTrue("Synthetic alarm must still own vibration",
                    Regex("(?i)(?:mUid|uid)\\s*[=:]\\s*$uid\\b").containsMatchIn(currentVibration))
            }
            shell("input keyevent KEYCODE_WAKEUP")
            controller.stop("main UI")
            controller.stop("main UI")
            await { controller.session.value == null && player() == null && !serviceRunning() && notification() == null }
            assertNull(controller.pendingToken())
            controller.resumeVibrationAfterScreenOff(token)
            assertNull(controller.session.value)
            assertThrows(IllegalStateException::class.java) { originalPlayer.start() }

            // A different mail follows the existing replace-in-place policy, with one Service/player.
            controller.start(event.copy(mailId = "session-second"))
            await { controller.session.value?.receivedMailId == "session-second" && player() != null }
            val secondPlayer = player()!!
            controller.start(event.copy(mailId = "session-third"))
            await { controller.session.value?.receivedMailId == "session-third" && player() !== secondPlayer }
            assertThrows(IllegalStateException::class.java) { secondPlayer.start() }
            val stop = notification()!!.actions.first().actionIntent
            stop.send()
            await { controller.session.value == null && player() == null && !serviceRunning() && notification() == null }
            assertNull(controller.pendingToken())
        } finally {
            val current = PendingAlarmStore(context).load()?.event?.mailId
            if (current == null || current.startsWith("session-")) controller.stop("instrumentation cleanup")
            shell("input keyevent KEYCODE_WAKEUP")
        }
    }

    private fun player(): MediaPlayer? = synchronized(controller) {
        AlarmController::class.java.getDeclaredField("player").apply { isAccessible = true }.get(controller) as MediaPlayer?
    }
    private fun notification() = notifications.activeNotifications
        .firstOrNull { it.id == AlarmController.PLAYBACK_NOTIFICATION_ID }?.notification
    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == AlarmPlaybackService::class.java.name }
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
        }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Alarm session condition was not met", condition())
    }
}
