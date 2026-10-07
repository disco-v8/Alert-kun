package direct.kabu.alertkun.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

internal object DeviceNetworkChecker {
    fun isAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION") manager.activeNetworkInfo?.isConnected == true
        }
    }
}
