package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.TlsValidationMode
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Properties
import javax.net.ssl.SSLException
import org.eclipse.angus.mail.util.SocketFetcher
import org.eclipse.angus.mail.util.MailSSLSocketFactory

/** Receive and explicit POP3 deletion use the same Angus TLS/trust/identity configuration. */
internal object MailConnectionSettings {
    fun protocolName(protocol: MailProtocol): String = when (protocol) {
        MailProtocol.POP3 -> "pop3"
        MailProtocol.POP3S -> "pop3s"
        MailProtocol.IMAP4 -> "imap"
        MailProtocol.IMAPS -> "imaps"
    }

    fun properties(protocol: MailProtocol): Properties = Properties().apply {
        val name = protocolName(protocol)
        put("mail.store.protocol", name)
        for (timeout in listOf("connectiontimeout", "timeout", "writetimeout")) {
            put("mail.$name.$timeout", "30000")
        }
        put("mail.$name.starttls.enable", "false")
        if (protocol == MailProtocol.POP3S || protocol == MailProtocol.IMAPS) {
            put("mail.$name.ssl.enable", "true")
            put("mail.$name.ssl.checkserveridentity", "true")
            if (protocol == MailProtocol.POP3S || protocol == MailProtocol.IMAPS) {
                // Also verify explicitly on providers that do not implement LDAPS endpoint checking.
                put("mail.$name.ssl.hostnameverifier.class", "MailHostnameVerifier")
            }
        }
    }

    fun properties(account: MailAccount): Properties = properties(account.protocol).apply {
        if (account.protocol !in listOf(MailProtocol.POP3S, MailProtocol.IMAPS)) return@apply
        val prefix = "mail.${protocolName(account.protocol)}"
        if (account.tlsValidationMode in listOf(TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, TlsValidationMode.INSECURE)) {
            put("$prefix.ssl.checkserveridentity", "false")
            remove("$prefix.ssl.hostnameverifier.class")
        }
        if (account.tlsValidationMode == TlsValidationMode.SELF_SIGNED_ALLOWED) {
            val context = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(ExpiryValidatedTrustManager()), null)
            }
            put("$prefix.ssl.socketFactory", context.socketFactory)
            put("$prefix.socketFactory.fallback", "false")
        } else if (account.tlsValidationMode == TlsValidationMode.INSECURE) {
            // Scoped to this explicitly selected account. TLS remains enabled.
            put("$prefix.ssl.socketFactory", MailSSLSocketFactory("TLS").apply { setTrustAllHosts(true) })
            put("$prefix.socketFactory.fallback", "false")
        } else {
            put("$prefix.ssl.socketFactory", caValidatedSocketFactory())
            put("$prefix.socketFactory.fallback", "false")
        }
    }

    fun openSocket(account: MailAccount, connectionProperties: Properties = properties(account)): Socket = SocketFetcher.getSocket(
        account.host, account.port, connectionProperties, "mail.${protocolName(account.protocol)}",
        account.protocol == MailProtocol.POP3S || account.protocol == MailProtocol.IMAPS,
    )

    fun classify(error: Throwable): MonitoringFailureKind {
        val chain = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).toList()
        chain.filterIsInstance<MailReceiverException>().firstOrNull()?.let { return it.failureKind }
        if (chain.any { it is java.security.cert.CertificateExpiredException || it is java.security.cert.CertificateNotYetValidException }) return MonitoringFailureKind.CERTIFICATE_EXPIRED
        if (chain.any { it.message.orEmpty().contains("subject alternative", true) || it.message.orEmpty().contains("hostname", true) || it.message.orEmpty().contains("Can't verify identity", true) || it is java.security.cert.CertPathValidatorException && it.reason == java.security.cert.PKIXReason.INVALID_NAME }) return MonitoringFailureKind.HOSTNAME_MISMATCH
        if (chain.any { it is java.security.cert.CertPathValidatorException || it.javaClass.simpleName == "SunCertPathBuilderException" || it is CertificateException && it.message.orEmpty().contains("trust anchor", true) }) return MonitoringFailureKind.CERTIFICATE_UNTRUSTED
        if (chain.any { it is UnknownHostException }) return MonitoringFailureKind.DNS_FAILURE
        if (chain.any { it is SocketTimeoutException }) return MonitoringFailureKind.CONNECTION_TIMEOUT
        if (chain.any { it is ConnectException }) return MonitoringFailureKind.CONNECTION_REFUSED
        if (chain.any { it is SSLException || it is CertificateException }) return MonitoringFailureKind.TLS_FAILURE
        if (chain.any { it is AuthenticationFailedException }) return MonitoringFailureKind.AUTHENTICATION_FAILURE
        if (chain.any { it is java.net.SocketException || it is java.io.EOFException }) return MonitoringFailureKind.SERVER_UNAVAILABLE
        return if (error is MessagingException) MonitoringFailureKind.PROTOCOL_ERROR else MonitoringFailureKind.SERVER_UNAVAILABLE
    }
}
