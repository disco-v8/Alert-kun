package direct.kabu.alertkun.model

/** The monitoring status presented to the rest of the application. */
data class MonitoringState(
    val isMonitoring: Boolean,
    val lastSuccessfulCheckAtEpochMillis: Long?,
    val nextScheduledCheckAtEpochMillis: Long?,
    val consecutiveFailureCount: Int,
    val lastFcmReceivedAtEpochMillis: Long? = null,
    val lastGatewayRegistrationAtEpochMillis: Long? = null,
    val referenceIntervalMinutes: Int? = null,
)
