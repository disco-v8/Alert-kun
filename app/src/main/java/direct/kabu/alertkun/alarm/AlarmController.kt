package direct.kabu.alertkun.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.AlarmMode

data class AlarmStartResult(
    val notificationPosted: Boolean,
    val state: AlarmState,
)

/** Delivers already-evaluated alarms without depending on mail, rules, or persistence. */
class AlarmController private constructor(context: Context) {
    private val context = context.applicationContext
    private val lifecycle = AlarmLifecycle()
    private var player: MediaPlayer? = null

    @Synchronized
    fun start(event: AlarmEvent): AlarmStartResult {
        stopOutputsLocked()
        val state = lifecycle.start(event.alarmMode)
        val notificationPosted = postNotification(event)
        startSound(event, event.alarmMode == AlarmMode.UNTIL_STOPPED)
        startVibration(event, event.alarmMode == AlarmMode.UNTIL_STOPPED)
        return AlarmStartResult(notificationPosted, state)
    }

    /** Safe to call repeatedly, including from the notification action receiver. */
    @Synchronized
    fun stop(): Boolean {
        val wasActive = lifecycle.stop()
        stopOutputsLocked()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        return wasActive
    }

    @Synchronized
    fun currentState(): AlarmState = lifecycle.state

    private fun postNotification(event: AlarmEvent): Boolean {
        createChannel()
        if (!canPostNotifications()) return false
        val subject = event.mailSubject?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.alarm_no_subject)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.alarm_notification_title, event.ruleName))
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subject))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(event.alarmMode == AlarmMode.ONCE)
            .setOngoing(event.alarmMode == AlarmMode.UNTIL_STOPPED)
            .apply {
                if (event.alarmMode == AlarmMode.UNTIL_STOPPED) {
                    addAction(0, context.getString(R.string.stop), stopPendingIntent())
                }
            }
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun startSound(event: AlarmEvent, looping: Boolean) {
        val requestedUri = event.soundUri?.let(Uri::parse)
        val mediaPlayer = createPlayer(requestedUri) ?: createPlayer(defaultSoundUri()) ?: return
        player = mediaPlayer
        try {
            mediaPlayer.isLooping = looping
            mediaPlayer.setOnCompletionListener { completed ->
                synchronized(this) {
                    if (player === completed) {
                        releasePlayerLocked()
                    }
                }
            }
            mediaPlayer.start()
        } catch (_: IllegalStateException) {
            releasePlayerLocked()
        }
    }

    private fun createPlayer(uri: Uri?): MediaPlayer? = uri?.let {
        try {
            MediaPlayer.create(context, it)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun defaultSoundUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun startVibration(event: AlarmEvent, repeating: Boolean) {
        val timings = AlarmVibration.timings(event.vibrationPattern) ?: return
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val repeatIndex = if (repeating) 0 else -1
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, repeatIndex))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, repeatIndex)
            }
        } catch (_: SecurityException) {
            // Vibration is optional when device policy denies it.
        }
    }

    private fun stopOutputsLocked() {
        releasePlayerLocked()
        vibrator()?.cancel()
    }

    private fun releasePlayerLocked() {
        val current = player ?: return
        player = null
        try {
            if (current.isPlaying) current.stop()
        } catch (_: IllegalStateException) {
        } finally {
            current.release()
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alarm_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        STOP_REQUEST_CODE,
        Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "alertkun_alarm"
        private const val NOTIFICATION_ID = 5_001
        private const val STOP_REQUEST_CODE = 5_001

        @Volatile private var instance: AlarmController? = null

        fun getInstance(context: Context): AlarmController = instance ?: synchronized(this) {
            instance ?: AlarmController(context).also { instance = it }
        }
    }
}
