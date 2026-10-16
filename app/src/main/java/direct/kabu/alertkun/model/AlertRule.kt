package direct.kabu.alertkun.model

/** A rule that selects one received email for an alarm. */
data class AlertRule(
    val id: String,
    val name: String,
    val searchText: String,
    val target: RuleTarget,
    val priority: Int,
    val soundUri: String?,
    val vibrationPattern: VibrationPattern,
    val alarmMode: AlarmMode = AlarmMode.ONCE,
    val enabled: Boolean,
    val backgroundColor: MailBackgroundColor = MailBackgroundColor.DEFAULT,
    val soundType: AlarmSoundType = AlarmSoundType.ALARM,
) {
    fun withSoundType(type: AlarmSoundType): AlertRule =
        if (type == soundType) this else copy(soundType = type, soundUri = null)
}

enum class RuleTarget {
    SUBJECT,
    BODY,
    SUBJECT_AND_BODY,
    SENDER,
}

enum class AlarmMode(val finiteCount: Int?) {
    ONCE(1),
    THREE_TIMES(3),
    FIVE_TIMES(5),
    UNTIL_STOPPED(null),
}

enum class VibrationPattern {
    NONE,
    PATTERN_1,
    PATTERN_2,
    PATTERN_3,
}
