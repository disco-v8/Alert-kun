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
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Receipt and latest completed-check display; never another alarm delivery. */
internal class MonitoringStatusNotifier(
    private val context: Context,
    private val readState: () -> Pair<Boolean, Long> = {
        runBlocking(Dispatchers.IO) { withTimeout(1_000) { MonitoringStatusRepository(context).notificationSnapshot() } }
    },
    private val hasPermission: () -> Boolean = {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    },
    private val observation: ObservationSink = { event, fields ->
        MonitoringObservation.record(context, event, *fields.toList().toTypedArray())
    },
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun onFcmReceived(receivedAt: Long, messageIdHash: String?) {
        post(receivedAt, messageIdHash, null, "NORMAL", null, "FCM", null)
    }

    /** Display a completed check only. Does not call AlarmController or record monitoring success. */
    fun onCheckCompleted(completedAt: Long, selectedAlarm: AlarmEvent?,
        expectedGeneration: Long, trigger: MailCheckTrigger, workId: String) {
        post(completedAt, null, selectedAlarm, if (selectedAlarm == null) "NORMAL" else "RULE_MATCH",
            expectedGeneration, trigger.name, workId)
    }

    @android.annotation.SuppressLint("MissingPermission") // Check before notify; catch permission revocation too.
    private fun post(timestamp: Long, messageIdHash: String?, selectedAlarm: AlarmEvent?, resultState: String,
        expectedGeneration: Long?, trigger: String, workId: String?) {
        // Serialize only notification post/cancel. Never acquire executionMutex or change monitoring state.
        synchronized(notificationLock) {
            var generation: Long? = null
            fun observe(name: String, action: String, reason: String? = null,
                importance: Int? = null, silent: Boolean? = null, sound: Boolean? = null, vibration: Boolean? = null) {
                event(name, generation, action, messageIdHash, reason, importance, silent, sound, vibration,
                    trigger, resultState, workId, if (expectedGeneration == null) "RECEIPT" else "CHECK_RESULT")
            }
            try {
                val state = readState()
                generation = state.second
                if (!state.first) {
                    cancel(generation)
                    observe("MONITORING_NOTIFICATION_BLOCKED", "SKIP", "MONITORING_OFF")
                    return
                }
                if (expectedGeneration != null && generation != expectedGeneration) {
                    observe("MONITORING_NOTIFICATION_BLOCKED", "SKIP", "GENERATION_MISMATCH")
                    return
                }
                createChannel()
                val channel = channelState()
                val importance = channel?.importance
                val sound = channel?.soundPresent
                val vibration = channel?.vibrationEnabled
                val silent = MonitoringStatusNotificationPolicy.isSilent(importance, sound, vibration)
                observe("MONITORING_NOTIFICATION_CHANNEL_STATE", "READ",
                    importance = importance, silent = silent, sound = if (channel == null) null else sound, vibration = vibration)
                val reason = when {
                    !hasPermission() -> "PERMISSION_DENIED"
                    !manager.areNotificationsEnabled() -> "APP_NOTIFICATIONS_DISABLED"
                    Build.VERSION.SDK_INT >= 26 && channel == null -> "CHANNEL_UNAVAILABLE"
                    importance == NotificationManager.IMPORTANCE_NONE -> "CHANNEL_BLOCKED"
                    else -> null
                }
                if (reason != null) {
                    observe("MONITORING_NOTIFICATION_BLOCKED", "SKIP", reason,
                        importance, silent, if (channel == null) null else sound, vibration)
                    return
                }
                val update = manager.activeNotifications.any { it.id == NOTIFICATION_ID && it.tag == null }
                manager.notify(NOTIFICATION_ID, notification(timestamp, silent, selectedAlarm))
                observe(if (update) "MONITORING_NOTIFICATION_UPDATE" else "MONITORING_NOTIFICATION_POST",
                    if (update) "UPDATE" else "POST", importance = importance, silent = silent,
                    sound = if (channel == null) null else sound, vibration = vibration)
            } catch (error: Exception) {
                observe("MONITORING_NOTIFICATION_BLOCKED", "SKIP",
                    if (error is SecurityException) "PERMISSION_DENIED" else "NOTIFICATION_ERROR")
            }
        }
    }

    internal fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.monitoring_status_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            enableVibration(false)
            setShowBadge(false)
        })
    }

    internal fun notification(receivedAt: Long, silent: Boolean, selectedAlarm: AlarmEvent? = null): Notification {
        val locale = context.resources.configuration.locales[0]
        val timestamp = MonitoringStatusNotificationPolicy.timestamp(receivedAt,
            context.getString(R.string.monitoring_status_date_pattern), locale)
        // Reuse alarm resources, including its blank-subject/sender fallback and receipt date format.
        // These values are notification content only and must never enter observability fields.
        val subject = selectedAlarm?.mailSubject?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.alarm_no_subject)
        val title = selectedAlarm?.let { context.getString(R.string.alarm_notification_title, it.ruleName) }
            ?: context.getString(R.string.monitoring_status_title)
        val message = buildString {
            if (selectedAlarm == null) {
                append(timestamp).append('\n').append(context.getString(R.string.monitoring_status_no_issues))
            } else {
                val from = selectedAlarm.mailFrom?.takeIf(String::isNotBlank) ?: context.getString(R.string.alarm_unknown_sender)
                val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    .format(Date(selectedAlarm.receivedAtEpochMillis ?: receivedAt))
                val details = context.getString(R.string.alarm_notification_details, subject, from, selectedAlarm.ruleName, date)
                // Copy the matching visible alarm text when available (including any delivery fallback note).
                // A pending asynchronous alarm can instead use the exact same existing resource content.
                val displayed = manager.activeNotifications.firstOrNull {
                    it.id in setOf(AlarmController.NOTIFICATION_ID, AlarmController.PLAYBACK_NOTIFICATION_ID) &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == subject &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.startsWith(details) == true
                }?.notification?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                append(displayed ?: details)
            }
            if (!silent) append('\n').append(context.getString(R.string.monitoring_status_silent_recommendation))
        }
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (selectedAlarm == null) message else subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(if (Build.VERSION.SDK_INT < 26) Notification.DEFAULT_SOUND else 0)
            .setOnlyAlertOnce(true)
            .setWhen(receivedAt)
            .setShowWhen(false)
            .setAutoCancel(false)
            .apply { if (selectedAlarm != null) setVisibility(NotificationCompat.VISIBILITY_PRIVATE) }
            .build()
    }

    fun cancel(generation: Long?) {
        synchronized(notificationLock) {
            try {
                manager.cancel(NOTIFICATION_ID)
                val channel = runCatching { channelState() }.getOrNull()
                event("MONITORING_NOTIFICATION_CANCEL", generation, "CANCEL", null, "MONITORING_OFF",
                    importance = channel?.importance,
                    silent = channel?.let { MonitoringStatusNotificationPolicy.isSilent(it.importance, it.soundPresent, it.vibrationEnabled) },
                    sound = channel?.soundPresent, vibration = channel?.vibrationEnabled, trigger = "MONITORING_OFF")
            } catch (_: Exception) {
                event("MONITORING_NOTIFICATION_CANCEL_FAILED", generation, "CANCEL", null, "NOTIFICATION_ERROR", trigger = "MONITORING_OFF")
            }
        }
    }

    private data class ChannelState(val importance: Int, val soundPresent: Boolean, val vibrationEnabled: Boolean)

    private fun channelState(): ChannelState? = if (Build.VERSION.SDK_INT >= 26) {
        manager.getNotificationChannel(CHANNEL_ID)?.let { ChannelState(it.importance, it.sound != null, it.shouldVibrate()) }
    } else null

    private fun event(name: String, generation: Long?, action: String, messageIdHash: String?, reason: String? = null,
        importance: Int? = null, silent: Boolean? = null, sound: Boolean? = null, vibration: Boolean? = null,
        trigger: String = "FCM", resultState: String? = null, workId: String? = null, phase: String? = null) {
        runCatching { observation(name, mapOf("notificationId" to NOTIFICATION_ID, "channelId" to CHANNEL_ID,
            "importance" to importance, "silent" to silent, "soundPresent" to sound, "vibrationEnabled" to vibration,
            "action" to action, "trigger" to trigger, "generation" to generation, "messageIdHash" to messageIdHash,
            "reason" to reason, "resultState" to resultState, "workId" to workId, "notificationPhase" to phase)) }
    }

    companion object {
        const val CHANNEL_ID = "alertkun_monitoring_status"
        const val NOTIFICATION_ID = 6_004
        private val notificationLock = Any()
    }
}
