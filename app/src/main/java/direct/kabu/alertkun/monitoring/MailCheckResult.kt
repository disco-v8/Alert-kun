package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.alarm.AlarmEvent

data class MailCheckResult(
    val success: Boolean,
    val checkedCount: Int = 0,
    val receivedCount: Int = 0,
    val alertCount: Int = 0,
    val error: Throwable? = null,
    val failureKind: MonitoringFailureKind? = null,
    /** Selected poll alarm after successful processing; notification display only, never another handoff. */
    val selectedAlarm: AlarmEvent? = null,
)

enum class MonitoringFailureKind {
    DEVICE_OFFLINE,
    DNS_FAILURE,
    CONNECTION_TIMEOUT,
    CONNECTION_REFUSED,
    TLS_FAILURE,
    CERTIFICATE_UNTRUSTED,
    CERTIFICATE_EXPIRED,
    HOSTNAME_MISMATCH,
    AUTHENTICATION_FAILURE,
    PROTOCOL_ERROR,
    SERVER_UNAVAILABLE,
}
