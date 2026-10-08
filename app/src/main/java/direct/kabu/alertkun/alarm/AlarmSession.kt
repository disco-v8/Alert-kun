package direct.kabu.alertkun.alarm

/** Live service-owned session. A persisted handoff alone is not proof of active playback. */
data class AlarmSession(
    val receivedMailId: String,
    val ruleId: String,
    val startedAtEpochMillis: Long,
)

internal object AlarmSessionPolicy {
    fun isSameAlarm(current: AlarmEvent?, requested: AlarmEvent): Boolean =
        current?.alarmMode == direct.kabu.alertkun.model.AlarmMode.UNTIL_STOPPED &&
            requested.alarmMode == current.alarmMode &&
            current.mailId == requested.mailId && current.ruleId == requested.ruleId
}
