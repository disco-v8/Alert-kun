package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.VibrationPattern

object AlarmVibration {
    fun timings(pattern: VibrationPattern): LongArray? = when (pattern) {
        VibrationPattern.NONE -> null
        VibrationPattern.PATTERN_1 -> longArrayOf(0, 300)
        VibrationPattern.PATTERN_2 -> longArrayOf(0, 200, 150, 200)
        VibrationPattern.PATTERN_3 -> longArrayOf(0, 350, 150, 350, 150, 350)
    }
}
