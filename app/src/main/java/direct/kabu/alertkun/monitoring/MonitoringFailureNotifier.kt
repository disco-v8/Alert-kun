package direct.kabu.alertkun.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import direct.kabu.alertkun.R

internal class MonitoringFailureNotifier(private val context: Context) {
    fun post(kind: MonitoringFailureKind) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.monitoring_failure_channel_name), NotificationManager.IMPORTANCE_HIGH))
        }
        try {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(context.getString(R.string.monitoring_failure_title))
                .setContentText(context.getString(if (kind == MonitoringFailureKind.DEVICE_OFFLINE) R.string.monitoring_device_offline_message else R.string.monitoring_server_failure_message)).setAutoCancel(true).build())
        } catch (_: SecurityException) {
            // Notification permission denial must not interrupt durable failure accounting.
        }
    }
    private companion object { const val CHANNEL_ID = "alertkun_monitoring_failure"; const val NOTIFICATION_ID = 6_002 }
}
