package direct.kabu.alertkun.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern

class AlarmTestViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val controller = AlarmController.getInstance(application)

    fun startOnce() = controller.start(testEvent(AlarmMode.ONCE, VibrationPattern.PATTERN_1))

    fun startUntilStopped() = controller.start(testEvent(AlarmMode.UNTIL_STOPPED, VibrationPattern.PATTERN_3))

    fun stop() = controller.stop()

    private fun testEvent(mode: AlarmMode, vibration: VibrationPattern) = AlarmEvent(
        mailId = "manual-test-mail",
        ruleId = "manual-test-rule",
        ruleName = appContext.getString(R.string.test_alarm_rule_name),
        mailSubject = appContext.getString(R.string.test_alarm_subject),
        soundUri = null,
        vibrationPattern = vibration,
        alarmMode = mode,
    )
}
