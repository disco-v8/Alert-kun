package direct.kabu.alertkun.model

/** A poll's final snapshots, committed together after its selected alarm handoff succeeds. */
data class MailProcessingOutcome(
    val mailId: String,
    val alertTriggered: Boolean,
    val matchedRuleId: String?,
    val backgroundColor: MailBackgroundColor,
)
