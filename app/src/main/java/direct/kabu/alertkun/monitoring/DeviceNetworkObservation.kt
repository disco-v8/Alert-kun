package direct.kabu.alertkun.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Read-only metadata; never used by execution policy. */
internal class DeviceNetworkObservation(
    private val context: Context,
    private val sink: ObservationSink,
) {
    // Only safe observation metadata. This cache is never read by DefaultNetworkState/retry.
    private val recentCapabilities = linkedMapOf<Network, Map<String, Any?>>()
    private val recentBlocked = mutableMapOf<Network, Boolean>()

    fun callback(type: String, network: Network, trackedDefault: Boolean,
        capabilities: NetworkCapabilities? = null, blocked: Boolean? = null) {
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val current = read { connectivity.activeNetwork }
            val fresh = if (type == "LOST") null else capabilities ?: read { connectivity.getNetworkCapabilities(network) }
            val metadata = fresh?.let { value ->
                mapOf<String, Any?>(
                    "transport" to listOfNotNull(
                        "WIFI".takeIf { value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                        "CELLULAR".takeIf { value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                        "VPN".takeIf { value.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                        "OTHER".takeIf { !value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                            !value.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                    ).joinToString(","),
                    "internet" to value.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    "validated" to value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "metered" to !value.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                )
            }
            val fields = metadata ?: recentCapabilities[network] ?: mapOf(
                "transport" to null, "internet" to null, "validated" to null, "metered" to null)
            if (metadata != null) {
                recentCapabilities.remove(network)
                recentCapabilities[network] = metadata
                if (recentCapabilities.size > 8) {
                    val oldest = recentCapabilities.keys.first()
                    recentCapabilities.remove(oldest)
                    recentBlocked.remove(oldest)
                }
            }
            if (blocked != null && recentCapabilities.containsKey(network)) recentBlocked[network] = blocked
            val values = fields + mapOf(
                "callbackType" to type, "networkHandle" to network.networkHandle,
                "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
                "isDefaultNetwork" to (current == network), "trackedDefault" to trackedDefault,
                "networkBlocked" to (blocked ?: recentBlocked[network]),
                "capabilitySource" to when {
                    metadata != null -> "CURRENT"
                    recentCapabilities.containsKey(network) -> "LAST_KNOWN"
                    else -> "UNKNOWN"
                },
            )
            if (type == "LOST") { recentCapabilities.remove(network); recentBlocked.remove(network) }
            sink("NETWORK_CALLBACK", values)
        }
    }

    fun snapshot(phase: String) {
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            val capabilities = read { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }
            sink("DEVICE_NETWORK_STATE", mapOf(
                "observationPhase" to phase,
                "processId" to android.os.Process.myPid(),
                "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
                "interactive" to read { power.isInteractive },
                "deviceIdle" to read { power.isDeviceIdleMode },
                "powerSave" to read { power.isPowerSaveMode },
                "batteryOptimizationExempt" to read { power.isIgnoringBatteryOptimizations(context.packageName) },
                "restrictBackgroundStatus" to read { connectivity.restrictBackgroundStatus },
                "metered" to read { connectivity.isActiveNetworkMetered },
                "wifi" to capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                "cellular" to capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                "vpn" to capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                "blockedCallbackSupported" to (Build.VERSION.SDK_INT >= 29),
            ))
        }
    }

    fun blocked(blocked: Boolean) {
        runCatching {
            sink("NETWORK_BLOCKED_STATUS", mapOf(
                "networkBlocked" to blocked,
                "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
            ))
        }
    }

    private fun <T> read(block: () -> T): T? = try { block() } catch (_: Exception) { null }
}
