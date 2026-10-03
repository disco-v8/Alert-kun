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
    val alarmMode: AlarmMode,
    val enabled: Boolean,
)

enum class RuleTarget {
    SUBJECT,
    BODY,
    SUBJECT_AND_BODY,
}

enum class AlarmMode {
    ONCE,
    UNTIL_STOPPED,
}

enum class VibrationPattern {
    NONE,
    PATTERN_1,
    PATTERN_2,
    PATTERN_3,
}
