package direct.kabu.alertkun.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Handler
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.AlertKunApplication
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Read-only state tests. No policy changes, mail checks or monitoring setting changes. */
class NetworkRestrictionObserverTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun applicationStartupPersistsCurrentProcessState() {
        assertTrue(context.applicationContext is AlertKunApplication)
        val directory = File(context.filesDir, "observability")
        val logs = (0..3).map { index ->
            File(directory, if (index == 0) "monitoring-observation.log" else "monitoring-observation.$index.log")
        }.filter { it.exists() }.flatMap { it.readLines() }
        assertTrue(logs.any { it.contains("DEVICE_NETWORK_STATE") &&
            it.contains("observationPhase=PROCESS_START") && it.contains("processId=${android.os.Process.myPid()}") })
    }

    @Test fun startupAndRelevantEventArePersistedWithoutIntentPayload() {
        val directory = File.createTempFile("restriction-observation-", ".tmp", context.cacheDir)
        check(directory.delete() && directory.mkdir())
        try {
            val writer = RotatingObservationLog(directory)
            NetworkRestrictionObserver(context) { event, fields -> writer.record(event, fields); Unit }.use { observer ->
                observer.start()
                observer.start()
                observer.receiver.onReceive(context, Intent("unrelated.action"))
                observer.receiver.onReceive(context, Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
                    .putExtra("token", "private-payload"))
            }
            val lines = File(directory, "monitoring-observation.log").readLines()
            assertEquals(1, lines.count { it.contains("NETWORK_RESTRICTION_OBSERVER_REGISTERED") })
            assertEquals(1, lines.count { it.contains("observationPhase=PROCESS_START") })
            assertEquals(1, lines.count { it.contains("observationPhase=RESTRICT_BACKGROUND_CHANGED") })
            val states = lines.filter { it.contains("DEVICE_NETWORK_STATE") }
            assertEquals(2, states.size)
            assertTrue(states.all { it.contains("restrictBackgroundStatus=") && it.contains("processId=") })
            assertFalse(lines.any { it.contains("private-payload") || it.contains("unrelated.action") || it.contains("token=") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun registrationFailureStillRecordsStartupState() {
        val failingContext = object : ContextWrapper(context) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
                broadcastPermission: String?, scheduler: Handler?): Intent? = throw SecurityException()

            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
                broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? = throw SecurityException()
        }
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        NetworkRestrictionObserver(failingContext) { event, fields -> events += event to fields }.use { it.start() }
        assertEquals("NETWORK_RESTRICTION_OBSERVER_REGISTRATION_FAILURE", events[0].first)
        assertEquals("PROCESS_START", events[1].second["observationPhase"])
    }

    @Test fun logFailureDoesNotEscapeStartupOrReceiver() {
        NetworkRestrictionObserver(context) { _, _ -> throw IllegalStateException() }.use { observer ->
            observer.start()
            observer.receiver.onReceive(context, Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
        }
    }
}
