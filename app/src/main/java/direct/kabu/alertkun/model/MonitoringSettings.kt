package direct.kabu.alertkun.model

data class MonitoringSettings(
    val pollIntervalMinutes: Int = 5,
    val mailRetentionDays: Int = 7,
    val consecutiveFailureThreshold: Int = 3,
    val monitoringEnabled: Boolean = false,
    val checkImmediatelyOnAppStart: Boolean = true,
    val healthNotificationEnabled: Boolean = true,
)
