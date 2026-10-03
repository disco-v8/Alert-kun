package direct.kabu.alertkun.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the explicit Stop action from an ongoing alarm notification. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            AlarmController.getInstance(context).stop()
        }
    }

    companion object {
        const val ACTION_STOP = "direct.kabu.alertkun.action.STOP_ALARM"
    }
}
