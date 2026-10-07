package direct.kabu.alertkun.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import direct.kabu.alertkun.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MonitoringFailureNotifier(private val context: Context) {
    fun post(failureThreshold: Int, failedAtEpochMillis: Long) {
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(failedAtEpochMillis))
        val message = context.getString(R.string.monitoring_failure_message, failureThreshold, timestamp)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.monitoring_failure_channel_name), NotificationManager.IMPORTANCE_HIGH))
        }
        try {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(context.getString(R.string.monitoring_failure_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setWhen(failedAtEpochMillis).setAutoCancel(true).build())
        } catch (_: SecurityException) {
            // Notification permission denial must not interrupt durable failure accounting.
        }
    }
    private companion object { const val CHANNEL_ID = "alertkun_monitoring_failure"; const val NOTIFICATION_ID = 6_002 }
}
