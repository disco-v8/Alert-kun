package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.model.MonitoringState

/** In-memory display of a final poll outcome; never another persisted counter or alarm delivery. */
internal sealed class MonitoringStatusResult(val observationState: String) {
    data object Checking : MonitoringStatusResult("CHECKING")
    data class Success(val selectedAlarm: AlarmEvent?) : MonitoringStatusResult(if (selectedAlarm == null) "NORMAL" else "RULE_MATCH")
    data class Failure(val count: Int, val threshold: Int) : MonitoringStatusResult("FAILURE")
}

internal data class MailCheckCompletion(
    val state: MonitoringState,
    val display: MonitoringStatusResult,
    val shouldNotifyFailure: Boolean = false,
)

/** Called once after all accounts and bounded read retries finish. The repository owns accounting. */
internal class MailCheckCompletionHandler(private val status: MonitoringStatusRepository) {
    suspend fun complete(result: MailCheckResult, completedAt: Long, threshold: Int,
        trigger: MailCheckTrigger, generation: Long): MailCheckCompletion {
        if (result.success) return MailCheckCompletion(status.recordSuccess(completedAt, trigger, generation),
            MonitoringStatusResult.Success(result.selectedAlarm))
        val failure = status.recordFailure(completedAt, threshold, generation)
        return MailCheckCompletion(failure.state,
            MonitoringStatusResult.Failure(failure.state.consecutiveFailureCount, threshold), failure.shouldNotify)
    }
}
