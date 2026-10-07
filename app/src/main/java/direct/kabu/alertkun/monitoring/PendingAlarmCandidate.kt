package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.alarm.AlarmEvent

internal data class PendingAlarmCandidate(
    val receivedMailId: String,
    val accountId: String,
    val remoteMessageId: String,
    val matchedRuleId: String,
    val priority: Int,
    val receivedAtEpochMillis: Long,
    val event: AlarmEvent,
) {
    companion object {
        // Stable account + remote identity precedes the randomly assigned local UUID.
        val selectionOrder = compareBy<PendingAlarmCandidate> { it.priority }
            .thenByDescending { it.receivedAtEpochMillis }
            .thenBy { it.accountId }
            .thenBy { it.remoteMessageId }
            .thenBy { it.receivedMailId }
    }
}
