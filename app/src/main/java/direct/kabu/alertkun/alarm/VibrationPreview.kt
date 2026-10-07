package direct.kabu.alertkun.alarm

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import direct.kabu.alertkun.model.VibrationPattern

/** Bounded settings feedback. Does not create events, persist settings or change alarm state. */
class VibrationPreview(context: Context) {
    private val token = Any()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    fun select(pattern: VibrationPattern) {
        cancel()
        val waveform = AlarmVibration.timings(pattern) ?: return
        val output = vibrator?.takeIf { it.hasVibrator() } ?: return
        AndroidVibrationOutput.play(output, waveform, -1, token)
    }

    fun cancel() {
        vibrator?.let { AndroidVibrationOutput.cancelPreview(it, token) }
    }
}
