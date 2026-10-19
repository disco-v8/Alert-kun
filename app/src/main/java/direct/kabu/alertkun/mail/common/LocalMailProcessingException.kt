package direct.kabu.alertkun.mail.common

/** Never reinterpret local persistence/evaluation failures as retryable remote reads. */
internal class LocalMailProcessingException(cause: Exception) : Exception(cause)
