package direct.kabu.alertkun.mail.common

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.TlsValidationMode
import direct.kabu.alertkun.monitoring.MailCheckWorker
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import java.net.InetAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in trials: ephemeral fixture TLS or read-only connection tests. Never saves an account or deletes mail. */
class TlsModeDeviceTest {
    @Test fun selfSignedFixtureChecksHostnameWithoutApprovalForBothProtocols() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("tlsFixturePath")
        assumeTrue(path != null)
        require(checkNotNull(path).matches(Regex("/data/local/tmp/[a-zA-Z0-9_.-]+\\.p12")))
        val keys = KeyStore.getInstance("PKCS12").apply {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path")).use {
                load(it, "fixture-only".toCharArray())
            }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, "fixture-only".toCharArray())
        }
        val server = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        fun handshake(protocol: MailProtocol, mode: TlsValidationMode, host: String, expected: MonitoringFailureKind?) {
            server.serverSocketFactory.createServerSocket(0).use { listener ->
                val task = Thread {
                    try {
                        (listener.accept() as SSLSocket).use { it.soTimeout = 5_000; it.startHandshake() }
                    } catch (_: Exception) { /* Rejected client handshakes are expected. */ }
                }.apply { isDaemon = true; start() }
                val account = MailAccount("fixture", "Fixture", protocol, host, listener.localPort,
                    "fixture", "fixture-key", false, null, true, tlsValidationMode = mode)
                try {
                    try {
                        MailConnectionSettings.openSocket(account).use {
                            assertTrue(it is SSLSocket)
                            assertTrue((it as SSLSocket).session.protocol.startsWith("TLS"))
                        }
                        assertNull("Expected TLS refusal: $mode", expected)
                    } catch (error: Exception) {
                        if (expected == null) throw error
                        assertEquals(expected, MailConnectionSettings.classify(error))
                    }
                } finally { listener.close(); task.join(6_000) }
            }
        }
        for (protocol in listOf(MailProtocol.POP3S, MailProtocol.IMAPS)) {
            handshake(protocol, TlsValidationMode.STRICT, "localhost", MonitoringFailureKind.CERTIFICATE_UNTRUSTED)
            handshake(protocol, TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, "localhost", MonitoringFailureKind.CERTIFICATE_UNTRUSTED)
            handshake(protocol, TlsValidationMode.SELF_SIGNED_ALLOWED, "localhost", null)
            handshake(protocol, TlsValidationMode.SELF_SIGNED_ALLOWED, "127.0.0.1", MonitoringFailureKind.HOSTNAME_MISMATCH)
            handshake(protocol, TlsValidationMode.INSECURE, "127.0.0.1", null)
        }
    }

    @Test fun savedServerHostnameMismatchModesWithoutChangingSavedAccount() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowReadOnlyTlsTest") == "true")
        MailCheckWorker.executionMutex.withLock {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = AccountRepository(AlertKunDatabase.getInstance(context).accountDao())
            val saved = repository.accounts.first().single { it.enabled }
            val address = InetAddress.getByName(saved.host).hostAddress!!
            val secrets = AndroidKeystoreCredentialStore(context)
            for (protocol in listOf(MailProtocol.POP3S, MailProtocol.IMAPS)) {
                val candidate = saved.copy(host = address, protocol = protocol,
                    port = if (protocol == saved.protocol) saved.port else if (protocol == MailProtocol.POP3S) 995 else 993)
                for (mode in TlsValidationMode.entries) {
                    val account = candidate.copy(tlsValidationMode = mode)
                    try {
                        AccountMailReceiver(listOf(account), secrets).testConnection(account)
                        assertTrue(mode in listOf(TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, TlsValidationMode.INSECURE))
                    } catch (error: MailReceiverException) {
                        assertTrue(mode in listOf(TlsValidationMode.STRICT, TlsValidationMode.SELF_SIGNED_ALLOWED))
                        assertEquals(MonitoringFailureKind.HOSTNAME_MISMATCH, error.failureKind)
                    }
                    println("TLS_MODE_DEVICE protocol=$protocol mode=$mode result=${if (mode in listOf(TlsValidationMode.STRICT, TlsValidationMode.SELF_SIGNED_ALLOWED)) "HOSTNAME_MISMATCH" else "SUCCESS"}")
                }
            }
            assertEquals(saved, repository.get(saved.id))
        }
    }
}
