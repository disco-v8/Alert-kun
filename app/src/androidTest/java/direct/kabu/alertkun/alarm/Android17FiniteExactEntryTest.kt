package direct.kabu.alertkun.alarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual AlarmManager delivery on API37; no manual receiver dispatch in the background test. */
@SdkSuppress(minSdkVersion = 37)
class Android17FiniteExactEntryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = AlarmController.getInstance(context)

    @Before fun prepare() {
        assertNull("Do not replace an unrelated alarm", controller.pendingToken())
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        assertTrue(shell("dumpsys audio").contains("mHardeningOverride=0"))
        assertTrue(controller.canScheduleExactAlarms())
        assertTrue(controller.canPostNotifications())
    }

    @After fun cleanup() {
        controller.stop()
        shell("am start -n ${context.packageName}/.MainActivity")
    }

    @Test fun backgroundFiniteAlarmUsesActualExactReceiverAndCompletesThreeCycles() {
        shell("input keyevent KEYCODE_HOME")
        val result = controller.start(event("background-exact", AlarmMode.THREE_TIMES))
        assertEquals(AlarmDelivery.EXACT_ALARM, result.delivery)
        val token = controller.pendingToken()!!
        await { events(token).any { it.contains(" ALARM_PLAYBACK_FINISHED ") } }
        val lines = events(token)
        for (event in listOf("ALARM_EXACT_SCHEDULED", "ALARM_EXACT_DELIVERED", "ALARM_FGS_STARTED")) {
            assertEquals(event, 1, lines.count { it.contains(" $event ") })
        }
        assertEquals(3, lines.count { it.contains(" ALARM_PLAYBACK_STARTED ") && it.contains("usage=4") })
        assertEquals(3, lines.count { it.contains(" ALARM_CYCLE_COMPLETED ") })
        assertEquals(0, lines.count { it.contains(" ALARM_PLAYBACK_ERROR ") })
        await { controller.pendingToken() == null }
        assertNull(controller.session.value)
    }

    @Test fun pendingReplacementAndStopRejectOldExactDeliveries() {
        // Keep real asynchronous delivery outside the pending replacement/cancellation window.
        val (old, current) = synchronized(controller) {
            val first = controller.start(event("old-pending", AlarmMode.FIVE_TIMES))
            assertEquals(AlarmDelivery.EXACT_ALARM, first.delivery)
            val old = controller.pendingToken()!!
            controller.start(event("new-pending", AlarmMode.THREE_TIMES))
            val current = controller.pendingToken()!!
            assertNotEquals(old, current)
            controller.startFromExactAlarm(old)
            assertEquals(current, controller.pendingToken())
            controller.stop()
            old to current
        }
        // Exercise the receiver itself after cancellation, rather than only its controller method.
        AlarmStartReceiver().onReceive(context, Intent(context, AlarmStartReceiver::class.java)
            .putExtra(AlarmPlaybackService.EXTRA_TOKEN, current))
        SystemClock.sleep(300)
        assertNull(controller.pendingToken())
        assertEquals(0, events(old).count { it.contains(" ALARM_EXACT_DELIVERED ") })
        assertEquals(0, events(current).count { it.contains(" ALARM_EXACT_DELIVERED ") })
    }

    private fun event(id: String, mode: AlarmMode) =
        AlarmEvent(id, "fixture-rule", "Fixture", null, null, VibrationPattern.PATTERN_1, mode)

    private fun events(token: String): List<String> = File(context.filesDir, "observability")
        .listFiles().orEmpty().filter { it.isFile }.flatMap { file ->
            file.useLines { lines -> lines.filter { it.contains("alarmToken=$token ") }.toList() }
        }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Exact-entry alarm did not complete", condition())
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
}
