package direct.kabu.alertkun.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.VibrationPattern

class AlarmTestViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val controller = AlarmController.getInstance(application)

    fun stop() = controller.stop()

    fun start(mode: AlarmMode, soundUri: String?, vibration: VibrationPattern, soundType: AlarmSoundType) =
        controller.start(testEvent(mode, vibration, soundUri, soundType))

    private fun testEvent(mode: AlarmMode, vibration: VibrationPattern, soundUri: String?, soundType: AlarmSoundType) = AlarmEvent(
        mailId = "manual-test-mail",
        ruleId = "manual-test-rule",
        ruleName = appContext.getString(R.string.test_alarm_rule_name),
        mailSubject = appContext.getString(R.string.test_alarm_subject),
        soundUri = soundUri,
        vibrationPattern = vibration,
        alarmMode = mode,
        soundType = soundType,
    )
}
