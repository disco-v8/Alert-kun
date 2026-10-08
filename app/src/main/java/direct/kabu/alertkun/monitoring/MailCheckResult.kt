package direct.kabu.alertkun.monitoring

data class MailCheckResult(
    val success: Boolean,
    val checkedCount: Int = 0,
    val receivedCount: Int = 0,
    val alertCount: Int = 0,
    val error: Throwable? = null,
    val failureKind: MonitoringFailureKind? = null,
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
    CERTIFICATE_NOT_APPROVED,
    CERTIFICATE_CHANGED,
    AUTHENTICATION_FAILURE,
    PROTOCOL_ERROR,
    SERVER_UNAVAILABLE,
}
