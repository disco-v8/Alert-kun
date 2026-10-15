package direct.kabu.alertkun.alarm

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import direct.kabu.alertkun.monitoring.MonitoringObservation

/** Owns finite and continuous alarm playback only. Never schedules or checks mail. */
class AlarmPlaybackService : Service() {
    private var token: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resumeVibration: Runnable? = null
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            val requested = token ?: return
            resumeVibration?.let(handler::removeCallbacks)
            // Power-button sleep cancels app vibrations in Android's vibrator service.
            // Reassert the same session after that broadcast, once, without touching sound/focus.
            val resume = Runnable {
                resumeVibration = null
                AlarmController.getInstance(this@AlarmPlaybackService).resumeVibrationAfterScreenOff(requested)
            }
            resumeVibration = resume
            handler.postDelayed(resume, 300L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = AlarmController.getInstance(this)
        if (intent == null && controller.pendingToken()?.let(controller::isContinuous) != true) {
            controller.discardFiniteRestart()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requested = intent?.getStringExtra(EXTRA_TOKEN) ?: controller.pendingToken()
        if (requested == null || !controller.activate(this, requested)) {
            // A stale Intent must not stop a newer alarm already running in this service.
            val currentPending = controller.pendingToken()
            val stop = token == null || currentPending == null || requested == currentPending
            if (stop) stopSelf(startId)
            return if (!stop && token?.let(controller::isContinuous) == true) START_STICKY else START_NOT_STICKY
        }
        token = requested
        return if (controller.isContinuous(requested)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        resumeVibration?.let(handler::removeCallbacks)
        resumeVibration = null
        unregisterReceiver(screenOffReceiver)
        token?.let { AlarmController.getInstance(this).serviceDestroyed(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        MonitoringObservation.record(this, "ALARM_FGS_STOPPED", "alarmToken" to token)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun replaceAlarm(requested: String) {
        val controller = AlarmController.getInstance(this)
        if (controller.pendingToken() != requested) return
        if (controller.activate(this, requested)) token = requested
        else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    internal fun finishPlayback(completedToken: String) {
        if (token != completedToken && AlarmController.getInstance(this).pendingToken() != completedToken) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object { const val EXTRA_TOKEN = "alarm_token" }
}
