package direct.kabu.alertkun.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the explicit Stop action from an ongoing alarm notification. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            AlarmController.getInstance(context).stop("notification")
        } else if (intent.action == ACTION_DISMISSED) {
            AlarmController.getInstance(context).notificationDismissed()
        }
    }

    companion object {
        const val ACTION_STOP = "direct.kabu.alertkun.action.STOP_ALARM"
        const val ACTION_DISMISSED = "direct.kabu.alertkun.action.DISMISS_ALARM"
    }
}
