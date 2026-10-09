package direct.kabu.alertkun.mail.common

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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

/** Trust-store independent validation; Angus separately enforces the hostname. */
internal class ExpiryValidatedTrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not supported")
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
        leaf.checkValidity()
    }
}
