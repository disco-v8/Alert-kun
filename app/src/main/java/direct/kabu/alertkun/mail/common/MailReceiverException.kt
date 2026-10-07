package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.monitoring.MonitoringFailureKind

class MailReceiverException(
    val failureKind: MonitoringFailureKind,
    cause: Throwable? = null,
) : Exception(cause)
