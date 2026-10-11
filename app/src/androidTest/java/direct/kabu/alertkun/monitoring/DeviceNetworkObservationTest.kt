package direct.kabu.alertkun.monitoring

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Only reads the current state. Never changes network, display, monitoring or policy. */
class DeviceNetworkObservationTest {
    @Test fun snapshotsAndBlockedEventsContainOnlySelectedMetadata() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val observation = DeviceNetworkObservation(InstrumentationRegistry.getInstrumentation().targetContext) { event, fields ->
            events += event to fields
        }
        observation.snapshot("CHECK_BEGIN")
        observation.blocked(true)
        assertEquals("DEVICE_NETWORK_STATE", events[0].first)
        assertEquals("CHECK_BEGIN", events[0].second["observationPhase"])
        assertTrue(events[0].second["elapsedRealtimeMs"] is Long)
        assertTrue(events[0].second.containsKey("restrictBackgroundStatus"))
        assertEquals("NETWORK_BLOCKED_STATUS", events[1].first)
        assertEquals(true, events[1].second["networkBlocked"])
        assertFalse(events.any { (_, fields) -> fields.keys.any { it in setOf("ssid", "host", "username", "token", "body") } })
    }

    @Test fun observationFailureDoesNotEscapeOrPreventCallbackCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val observation = DeviceNetworkObservation(context) { _, _ -> throw IllegalStateException() }
        observation.snapshot("CHECK_BEGIN")
        observation.blocked(true)
        DefaultNetworkMonitor(context) { _, _ -> throw IllegalStateException() }.use { }
    }
}
