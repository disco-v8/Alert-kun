package direct.kabu.alertkun.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AlarmPlaybackInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = AlarmController.getInstance(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Before fun prepare() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        controller.stop()
    }

    @After fun cleanup() {
        controller.stop()
        // Revocation kills the target process. Restore appops from the host after the suite,
        // not here while the instrumentation still needs to report its result.
    }

    @Test fun exactAlarmStartsForegroundPlaybackAndBroadcastStopCleansUp() {
        assertTrue(controller.canScheduleExactAlarms())
        val result = controller.start(event("continuous", AlarmMode.UNTIL_STOPPED))
        assertEquals(AlarmDelivery.EXACT_ALARM, result.delivery)
        await { controller.currentState() == AlarmState.ACTIVE }
        await { manager.activeNotifications.any { it.id == AlarmController.PLAYBACK_NOTIFICATION_ID } }
        val notification = manager.activeNotifications.single { it.id == AlarmController.PLAYBACK_NOTIFICATION_ID }.notification
        assertNotNull(notification.contentIntent)
        assertTrue(notification.actions.isNotEmpty())
        val oldToken = controller.pendingToken()!!
        val replacement = controller.start(event("replacement", AlarmMode.UNTIL_STOPPED))
        assertEquals(AlarmDelivery.ACTIVE_SERVICE, replacement.delivery)
        val newToken = controller.pendingToken()!!
        assertNotEquals(oldToken, newToken)
        await { controller.currentState() == AlarmState.ACTIVE }
        controller.startFromExactAlarm(oldToken)
        assertEquals(newToken, controller.pendingToken())
        context.sendBroadcast(Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP))
        await { controller.currentState() == AlarmState.IDLE && controller.pendingToken() == null }
        controller.stop() // repeated Stop is safe
        await { manager.activeNotifications.none { it.id == AlarmController.PLAYBACK_NOTIFICATION_ID } }
    }

    @Test fun oncePostsDetailsAndUsesFiniteServiceWithoutContinuousSession() {
        val result = controller.start(event("once", AlarmMode.ONCE))
        assertEquals(AlarmDelivery.FOREGROUND_SERVICE, result.delivery)
        assertEquals(AlarmState.IDLE, controller.currentState())
        assertNotNull(controller.pendingToken())
        await { manager.activeNotifications.any { it.id == AlarmController.NOTIFICATION_ID } }
        val notification = manager.activeNotifications.single { it.id == AlarmController.NOTIFICATION_ID }.notification
        val text = notification.extras.getCharSequence("android.bigText").toString()
        assertTrue(text.contains("sender@example.invalid"))
        assertTrue(text.contains("Test rule"))
        assertNotNull(notification.contentIntent)
        assertNull(controller.session.value)
    }

    @Test fun handoffSurvivesStoreRecreationAndStaleTokenCannotStart() {
        val store = PendingAlarmStore(context)
        val saved = PendingAlarm("current", event("persisted", AlarmMode.UNTIL_STOPPED))
        store.save(saved)
        assertEquals(saved, PendingAlarmStore(context).load())
        controller.startFromExactAlarm("stale")
        assertEquals(AlarmState.IDLE, controller.currentState())
        assertEquals("current", controller.pendingToken())
        controller.stop()
        assertNull(PendingAlarmStore(context).load())
    }

    @Test fun stoppingPendingAlarmPreventsLateReceiverResurrection() {
        controller.start(event("pending", AlarmMode.UNTIL_STOPPED))
        val token = controller.pendingToken()!!
        controller.stop()
        controller.startFromExactAlarm(token)
        assertEquals(AlarmState.IDLE, controller.currentState())
        assertNull(controller.pendingToken())
    }

    private fun event(id: String, mode: AlarmMode) = AlarmEvent(id, "rule", "Test rule", "Test subject", null,
        VibrationPattern.PATTERN_1, mode, "sender@example.invalid", 1_000L)

    private fun await(condition: () -> Boolean) {
        // Exact alarms can be throttled after other instrumentation alarm tests.
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Alarm lifecycle condition was not satisfied", condition())
    }
}
