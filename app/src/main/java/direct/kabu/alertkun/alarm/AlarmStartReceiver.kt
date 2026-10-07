package direct.kabu.alertkun.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only the app's alarm PendingIntent can invoke this non-exported receiver. */
class AlarmStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(AlarmPlaybackService.EXTRA_TOKEN) ?: return
        AlarmController.getInstance(context).startFromExactAlarm(token)
    }
}
