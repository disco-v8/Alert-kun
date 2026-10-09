package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.MailConnectionSettings
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import jakarta.mail.Folder
import jakarta.mail.Session
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real TLS against a temporary local certificate with disposable messages and fixture credentials. */
class Pop3TlsDeletionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_700_000_000_000L

    private fun contexts(expired: Boolean = false): Pair<SSLContext, SSLContext> {
        val path = File(temporary.root, "fixture.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool.exe")
        val executable = if (keytool.exists()) keytool else File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(listOf(executable.absolutePath, "-genkeypair", "-alias", "fixture", "-keyalg", "EC",
            "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
            "-ext", "EKU=serverAuth", "-validity", "2", "-storetype", "PKCS12", "-keystore", path.absolutePath,
            "-storepass", "fixture-only", "-noprompt") + if (expired) listOf("-startdate", "2020/01/01 00:00:00") else emptyList()).redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        assertEquals("Temporary test certificate creation", 0, process.waitFor())
        val keys = KeyStore.getInstance("PKCS12").apply { path.inputStream().use { load(it, "fixture-only".toCharArray()) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, "fixture-only".toCharArray())
        }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("fixture", keys.getCertificate("fixture"))
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val server = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        val client = SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
        return server to client
    }

    private fun properties(client: SSLContext) = MailConnectionSettings.properties(MailProtocol.POP3S).apply {
        put("mail.pop3s.ssl.socketFactory", client.socketFactory)
        setProperty("mail.pop3s.socketFactory.fallback", "false")
    }

    private fun mail(uidl: String, receivedAt: Long = now) = ReceivedMail(uidl, "deletion-test", uidl,
        receivedAt, null, emptyMap(), null, null, false, MailProcessingState.PROCESSED, false, null)

    @Test fun strictAndCaOnlyRejectSelfSignedBeforeAuthentication() = runBlocking {
        val (serverTls, _) = contexts()
        for (mode in listOf(TlsValidationMode.STRICT, TlsValidationMode.CA_VALIDATED_NO_HOSTNAME)) {
            Pop3TestServer(socketFactory = serverTls.serverSocketFactory).use { server ->
                val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S,
                    host = "localhost", tlsValidationMode = mode)
                try {
                    MailConnectionSettings.openSocket(account).close()
                    fail("Self-signed must not be trusted in $mode")
                } catch (error: Exception) {
                    assertEquals(MonitoringFailureKind.CERTIFICATE_UNTRUSTED, MailConnectionSettings.classify(error))
                }
                assertTrue(server.commands.isEmpty())
            }
        }
    }

    @Test fun insecureSelfSignedReceiveAndDeletionUseEncryptedConnectionsWithoutPin() = runBlocking {
        val (serverTls, _) = contexts()
        Pop3TestServer(socketFactory = serverTls.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S,
                tlsValidationMode = TlsValidationMode.INSECURE)
            val store = Session.getInstance(MailConnectionSettings.properties(account)).getStore("pop3s")
            try {
                store.connect(account.host, account.port, account.username, "fixture-secret")
                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY); folder.close(false)
                assertEquals(listOf("test-uid-1"), server.remaining())
                assertFalse(server.commands.any { it.startsWith("DELE") })
            } finally { if (store.isConnected) store.close() }
            Pop3ServerDeletion(account, "fixture-secret").deleteProcessed({ _, uid -> mail(uid) }, now)
            assertTrue(server.remaining().isEmpty())
        }
    }

    @Test fun selfSignedAllowedReceiveAndDeletionNeedNoApproval() = runBlocking {
        val (serverTls, _) = contexts()
        Pop3TestServer(socketFactory = serverTls.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S,
                host = "localhost", tlsValidationMode = TlsValidationMode.SELF_SIGNED_ALLOWED)
            val store = Session.getInstance(MailConnectionSettings.properties(account)).getStore("pop3s")
            try {
                store.connect(account.host, account.port, account.username, "fixture-secret")
                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY); folder.close(false)
            } finally { if (store.isConnected) store.close() }
            Pop3ServerDeletion(account, "fixture-secret").deleteProcessed({ _, uid -> mail(uid) }, now)
            assertTrue(server.remaining().isEmpty())
        }
    }

    @Test fun selfSignedAllowedRejectsWrongHostnameBeforeAuthentication() = runBlocking {
        val (serverTls, _) = contexts()
        Pop3TestServer(socketFactory = serverTls.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S,
                tlsValidationMode = TlsValidationMode.SELF_SIGNED_ALLOWED)
            try { MailConnectionSettings.openSocket(account).close(); fail("Hostname must match") }
            catch (error: Exception) { assertEquals(MonitoringFailureKind.HOSTNAME_MISMATCH, MailConnectionSettings.classify(error)) }
            assertTrue(server.commands.isEmpty())
        }
    }

    @Test fun selfSignedAllowedRejectsExpiredSelfSignedAndInsecureAcceptsIt() = runBlocking {
        val (serverTls, _) = contexts(expired = true)
        for (mode in listOf(TlsValidationMode.SELF_SIGNED_ALLOWED, TlsValidationMode.INSECURE)) {
            Pop3TestServer(socketFactory = serverTls.serverSocketFactory).use { server ->
                val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S,
                    host = "localhost", tlsValidationMode = mode)
                try {
                    MailConnectionSettings.openSocket(account).use {
                        assertTrue(it is javax.net.ssl.SSLSocket)
                        assertTrue((it as javax.net.ssl.SSLSocket).session.protocol.startsWith("TLS"))
                    }
                    assertEquals(TlsValidationMode.INSECURE, mode)
                } catch (error: Exception) {
                    assertEquals(TlsValidationMode.SELF_SIGNED_ALLOWED, mode)
                    assertEquals(MonitoringFailureKind.CERTIFICATE_EXPIRED, MailConnectionSettings.classify(error))
                }
                assertTrue(server.commands.isEmpty())
            }
        }
    }

    @Test fun trustedPop3sReceiveAndDeletionUseSameTlsSettingsAndCommitOnlyEligibleTargets() = runBlocking {
        val (serverTls, clientTls) = contexts()
        Pop3TestServer(listOf("old", "recent"), serverTls.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.AFTER_1_DAY).copy(protocol = MailProtocol.POP3S, host = "localhost")
            val properties = properties(clientTls)
            val store = Session.getInstance(properties).getStore("pop3s")
            try {
                store.connect(account.host, account.port, account.username, "fixture-secret")
                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)
                assertEquals(2, folder.messageCount)
                folder.close(false)
            } finally { store.close() }
            assertEquals(listOf("old", "recent"), server.remaining())
            assertFalse(server.commands.any { it.startsWith("DELE") })
            Pop3ServerDeletion(account, "fixture-secret", socketProvider = { MailConnectionSettings.openSocket(it, properties) })
                .deleteProcessed({ _, uidl -> mail(uidl, if (uidl == "old") now - 86_400_000L else now) }, now)
            assertEquals(listOf("recent"), server.remaining())
            assertEquals(listOf("DELE 1"), server.commands.filter { it.startsWith("DELE") })
        }
    }

    @Test fun trustedPop3sWithNoEligibleMailCompletesWithoutDele() = runBlocking {
        val (serverTls, clientTls) = contexts()
        Pop3TestServer(listOf("recent"), serverTls.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.AFTER_1_DAY).copy(protocol = MailProtocol.POP3S, host = "localhost")
            Pop3ServerDeletion(account, "fixture-secret", socketProvider = { MailConnectionSettings.openSocket(it, properties(clientTls)) })
                .deleteProcessed({ _, uidl -> mail(uidl) }, now)
            assertEquals(listOf("recent"), server.remaining())
            assertFalse(server.commands.any { it.startsWith("DELE") })
            assertTrue(server.commands.contains("QUIT"))
        }
    }

    @Test fun wrongCertificateHostnameFailsBeforeAuthOrDeleteEvenWithTrustedCertificate() = runBlocking {
        val (serverTls, clientTls) = contexts()
        Pop3TestServer(socketFactory = serverTls.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S)
            try {
                Pop3ServerDeletion(account, "fixture-secret", socketProvider = { MailConnectionSettings.openSocket(it, properties(clientTls)) })
                    .deleteProcessed({ _, _ -> error("Must not reach Room lookup") }, now)
                fail("Expected hostname rejection")
            } catch (failure: MailReceiverException) { assertEquals(MonitoringFailureKind.HOSTNAME_MISMATCH, failure.failureKind) }
            assertTrue(server.commands.isEmpty())
            assertEquals(listOf("test-uid-1"), server.remaining())
        }
    }

    @Test fun untrustedCertificateFailsBeforeAuthOrDelete() = runBlocking {
        val (serverTls, _) = contexts()
        Pop3TestServer(socketFactory = serverTls.serverSocketFactory).use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.POP3S, host = "localhost")
            try {
                Pop3ServerDeletion(account, "fixture-secret").deleteProcessed({ _, _ -> error("Must not lookup") }, now)
                fail("Expected trust rejection")
            } catch (failure: MailReceiverException) { assertEquals(MonitoringFailureKind.CERTIFICATE_UNTRUSTED, failure.failureKind) }
            assertTrue(server.commands.isEmpty())
        }
    }

    @Test fun deletionConnectionFailureRetainsItsActualFailureKind() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val account = MailAccount("deletion-test", "Fixture", MailProtocol.POP3S, "localhost", port,
            "fixture-user", "fixture-key", false, null, true, ServerDeletePolicy.IMMEDIATELY)
        try {
            Pop3ServerDeletion(account, "fixture-secret").deleteProcessed({ _, _ -> error("Must not lookup") }, now)
            fail("Expected refused connection")
        } catch (failure: MailReceiverException) { assertEquals(MonitoringFailureKind.CONNECTION_REFUSED, failure.failureKind) }
    }

    @Test fun productionPop3sConfigurationRequiresTrustAndExplicitHostnameVerification() {
        val properties = MailConnectionSettings.properties(MailProtocol.POP3S)
        assertEquals("true", properties.getProperty("mail.pop3s.ssl.enable"))
        assertEquals("true", properties.getProperty("mail.pop3s.ssl.checkserveridentity"))
        assertEquals("MailHostnameVerifier", properties.getProperty("mail.pop3s.ssl.hostnameverifier.class"))
        assertEquals("false", properties.getProperty("mail.pop3s.starttls.enable"))
        assertNull(properties["mail.pop3s.ssl.socketFactory"])
        assertNull(properties["mail.pop3s.ssl.trust"])
        assertEquals(MonitoringFailureKind.TLS_FAILURE,
            MailConnectionSettings.classify(java.security.cert.CertificateException("Fixture identity rejection")))
        assertEquals(MonitoringFailureKind.TLS_FAILURE,
            MailConnectionSettings.classify(java.io.IOException("Fixture", java.security.cert.CertificateException())))
    }
}
