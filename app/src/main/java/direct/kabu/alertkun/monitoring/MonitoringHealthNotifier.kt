package direct.kabu.alertkun.monitoring

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import direct.kabu.alertkun.MainActivity
import direct.kabu.alertkun.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MonitoringHealthNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        createChannel()
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 ||
            manager.getNotificationChannel(CHANNEL_ID).importance != NotificationManager.IMPORTANCE_NONE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                context.getString(R.string.monitoring_health_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
    }

    internal fun notification(completedAt: Long, alertCount: Int): Notification {
        require(alertCount >= 0)
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(completedAt))
        val message = context.getString(R.string.monitoring_health_message, alertCount, timestamp)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_normal)
            .setContentTitle(context.getString(R.string.monitoring_health_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setWhen(completedAt)
            .setAutoCancel(true)
            .build()
    }

    @android.annotation.SuppressLint("MissingPermission") // canPost checks the runtime permission; revocation is caught by reporter.
    fun post(completedAt: Long, alertCount: Int) {
        manager.notify(NOTIFICATION_ID, notification(completedAt, alertCount))
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    companion object {
        const val CHANNEL_ID = "alertkun_monitoring_health"
        const val NOTIFICATION_ID = 6_003
    }
}
