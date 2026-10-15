package direct.kabu.alertkun.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Run on a fresh test installation without the Alarms & reminders special access. */
class AlarmFallbackInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = AlarmController.getInstance(context)

    @After fun stop() { controller.stop() }

    @Test fun missingExactPermissionStillNotifiesAndUserActionStartsPlayback() {
        assumeFalse(controller.canScheduleExactAlarms())
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
        }
        controller.stop()
        val result = controller.start(AlarmEvent("fallback", "rule", "Rule", "Subject", null,
            VibrationPattern.NONE, AlarmMode.UNTIL_STOPPED))
        assertEquals(AlarmDelivery.FALLBACK_NOTIFICATION, result.delivery)
        assertEquals(AlarmState.IDLE, controller.currentState())
        val manager = context.getSystemService(NotificationManager::class.java)
        await { manager.activeNotifications.any { it.id == AlarmController.NOTIFICATION_ID } }
        val notification = manager.activeNotifications.single { it.id == AlarmController.NOTIFICATION_ID }.notification
        assertEquals(2, notification.actions.size) // Stop + Start alarm
        // A visible activity supplies a real permitted foreground-start context; uses the same notification PendingIntent.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { notification.actions.last().actionIntent.send() }
            await { controller.currentState() == AlarmState.ACTIVE }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(condition())
    }
}
