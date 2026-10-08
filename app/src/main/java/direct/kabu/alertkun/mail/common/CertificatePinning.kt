package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TlsCertificateException(val kind: MonitoringFailureKind) : CertificateException(kind.name)

/** Standard CA validation, with an explicit leaf validity check for clear expiry diagnostics. */
internal class ValidatedCaTrustManager(private val delegate: X509TrustManager) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        (chain.firstOrNull() ?: throw CertificateException("Missing certificate")).checkValidity()
        delegate.checkServerTrusted(chain, authType)
    }
}

internal fun caValidatedSocketFactory(): javax.net.ssl.SSLSocketFactory {
    val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(null as java.security.KeyStore?)
    }
    val manager = managers.trustManagers.filterIsInstance<X509TrustManager>().single()
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(ValidatedCaTrustManager(manager)), null) }.socketFactory
}

internal fun certificateFingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it.toInt() and 255) }

/** Only a previously approved leaf certificate is accepted; neither CA nor hostname bypass grants trust. */
internal class PinnedCertificateTrustManager(private val approved: String?) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not supported")
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
        if (approved == null || !Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}").matches(approved)) {
            throw TlsCertificateException(MonitoringFailureKind.CERTIFICATE_NOT_APPROVED)
        }
        val actual = certificateFingerprint(leaf)
        if (!MessageDigest.isEqual(approved.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))) {
            throw TlsCertificateException(MonitoringFailureKind.CERTIFICATE_CHANGED)
        }
        leaf.checkValidity()
    }
}

/** Collects the public certificate and rejects the handshake. Never authenticates or accepts trust. */
internal suspend fun inspectServerCertificate(account: MailAccount): String = withContext(Dispatchers.IO) {
    var fingerprint: String? = null
    val rejectAfterInspection = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException() }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            fingerprint = chain.firstOrNull()?.let(::certificateFingerprint)
            throw CertificateException("Certificate inspection only")
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(rejectAfterInspection), null) }
    Socket().use { raw ->
        raw.connect(InetSocketAddress(account.host, account.port), 30_000)
        (context.socketFactory.createSocket(raw, account.host, account.port, true) as SSLSocket).use { tls ->
            tls.soTimeout = 30_000
            tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(account.host)) }
            try { tls.startHandshake() } catch (error: Exception) {
                if (fingerprint == null) throw MailReceiverException(MailConnectionSettings.classify(error), error)
            }
        }
    }
    checkNotNull(fingerprint)
}
