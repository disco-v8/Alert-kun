package direct.kabu.alertkun.monitoring

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/** Investigation only: identifiers/timing/counts, never connection secrets or mail content. */
internal fun monitoringDebugLog(context: Context, message: String) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        Log.i("MonitoringTrace", "wallMs=${System.currentTimeMillis()} $message")
    }
}
