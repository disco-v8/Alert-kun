package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.mail.pop3.Pop3ServerDeletion
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*
import jakarta.mail.Session
import jakarta.mail.Folder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TlsValidationTest {
    private data class Fixture(val server: SSLContext, val trustedClient: SSLContext, val leaf: X509Certificate)
    companion object {
        @ClassRule @JvmField val files = TemporaryFolder()
        private val valid by lazy { fixture("valid", false) }
        private val expired by lazy { fixture("expired", true) }
        private fun fixture(name: String, expired: Boolean): Fixture {
            val dir = files.newFolder(name)
            val executable = File(System.getProperty("java.home"), "bin/keytool${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""}")
            fun keytool(vararg args: String) {
                val process = ProcessBuilder(listOf(executable.path) + args).directory(dir).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { output }
            }
            val secret = "fixture-only-password"
            keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-dname", "CN=Fixture CA", "-ext", "bc:c", "-validity", "3650", "-keystore", "ca.p12", "-storepass", secret, "-keypass", secret)
            keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", secret, "-file", "ca.cer")
            keytool("-genkeypair", "-alias", "leaf", "-keyalg", "EC", "-dname", "CN=localhost", "-validity", "3650", "-keystore", "leaf.p12", "-storepass", secret, "-keypass", secret)
            keytool("-certreq", "-alias", "leaf", "-keystore", "leaf.p12", "-storepass", secret, "-file", "leaf.csr")
            val dates = if (expired) arrayOf("-startdate", "2020/01/01 00:00:00", "-validity", "1") else arrayOf("-validity", "3650")
            keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", secret, "-infile", "leaf.csr", "-outfile", "leaf.cer", "-ext", "SAN=dns:localhost", "-ext", "EKU=serverAuth", *dates)
            keytool("-importcert", "-noprompt", "-alias", "ca", "-keystore", "leaf.p12", "-storepass", secret, "-file", "ca.cer")
            keytool("-importcert", "-alias", "leaf", "-keystore", "leaf.p12", "-storepass", secret, "-file", "leaf.cer")
            val keys = KeyStore.getInstance("PKCS12").apply { File(dir, "leaf.p12").inputStream().use { load(it, secret.toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, secret.toCharArray()) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", keys.getCertificate("ca")) }
            val trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            return Fixture(SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) },
                SSLContext.getInstance("TLS").apply { init(null, arrayOf(ValidatedCaTrustManager(trusted.trustManagers.filterIsInstance<X509TrustManager>().single())), null) }, keys.getCertificate("leaf") as X509Certificate)
        }
    }

    private fun connect(mode: TlsValidationMode, host: String, trusted: Boolean, fixture: Fixture = valid,
        fingerprint: String? = null, expected: MonitoringFailureKind? = null) = runBlocking {
        Pop3TestServer(socketFactory = fixture.server.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S,
                host = host, tlsValidationMode = mode, certificateFingerprint = fingerprint)
            val properties = MailConnectionSettings.properties(account).apply {
                if (trusted && mode != TlsValidationMode.PINNED_CERTIFICATE) put("mail.pop3s.ssl.socketFactory", fixture.trustedClient.socketFactory)
            }
            try {
                val store = Session.getInstance(properties).getStore("pop3s")
                try {
                    store.connect(account.host, account.port, account.username, "fixture-secret")
                    val folder = store.getFolder("INBOX")
                    folder.open(Folder.READ_ONLY); folder.close(false)
                } finally { if (store.isConnected) store.close() }
                Pop3ServerDeletion(account, "fixture-secret", socketProvider = { MailConnectionSettings.openSocket(it, properties) })
                    .deleteProcessed({ _, uid -> ReceivedMail("m", account.id, uid, 1, null, emptyMap(), null, null, false,
                        MailProcessingState.PROCESSED, false, null) }, System.currentTimeMillis())
                assertNull(expected)
                assertTrue(server.remaining().isEmpty())
            } catch (error: Exception) {
                if (expected == null) throw error
                assertEquals(expected, MailConnectionSettings.classify(error))
                assertFalse(server.commands.any { it.startsWith("DELE") })
            }
        }
    }

    @Test fun strictAcceptsTrustedMatchingCertificateForReceiveAndDeletion() = connect(TlsValidationMode.STRICT, "localhost", true)
    @Test fun strictRejectsHostnameMismatch() = connect(TlsValidationMode.STRICT, "127.0.0.1", true, expected = MonitoringFailureKind.HOSTNAME_MISMATCH)
    @Test fun strictRejectsUnknownCa() = connect(TlsValidationMode.STRICT, "localhost", false, expected = MonitoringFailureKind.CERTIFICATE_UNTRUSTED)
    @Test fun strictRejectsExpiredLeaf() = connect(TlsValidationMode.STRICT, "localhost", true, expired, expected = MonitoringFailureKind.CERTIFICATE_EXPIRED)
    @Test fun hostnameExceptionAcceptsMatchingCertificate() = connect(TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, "localhost", true)
    @Test fun hostnameExceptionAcceptsMismatchWithValidCa() = connect(TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, "127.0.0.1", true)
    @Test fun hostnameExceptionRejectsUnknownCa() = connect(TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, "127.0.0.1", false, expected = MonitoringFailureKind.CERTIFICATE_UNTRUSTED)
    @Test fun hostnameExceptionRejectsExpiredLeaf() = connect(TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, "127.0.0.1", true, expired, expected = MonitoringFailureKind.CERTIFICATE_EXPIRED)
    @Test fun pinnedModeRejectsUnapprovedCertificate() = connect(TlsValidationMode.PINNED_CERTIFICATE, "localhost", false, expected = MonitoringFailureKind.CERTIFICATE_NOT_APPROVED)
    @Test fun approvedPinAcceptsReceiveAndDeletionWithoutCaOrHostnameTrust() = connect(TlsValidationMode.PINNED_CERTIFICATE, "127.0.0.1", false, fingerprint = certificateFingerprint(valid.leaf))
    @Test fun changedPinRejectsWithoutUpdatingAndOnlyExplicitReapprovalWorks() {
        val old = certificateFingerprint(expired.leaf)
        connect(TlsValidationMode.PINNED_CERTIFICATE, "localhost", false, fingerprint = old, expected = MonitoringFailureKind.CERTIFICATE_CHANGED)
        assertEquals(old, certificateFingerprint(expired.leaf))
        connect(TlsValidationMode.PINNED_CERTIFICATE, "localhost", false, fingerprint = certificateFingerprint(valid.leaf))
        connect(TlsValidationMode.PINNED_CERTIFICATE, "localhost", false, fingerprint = certificateFingerprint(valid.leaf))
    }
    @Test fun pinnedModeAlsoRejectsExpiredCertificate() = connect(TlsValidationMode.PINNED_CERTIFICATE, "localhost", false, expired,
        certificateFingerprint(expired.leaf), MonitoringFailureKind.CERTIFICATE_EXPIRED)
    @Test fun imapsAndPop3sApplyIdenticalAccountPolicy() {
        for (mode in TlsValidationMode.entries) for (protocol in listOf(MailProtocol.POP3S, MailProtocol.IMAPS)) {
            val account = MailAccount("a", "Fixture", protocol, "localhost", 995, "fixture", "key", false, null, true,
                tlsValidationMode = mode, certificateFingerprint = certificateFingerprint(valid.leaf))
            val prefix = "mail.${MailConnectionSettings.protocolName(protocol)}"
            val settings = MailConnectionSettings.properties(account)
            assertEquals((mode == TlsValidationMode.STRICT).toString(), settings.getProperty("$prefix.ssl.checkserveridentity"))
            assertTrue(settings["$prefix.ssl.socketFactory"] is SSLSocketFactory)
            assertNull(settings["$prefix.ssl.trust"])
        }
    }

    @Test fun imapsUsesAllThreeModesForActualAngusHandshakeAndAuthentication() {
        for (mode in TlsValidationMode.entries) {
            val listener = valid.server.serverSocketFactory.createServerSocket(0)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val task = Thread {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        fun send(line: String) { writer.write(line + "\r\n"); writer.flush() }
                        send("* OK [CAPABILITY IMAP4rev1] Fixture")
                        while (true) {
                            val command = reader.readLine() ?: break
                            val tag = command.substringBefore(' ')
                            when (command.substringAfter(' ').substringBefore(' ').uppercase()) {
                                "CAPABILITY" -> { send("* CAPABILITY IMAP4rev1"); send("$tag OK") }
                                "LOGIN" -> send("$tag OK LOGIN complete")
                                "EXAMINE", "SELECT" -> { send("* 0 EXISTS"); send("* 0 RECENT"); send("* OK [UIDVALIDITY 1]"); send("$tag OK [READ-ONLY]") }
                                "LOGOUT" -> { send("* BYE"); send("$tag OK"); break }
                                else -> send("$tag OK")
                            }
                        }
                    }
                } catch (error: Exception) { failure.set(error) }
            }.apply { isDaemon = true; start() }
            try {
                val account = MailAccount("a", "Fixture", MailProtocol.IMAPS,
                    if (mode == TlsValidationMode.STRICT) "localhost" else "127.0.0.1", listener.localPort,
                    "fixture", "key", false, null, true, tlsValidationMode = mode,
                    certificateFingerprint = certificateFingerprint(valid.leaf))
                val properties = MailConnectionSettings.properties(account).apply {
                    if (mode != TlsValidationMode.PINNED_CERTIFICATE) put("mail.imaps.ssl.socketFactory", valid.trustedClient.socketFactory)
                }
                val store = Session.getInstance(properties).getStore("imaps")
                try {
                    store.connect(account.host, account.port, account.username, "fixture-secret")
                    val folder = store.getFolder("INBOX")
                    folder.open(Folder.READ_ONLY); assertEquals(0, folder.messageCount); folder.close(false)
                } finally { if (store.isConnected) store.close() }
            } finally { listener.close(); task.join(10_000) }
            assertNull(failure.get())
        }
    }
}
