package direct.kabu.alertkun.monitoring

data class MonitoringHealthStatus(
    val monitoringEnabled: Boolean,
    val notificationEnabled: Boolean,
    val observingSince: Long?,
    val lastNotificationAt: Long?,
    val lastFailedCheckAt: Long?,
    val lastSuccessfulCheckAt: Long?,
    val lastAttemptSucceeded: Boolean,
    val consecutiveFailureCount: Int,
)

data class MonitoringHealthReport(val alertCount: Int, val lastSuccessfulCheckAt: Long)

/** A rolling 24-hour health report, never a scheduling or FCM delivery guarantee. */
object MonitoringHealthNotificationPolicy {
    const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    fun report(now: Long, status: MonitoringHealthStatus, alertCount: Int): MonitoringHealthReport? {
        require(alertCount >= 0)
        return if (shouldNotify(now, status)) MonitoringHealthReport(alertCount, now) else null
    }

    fun shouldNotify(now: Long, status: MonitoringHealthStatus): Boolean {
        if (!status.monitoringEnabled || !status.notificationEnabled || !status.lastAttemptSucceeded ||
            status.consecutiveFailureCount != 0 || now <= DAY_MILLIS) return false
        val since = status.observingSince ?: return false
        val success = status.lastSuccessfulCheckAt ?: return false
        // This is called at an actual successful check, never at a backup skip.
        if (success != now || since <= 0 || since > now || now - since < DAY_MILLIS) return false
        val last = status.lastNotificationAt
        if (last != null && (last <= 0 || last > now || now - last < DAY_MILLIS)) return false
        val failure = status.lastFailedCheckAt
        // Unknown/future failure timestamps are deliberately not evidence of a healthy day.
        return failure == null || (failure > 0 && failure < now - DAY_MILLIS)
    }
}
