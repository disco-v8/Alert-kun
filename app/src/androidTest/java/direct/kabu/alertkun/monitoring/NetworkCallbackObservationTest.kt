package direct.kabu.alertkun.monitoring

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Invokes the existing callback on an isolated monitor; no mail/settings/work changes. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class NetworkCallbackObservationTest {
    @Test fun allCallbacksPersistSafeMetadataAndLostUsesPreviousCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        assumeNotNull(network)
        val caps = manager.getNetworkCapabilities(network!!)
        assumeNotNull(caps)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        DefaultNetworkMonitor(context) { event, fields ->
            lines += ObservationFormat.line(event, fields, 0, java.util.TimeZone.getTimeZone("UTC"))
        }.use { monitor ->
            val callback = DefaultNetworkMonitor::class.java.getDeclaredField("callback").apply { isAccessible = true }
                .get(monitor) as ConnectivityManager.NetworkCallback
            val lock = checkNotNull(DefaultNetworkMonitor::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(monitor))
            synchronized(lock) {
                lines.clear()
                callback.onAvailable(network)
                val state = monitor.state.value
                callback.onBlockedStatusChanged(network, true)
                assertEquals("Observation must not change readiness/retry state", state, monitor.state.value)
                val links = LinkProperties().apply {
                    domains = "private-domain.invalid"
                }
                callback.onLinkPropertiesChanged(network, links)
                callback.onCapabilitiesChanged(network, NetworkCapabilities())
                callback.onLost(network)
                val callbacks = lines.filter { it.contains(" NETWORK_CALLBACK ") }
                for (type in listOf("AVAILABLE", "LOST", "CAPABILITIES_CHANGED", "LINK_PROPERTIES_CHANGED", "BLOCKED_STATUS_CHANGED")) {
                    assertEquals(1, callbacks.count { it.contains("callbackType=$type ") })
                }
                val lost = callbacks.single { it.contains("callbackType=LOST ") }
                assertTrue(lost.contains("capabilitySource=LAST_KNOWN"))
                assertTrue(lost.contains("internet=false")); assertTrue(lost.contains("validated=false"))
                assertTrue(lost.contains("metered=true")); assertTrue(lost.contains("networkBlocked=true"))
                assertTrue(lost.contains("networkHandle=${network.networkHandle}"))
                assertFalse(lines.any { it.contains("10.23.45") || it.contains("private-domain") || it.contains("dnsServers=") })
            }
        }
    }

    @Test fun throwingSinkDoesNotAffectBlockedCallbackStateOrClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = context.getSystemService(ConnectivityManager::class.java).activeNetwork
        assumeNotNull(network)
        val monitor = DefaultNetworkMonitor(context) { _, _ -> throw IllegalStateException() }
        val callback = DefaultNetworkMonitor::class.java.getDeclaredField("callback").apply { isAccessible = true }
            .get(monitor) as ConnectivityManager.NetworkCallback
        try { callback.onBlockedStatusChanged(network!!, true) } finally { monitor.close(); monitor.close() }
    }
}
