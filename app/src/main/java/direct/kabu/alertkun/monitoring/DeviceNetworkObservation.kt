package direct.kabu.alertkun.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Read-only metadata; never used by execution policy. */
internal class DeviceNetworkObservation(
    private val context: Context,
    private val sink: ObservationSink,
) {
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
