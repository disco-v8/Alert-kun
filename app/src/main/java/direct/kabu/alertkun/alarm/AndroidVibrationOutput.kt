package direct.kabu.alertkun.alarm

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/** Shared API compatibility and ownership of this app's single physical vibration output. */
internal object AndroidVibrationOutput {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var owner: Any? = null

    @Synchronized
    fun play(vibrator: Vibrator, waveform: LongArray, repeatIndex: Int, token: Any): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(VibrationEffect.createWaveform(waveform, repeatIndex),
                    VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(waveform, repeatIndex), attributes)
            } else {
                // Existing minSdk 24 compatibility path, shared rather than duplicated.
                @Suppress("DEPRECATION")
                vibrator.vibrate(waveform, repeatIndex, attributes)
            }
            owner = token
            return true
        } catch (_: SecurityException) { return false }
    }

    @Synchronized
    fun cancelPreview(vibrator: Vibrator, token: Any) {
        // An alarm that replaced the preview must survive subsequent screen cleanup.
        if (owner !== token) return
        cancel(vibrator)
    }

    @Synchronized
    fun cancel(vibrator: Vibrator) {
        try { vibrator.cancel() } catch (_: SecurityException) { }
        owner = null
    }
}
