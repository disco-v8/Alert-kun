package direct.kabu.alertkun.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import direct.kabu.alertkun.MainActivity
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.monitoring.MonitoringObservation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlarmStartResult(val notificationPosted: Boolean, val state: AlarmState,
    val delivery: AlarmDelivery = AlarmDelivery.FOREGROUND_SERVICE)

/** Latest alarm replaces the previous alarm. No protocol or rule evaluation here. */
class AlarmController private constructor(context: Context) {
    private val context = context.applicationContext
    private val lifecycle = AlarmLifecycle()
    private val pending = PendingAlarmStore(this.context)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var onceCompletion: Runnable? = null
    private var finiteSequence: FiniteAlarmSequence? = null
    private var nextFiniteCycle: Runnable? = null
    private var activeToken: String? = null
    private var playbackService: WeakReference<AlarmPlaybackService>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasLegacyFocus = false
    private var finiteSoundAllowed = false
    private val vibrationToken = Any()
    private val mutableSession = MutableStateFlow<AlarmSession?>(null)
    val session = mutableSession.asStateFlow()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            debugLog("audio focus change=$change active=${activeToken != null}")
            try {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    player?.let { if (it.isPlaying) it.pause() }
                } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    player?.let { if (!it.isPlaying) { it.start(); debugLog("sound resume after audio focus gain") } }
                }
            } catch (_: IllegalStateException) { stop("audio focus playback state error") }
        }
    }

    @Synchronized
    fun start(event: AlarmEvent): AlarmStartResult {
        debugLog("AlarmEvent received mailId=${event.mailId} mode=${event.alarmMode}")
        if (AlarmSessionPolicy.isSameAlarm(pending.load()?.event, event)) {
            debugLog("already active -> start ignored mailId=${event.mailId}")
            return AlarmStartResult(canPostNotifications(), lifecycle.state,
                if (activeToken != null) AlarmDelivery.ACTIVE_SERVICE else AlarmDelivery.EXACT_ALARM)
        }
        // Persist handoff before accepting it; errors leave the mail RECEIVED for recovery.
        val runningService = playbackService?.get()?.takeIf { activeToken != null }
        val runningContinuous = activeToken?.let(::isContinuous) == true
        val token = UUID.randomUUID().toString()
        pending.save(PendingAlarm(token, event))
        observe("ALARM_PLAYBACK_REQUESTED", token, event)
        cancelExactAlarm()
        activeToken = null
        lifecycle.stop()
        mutableSession.value = null
        stopOutputsLocked()
        var delivery = AlarmDeliveryPolicy.delivery(event.alarmMode, canScheduleExactAlarms(), canPostNotifications(),
            event.soundType, Build.VERSION.SDK_INT)
        if (delivery != AlarmDelivery.FALLBACK_NOTIFICATION && runningService != null) {
            if (runningContinuous == (event.alarmMode == AlarmMode.UNTIL_STOPPED)) {
                // Already foreground: replace in-place, avoiding repeated exact-alarm throttling.
                handler.post { runningService.replaceAlarm(token) }
                delivery = AlarmDelivery.ACTIVE_SERVICE
            } else {
                // Re-enter onStartCommand to update START_STICKY / START_NOT_STICKY at the OS.
                delivery = AlarmDelivery.FOREGROUND_SERVICE
            }
        }
        if (delivery == AlarmDelivery.EXACT_ALARM) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 100L, exactPendingIntent(token))
                observe("ALARM_EXACT_SCHEDULED", token, event)
            } catch (_: SecurityException) { delivery = AlarmDelivery.FALLBACK_NOTIFICATION }
        }
        val posted = postNotification(event, token, delivery == AlarmDelivery.FALLBACK_NOTIFICATION,
            event.alarmMode == AlarmMode.UNTIL_STOPPED)
        if (delivery == AlarmDelivery.FOREGROUND_SERVICE && !startPlaybackService(PendingAlarm(token, event))) {
            delivery = AlarmDelivery.FALLBACK_NOTIFICATION
        } else if (delivery == AlarmDelivery.FALLBACK_NOTIFICATION) {
            context.stopService(Intent(context, AlarmPlaybackService::class.java))
            fallback(PendingAlarm(token, event))
        }
        return AlarmStartResult(posted, lifecycle.state, delivery)
    }

    /** Only called from the exact-alarm broadcast, never directly from a Worker. */
    @Synchronized
    internal fun startFromExactAlarm(token: String) {
        val alarm = pending.load()?.takeIf { it.token == token } ?: return
        if (activeToken == token || !AlarmDeliveryPolicy.acceptsExactEntry(
                alarm.event.alarmMode, alarm.event.soundType, Build.VERSION.SDK_INT)) return
        observe("ALARM_EXACT_DELIVERED", token, alarm.event)
        debugLog("exact alarm service start mode=${alarm.event.alarmMode}")
        if (!canPostNotifications()) { fallback(alarm); return }
        startPlaybackService(alarm)
    }

    private fun startPlaybackService(alarm: PendingAlarm): Boolean = try {
        ContextCompat.startForegroundService(context, playbackIntent(alarm.token))
        observe("ALARM_FGS_START_REQUESTED", alarm.token, alarm.event)
        true
    } catch (error: RuntimeException) {
        observe("ALARM_FGS_START_FAILURE", alarm.token, alarm.event, "exceptionClass" to error.javaClass.simpleName)
        fallback(alarm)
        false
    }

    @Synchronized internal fun pendingToken(): String? = pending.load()?.token
    @Synchronized internal fun isContinuous(token: String): Boolean =
        pending.load()?.takeIf { it.token == token }?.event?.alarmMode == AlarmMode.UNTIL_STOPPED

    @Synchronized internal fun discardFiniteRestart() {
        if (pending.load()?.event?.alarmMode != AlarmMode.UNTIL_STOPPED) pending.clear()
    }

    /** Foreground status precedes audio focus acquisition on Android 15+. */
    @Synchronized
    internal fun activate(service: AlarmPlaybackService, token: String): Boolean {
        val alarm = pending.load()?.takeIf { it.token == token } ?: return false
        if (activeToken == token) return true
        if (!canPostNotifications()) { fallback(alarm); return false }
        try {
            ServiceCompat.startForeground(service, PLAYBACK_NOTIFICATION_ID,
                notification(alarm.event, token, false, true),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (error: RuntimeException) {
            observe("ALARM_FGS_START_FAILURE", token, alarm.event, "exceptionClass" to error.javaClass.simpleName)
            fallback(alarm)
            return false
        }
        stopOutputsLocked()
        val continuous = alarm.event.alarmMode == AlarmMode.UNTIL_STOPPED
        if (continuous) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        activeToken = token
        playbackService = WeakReference(service)
        lifecycle.start(alarm.event.alarmMode)
        mutableSession.value = if (continuous) AlarmSession(alarm.event.mailId, alarm.event.ruleId, System.currentTimeMillis()) else null
        observe("ALARM_FGS_STARTED", token, alarm.event)
        debugLog("alarm session start mailId=${alarm.event.mailId}")
        val attributes = AlarmSoundMapping.audioAttributes(alarm.event.soundType)
        val focused = try { requestFocus(alarm.event.soundType, attributes) } catch (_: RuntimeException) { false }
        observe("ALARM_AUDIO_FOCUS", token, alarm.event, "focusGranted" to focused, "usage" to attributes.usage)
        if (!continuous) {
            playFinite(alarm.event, alarm.event.alarmMode.finiteCount!!, focused)
            return true
        }
        if (focused && !startSound(alarm.event, true, attributes)) {
            stop("playback start failure")
            return false
        }
        startVibration(alarm.event, true)
        return true
    }

    @Synchronized
    internal fun serviceDestroyed(token: String) {
        debugLog("service stop")
        if (activeToken != token) return
        observe("ALARM_FGS_STOPPED", token, pending.load()?.event)
        if (!isContinuous(token)) runCatching { pending.clear() }
        playbackService = null
        activeToken = null
        lifecycle.stop()
        mutableSession.value = null
        stopOutputsLocked()
        debugLog("service stop; alarm session inactive")
        // Retain handoff for a system START_STICKY restart. Explicit Stop clears it.
    }

    @Synchronized
    fun stop(source: String = "controller"): Boolean {
        debugLog("stop requested from $source")
        observe("ALARM_STOP_REQUESTED", activeToken, pending.load()?.event)
        val wasActive = lifecycle.stop()
        activeToken = null
        mutableSession.value = null
        cancelExactAlarm()
        stopOutputsLocked()
        context.stopService(Intent(context, AlarmPlaybackService::class.java))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        NotificationManagerCompat.from(context).cancel(PLAYBACK_NOTIFICATION_ID)
        pending.clear()
        debugLog("alarm session inactive")
        return wasActive
    }

    /** Restore only the Stop surface. Dismissal never stops or restarts outputs. */
    @Synchronized
    internal fun notificationDismissed() {
        debugLog("notification dismissed active=${mutableSession.value != null}")
        val alarm = pending.load()?.takeIf { it.token == activeToken && mutableSession.value != null } ?: return
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(context).notify(PLAYBACK_NOTIFICATION_ID,
                notification(alarm.event, alarm.token, false, true))
            debugLog("active alarm notification restored")
        } catch (_: SecurityException) { /* Main screen still offers the shared Stop action. */ }
    }

    @Synchronized
    internal fun resumeVibrationAfterScreenOff(token: String) {
        val alarm = pending.load()?.takeIf { it.token == token && activeToken == token && mutableSession.value != null }
            ?: return
        debugLog("active session vibration resume after screen off")
        startVibration(alarm.event, true)
    }

    @Synchronized fun currentState(): AlarmState = lifecycle.state
    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    fun canPostNotifications(): Boolean {
        createChannel()
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled() && channelEnabled
    }

    /** Explain revoked permission on reopen, without re-sounding a previously processed mail. */
    @Synchronized
    fun refreshPendingPermissionWarning() {
        pending.load()?.let { if (it.event.alarmMode == AlarmMode.UNTIL_STOPPED && !canScheduleExactAlarms())
            postNotification(it.event, it.token, true, true) }
    }

    private fun fallback(alarm: PendingAlarm) {
        // No unowned background MediaPlayer on any API. Notification action can retry the FGS.
        stopOutputsLocked()
        postNotification(alarm.event, alarm.token, true, alarm.event.alarmMode == AlarmMode.UNTIL_STOPPED)
        observe("ALARM_FALLBACK", alarm.token, alarm.event, "reason" to "NOTIFICATION_AND_VIBRATION_ONLY")
        playFinite(alarm.event, alarm.event.alarmMode.finiteCount ?: 1, false)
    }
    private fun playFinite(event: AlarmEvent, count: Int, soundAllowed: Boolean) {
        finiteSoundAllowed = soundAllowed
        val sequence = FiniteAlarmSequence(count)
        finiteSequence = sequence
        startFiniteCycle(event, sequence)
    }

    private fun startFiniteCycle(event: AlarmEvent, sequence: FiniteAlarmSequence) {
        if (finiteSequence !== sequence) return
        var startedAt = SystemClock.elapsedRealtime()
        val vibrationDuration = AlarmVibration.timings(event.vibrationPattern)?.sum() ?: 0L
        debugLog("finite cycle start remaining=${sequence.remainingCount}")
        val soundFinished = {
            if (finiteSequence === sequence) {
                // Start sound and vibration together, and wait for both before the next cycle.
                val next = Runnable { synchronized(this) {
                    if (finiteSequence === sequence) {
                        nextFiniteCycle = null
                        observe("ALARM_CYCLE_COMPLETED", activeToken ?: pendingToken(), event,
                            "completedCount" to ((event.alarmMode.finiteCount ?: 1) - sequence.remainingCount + 1))
                        if (sequence.completeCycle()) startFiniteCycle(event, sequence)
                        else {
                            finiteSequence = null
                            vibrator()?.let { AndroidVibrationOutput.cancelPreview(it, vibrationToken) }
                            debugLog("finite alarm completed")
                            finishFinitePlayback()
                        }
                    }
                } }
                nextFiniteCycle = next
                handler.postDelayed(next, (vibrationDuration - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L))
            }
        }
        val outputsStarted = {
            startedAt = SystemClock.elapsedRealtime()
            startVibration(event, false)
        }
        if (!finiteSoundAllowed) {
            outputsStarted()
            soundFinished()
        } else if (!startSound(event, false, onFinished = soundFinished, onStarted = outputsStarted,
                onFailed = { finishFinitePlayback() })) {
            observe("ALARM_PLAYBACK_ERROR", activeToken, event, "reason" to "PLAYER_START_FAILED")
            finishFinitePlayback()
        }
    }

    private fun finishFinitePlayback() {
        val token = activeToken
        val service = playbackService?.get()
        stopOutputsLocked()
        if (token == null) return // FGS start failed: retain the notification action/handoff.
        observe("ALARM_PLAYBACK_FINISHED", token, pending.load()?.event)
        try { service?.finishPlayback(token) } finally {
            runCatching { pending.clear() }
            playbackService = null
            activeToken = null
        }
    }
    private fun postNotification(event: AlarmEvent, token: String, fallback: Boolean, ongoing: Boolean): Boolean {
        if (!canPostNotifications()) return false
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(event, token, fallback, ongoing))
            true
        } catch (_: SecurityException) { false }
    }
    private fun notification(event: AlarmEvent, token: String, fallback: Boolean, ongoing: Boolean): Notification {
        createChannel()
        val subject = event.mailSubject?.takeIf(String::isNotBlank) ?: context.getString(R.string.alarm_no_subject)
        val from = event.mailFrom?.takeIf(String::isNotBlank) ?: context.getString(R.string.alarm_unknown_sender)
        val timestamp = event.receivedAtEpochMillis ?: System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        val details = context.getString(R.string.alarm_notification_details, subject, from, event.ruleName, date) +
            if (fallback) "\n" + context.getString(R.string.alarm_start_unavailable) else ""
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rule_match)
            .setContentTitle(context.getString(R.string.alarm_notification_title, event.ruleName))
            .setContentText(subject).setStyle(NotificationCompat.BigTextStyle().bigText(details))
            // This is a newly generated alert, even when recovering an older RECEIVED mail.
            // Receipt time stays in the details; a stale Notification.when can be discarded by Android.
            .setWhen(System.currentTimeMillis()).setShowWhen(true).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setAutoCancel(!ongoing).setOngoing(ongoing)
            .apply {
                if (ongoing) setDeleteIntent(PendingIntent.getBroadcast(context, 5006,
                    Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_DISMISSED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            .setContentIntent(PendingIntent.getActivity(context, 5002,
                Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_MAIL)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (ongoing) addAction(0, context.getString(R.string.stop), PendingIntent.getBroadcast(context, 5001,
                    Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                if (fallback) {
                    val start = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        PendingIntent.getForegroundService(context, 5003, playbackIntent(token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    else PendingIntent.getService(context, 5003, playbackIntent(token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, context.getString(R.string.alarm_start_action), start)
                }
            }.build()
    }
    private fun playbackIntent(token: String) = Intent(context, AlarmPlaybackService::class.java)
        .putExtra(AlarmPlaybackService.EXTRA_TOKEN, token)
    private fun exactPendingIntent(token: String = "") = PendingIntent.getBroadcast(context, 5004,
        Intent(context, AlarmStartReceiver::class.java).putExtra(AlarmPlaybackService.EXTRA_TOKEN, token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelExactAlarm() { alarms.cancel(exactPendingIntent()) }

    private fun startSound(
        event: AlarmEvent,
        looping: Boolean,
        attributes: AudioAttributes = AlarmSoundMapping.audioAttributes(event.soundType),
        onFinished: (() -> Unit)? = null,
        onStarted: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ): Boolean {
        val requested = AlarmSoundMapping.resolveUri(event.soundType, event.soundUri)
        val current = createPlayer(requested, attributes) ?: createPlayer(AlarmSoundMapping.defaultUri(event.soundType), attributes) ?: return false
        player = current
        try {
            // MediaPlayer owns and releases this CPU wake lock with playback; no screen wake lock.
            if (looping || activeToken != null) current.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            current.isLooping = looping
            current.setOnCompletionListener { completed -> synchronized(this) {
                if (player === completed) {
                    debugLog("playback completed")
                    if (looping) stop("unexpected playback completion")
                    else { releasePlayerLocked(); onFinished?.invoke() }
                }
            } }
            current.setOnErrorListener { failed, what, extra -> synchronized(this) {
                debugLog("sound error what=$what extra=$extra")
                observe("ALARM_PLAYBACK_ERROR", activeToken, event, "category" to what, "reason" to extra)
                if (player === failed) {
                    if (looping) stop("playback error")
                    else { releasePlayerLocked(); (onFailed ?: onFinished)?.invoke() }
                }
            }; true }
            current.start()
            observe("ALARM_PLAYBACK_STARTED", activeToken, event, "usage" to attributes.usage)
            onStarted?.invoke()
            debugLog("sound start type=${event.soundType} usage=${attributes.usage} loop=${current.isLooping} durationMs=${current.duration}")
            if (!looping) {
                // NuPlayer can auto-loop ringtone metadata independently of isLooping=false,
                // suppressing OnCompletionListener for USAGE_ALARM. Bound every finite cycle to one
                // media duration as well as releasing on normal completion/error.
                val completion = Runnable { synchronized(this) {
                    if (player === current) {
                        debugLog("playback completed (ONCE duration boundary)")
                        releasePlayerLocked()
                        onFinished?.invoke()
                    }
                } }
                onceCompletion = completion
                handler.postDelayed(completion, current.duration.toLong().coerceAtLeast(0L))
            }
            return true
        } catch (error: RuntimeException) {
            observe("ALARM_PLAYBACK_ERROR", activeToken, event, "exceptionClass" to error.javaClass.simpleName)
            releasePlayerLocked()
            return false
        }
    }
    private fun createPlayer(uri: Uri?, attributes: AudioAttributes): MediaPlayer? = uri?.let {
        try { MediaPlayer.create(context, it, null, attributes, 0) } catch (_: RuntimeException) { null }
    }
    private fun requestFocus(type: AlarmSoundType, attributes: AudioAttributes): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler).build()
        focusRequest = request
        val result = audio.requestAudioFocus(request)
        debugLog("audio focus request result=$result usage=${attributes.usage}")
        result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } else {
        @Suppress("DEPRECATION")
        val granted = audio.requestAudioFocus(focusListener, AlarmSoundMapping.legacyStreamType(type), AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasLegacyFocus = granted
        granted
    }
    private fun startVibration(event: AlarmEvent, repeating: Boolean) {
        val waveform = (if (repeating) AlarmVibration.repeatingTimings(event.vibrationPattern)
            else AlarmVibration.timings(event.vibrationPattern)) ?: return
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return
        debugLog("vibration start repeat=${if (repeating) 0 else -1}")
        AndroidVibrationOutput.play(vibrator, waveform, if (repeating) 0 else -1, vibrationToken)
    }
    private fun stopOutputsLocked() {
        finiteSequence?.cancel()
        finiteSoundAllowed = false
        finiteSequence = null
        nextFiniteCycle?.let(handler::removeCallbacks)
        nextFiniteCycle = null
        releasePlayerLocked()
        vibrator()?.let(AndroidVibrationOutput::cancel)
        debugLog("vibration stop")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else if (hasLegacyFocus) {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(focusListener)
        }
        focusRequest = null
        hasLegacyFocus = false
        observe("ALARM_OUTPUTS_RELEASED", activeToken, pending.load()?.event)
    }
    private fun releasePlayerLocked() {
        onceCompletion?.let(handler::removeCallbacks)
        onceCompletion = null
        val current = player ?: return
        player = null
        try { if (current.isPlaying) current.stop() } catch (_: IllegalStateException) { }
        finally { current.release(); debugLog("sound stop; player release") }
    }
    private fun debugLog(message: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("AlertKunAlarm", message)
        }
    }
    private fun observe(name: String, token: String?, event: AlarmEvent?, vararg values: Pair<String, Any?>) {
        MonitoringObservation.record(context, name, "alarmToken" to token, "alarmMode" to event?.alarmMode?.name,
            "soundType" to event?.soundType?.name, "requestedCount" to event?.alarmMode?.finiteCount, *values)
    }
    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.alarm_channel_name), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alarm_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    companion object {
        const val CHANNEL_ID = "alertkun_alarm"
        const val NOTIFICATION_ID = 5001
        const val PLAYBACK_NOTIFICATION_ID = 5005
        const val ACTION_OPEN_MAIL = "direct.kabu.alertkun.action.OPEN_RECEIVED_MAIL"
        @Volatile private var instance: AlarmController? = null
        fun getInstance(context: Context): AlarmController = instance ?: synchronized(this) {
            instance ?: AlarmController(context).also { instance = it }
        }
    }
}
