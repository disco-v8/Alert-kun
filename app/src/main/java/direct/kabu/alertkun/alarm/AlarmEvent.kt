package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern

/** Immutable, post-rule-evaluation input for Android alarm delivery. */
data class AlarmEvent(
    val mailId: String,
    val ruleId: String,
    val ruleName: String,
    val mailSubject: String?,
    val soundUri: String?,
    val vibrationPattern: VibrationPattern,
    val alarmMode: AlarmMode,
) {
    init {
        require(mailId.isNotBlank())
        require(ruleId.isNotBlank())
        require(ruleName.isNotBlank())
    }
}

enum class AlarmState {
    IDLE,
    ACTIVE,
}

/** Keeps the MVP's persistent-alarm state independent from Android framework classes. */
class AlarmLifecycle {
    var state: AlarmState = AlarmState.IDLE
        private set

    fun start(mode: AlarmMode): AlarmState {
        state = if (mode == AlarmMode.UNTIL_STOPPED) AlarmState.ACTIVE else AlarmState.IDLE
        return state
    }

    /** Returns whether a persistent alarm had been active before this call. */
    fun stop(): Boolean {
        val wasActive = state == AlarmState.ACTIVE
        state = AlarmState.IDLE
        return wasActive
    }
}
