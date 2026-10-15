package direct.kabu.alertkun.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Registered only for an actual check, inside executionMutex; callbacks only publish snapshots. */
internal class DefaultNetworkMonitor(
    context: Context,
    observation: ObservationSink = { _, _ -> },
) : DefaultNetworkSource, Closeable {
    private val deviceObservation = DeviceNetworkObservation(context, observation)
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val log: (String) -> Unit = { monitoringDebugLog(context, it) }
    private val lock = Any()
    private var closed = false
    private var registered = false
    private val mutableState = MutableStateFlow(initialState())
    override val state = mutableState.asStateFlow()

    private fun initialState(): DefaultNetworkState {
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        return DefaultNetworkState(network?.toString(), capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(lock) {
            if (closed) return@synchronized
            val previous = mutableState.value
            val changed = previous.networkId != network.toString()
            if (changed) mutableState.value = DefaultNetworkState(network.toString(), changeVersion = previous.changeVersion + 1)
            log("network available default network changed=$changed")
            deviceObservation.callback("AVAILABLE", network, mutableState.value.networkId == network.toString())
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
            if (closed) return@synchronized
            deviceObservation.callback("CAPABILITIES_CHANGED", network, mutableState.value.networkId == network.toString(), capabilities)
            if (mutableState.value.networkId != network.toString()) return@synchronized
            val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            mutableState.value = mutableState.value.copy(internet = internet, validated = validated)
            log("network capabilities INTERNET=$internet VALIDATED=$validated")
        }
        override fun onLost(network: Network) = synchronized(lock) {
            if (closed) return@synchronized
            deviceObservation.callback("LOST", network, mutableState.value.networkId == network.toString())
            log("network lost currentDefault=${mutableState.value.networkId == network.toString()}")
            if (mutableState.value.networkId == network.toString()) {
                mutableState.value = DefaultNetworkState(changeVersion = mutableState.value.changeVersion + 1)
                log("default network changed available=false")
            }
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = synchronized(lock) {
            if (closed) return@synchronized
            deviceObservation.callback("BLOCKED_STATUS_CHANGED", network, mutableState.value.networkId == network.toString(), blocked = blocked)
            if (mutableState.value.networkId != network.toString()) return@synchronized
            // This does not change readiness, retry decisions or monitoring results.
            deviceObservation.blocked(blocked)
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = synchronized(lock) {
            if (closed) return@synchronized
            // Deliberately do not serialize LinkProperties (addresses, DNS, routes, domains).
            deviceObservation.callback("LINK_PROPERTIES_CHANGED", network, mutableState.value.networkId == network.toString())
        }
    }

    init {
        deviceObservation.snapshot("CHECK_BEGIN")
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            log("default network callback registered INTERNET=${state.value.internet} VALIDATED=${state.value.validated}")
        } catch (error: RuntimeException) {
            // Callback failure must not suppress real connection errors or manufacture success.
            log("default network callback registration failed type=${error.javaClass.simpleName}")
        }
    }

    override fun close() {
        val unregister = synchronized(lock) {
            if (closed) return
            closed = true
            registered
        }
        deviceObservation.snapshot("CHECK_END")
        if (unregister) {
            try { manager.unregisterNetworkCallback(callback) }
            catch (error: RuntimeException) { log("default network callback unregister failed type=${error.javaClass.simpleName}") }
            log("default network callback unregistered")
        }
    }
}
