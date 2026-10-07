package direct.kabu.alertkun.alarm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Exists only for a sounding UNTIL_STOPPED alarm. Never schedules or checks mail. */
class AlarmPlaybackService : Service() {
    private var token: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = AlarmController.getInstance(this)
        val requested = intent?.getStringExtra(EXTRA_TOKEN) ?: controller.pendingToken()
        if (requested == null || !controller.activate(this, requested)) {
            // A stale Intent must not stop a newer alarm already running in this service.
            val currentPending = controller.pendingToken()
            val stop = token == null || currentPending == null || requested == currentPending
            if (stop) stopSelf(startId)
            return if (stop) START_NOT_STICKY else START_STICKY
        }
        token = requested
        return START_STICKY
    }

    override fun onDestroy() {
        token?.let { AlarmController.getInstance(this).serviceDestroyed(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun replaceAlarm(requested: String) {
        if (AlarmController.getInstance(this).activate(this, requested)) token = requested
    }

    companion object { const val EXTRA_TOKEN = "alarm_token" }
}
