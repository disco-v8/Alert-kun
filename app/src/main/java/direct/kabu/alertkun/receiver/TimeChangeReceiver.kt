package direct.kabu.alertkun.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import direct.kabu.alertkun.monitoring.MonitoringScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Delegates system clock events without doing scheduler or DataStore work on the broadcast thread. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_TIME_CHANGED -> MonitoringScheduler.handleTimeChanged(context.applicationContext)
                    Intent.ACTION_TIMEZONE_CHANGED -> MonitoringScheduler.handleTimezoneChanged()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
