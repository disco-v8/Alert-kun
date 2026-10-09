package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.security.CredentialStore
import java.net.SocketException
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConnectionObservationTest {
    private val secrets = object : CredentialStore {
        override fun load(key: String) = "fixture-secret"
        override fun save(key: String, password: String) = Unit
        override fun delete(key: String) = Unit
    }

    @Test fun realReadOnlyConnectionRecordsIdentityAndTimingWithoutMailOrSecrets() = runBlocking {
        Pop3TestServer().use { server ->
            val events = mutableListOf<Pair<String, Map<String, Any?>>>()
            val account = server.account(ServerDeletePolicy.NEVER)
            val receiver = AccountMailReceiver(listOf(account), secrets, observation = { e, f -> events += e to f })
            assertEquals(1, receiver.fetchNewMessages().size)
            assertEquals(listOf("MAIL_CONNECT_START", "MAIL_CONNECT_SUCCESS"), events.map { it.first })
            assertEquals(account.id, events.first().second["accountId"])
            assertEquals("POP3", events.last().second["protocol"])
            assertTrue(events.last().second["elapsedMs"] as Long >= 0)
            val text = events.joinToString { ObservationFormat.line(it.first, it.second, 0, TimeZone.getTimeZone("UTC")) }
            listOf("fixture-secret", "fixture-user", "127.0.0.1", "ALERT test", "Disposable test message").forEach { assertFalse(text.contains(it)) }
            assertFalse(server.commands.any { it.startsWith("DELE") })
        }
    }

    @Test fun failedObservationDoesNotChangeReadOnlyReceive() = runBlocking {
        Pop3TestServer().use { server ->
            val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), secrets,
                observation = { _, _ -> error("Observation storage failed") })
            assertEquals(1, receiver.fetchNewMessages().size)
            assertFalse(server.commands.any { it.startsWith("DELE") })
        }
    }

    @Test fun networkWaitAndRetryRecordDecisionsWithoutNetworkIdentifiers() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val source = object : DefaultNetworkSource {
            override val state = MutableStateFlow(DefaultNetworkState())
            override suspend fun awaitReady(timeoutMillis: Long): Boolean {
                state.value = DefaultNetworkState("private-network-id", true, true)
                return true
            }
        }
        val policy = NetworkStabilization(source, pause = {}, observation = { e, f -> events += e to f })
        var attempts = 0
        assertEquals(42, policy.read {
            attempts++
            if (attempts == 1) {
                source.state.value = source.state.value.copy(changeVersion = 1)
                throw MailReceiverException(MonitoringFailureKind.SERVER_UNAVAILABLE, SocketException("private exception text"))
            }
            42
        })
        assertEquals(2, attempts)
        assertEquals(listOf("NETWORK_STABILIZE_START", "NETWORK_STABILIZE_RECOVERED", "NETWORK_RETRY_ALLOWED",
            "NETWORK_RETRY_WAIT", "NETWORK_RETRY_START", "NETWORK_RETRY_END"), events.map { it.first })
        assertEquals(true, events.single { it.first == "NETWORK_RETRY_ALLOWED" }.second["networkChanged"])
        val text = events.joinToString { ObservationFormat.line(it.first, it.second, 0, TimeZone.getTimeZone("UTC")) }
        assertFalse(text.contains("private-network-id"))
        assertFalse(text.contains("private exception text"))
    }

    @Test fun rejectedRetryIsObservedAndOriginalFailureIsPreserved() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val source = object : DefaultNetworkSource {
            override val state = MutableStateFlow(DefaultNetworkState("private", true, true))
        }
        val failure = MailReceiverException(MonitoringFailureKind.AUTHENTICATION_FAILURE)
        val policy = NetworkStabilization(source, pause = {}, observation = { e, f -> events += e to f })
        try {
            policy.read<Int> { throw failure }
            fail("Should preserve failure")
        } catch (error: MailReceiverException) { assertSame(failure, error) }
        assertEquals("NETWORK_RETRY_SKIPPED", events.single().first)
        assertEquals(false, events.single().second["networkChanged"])
        assertEquals("AUTHENTICATION_FAILURE", events.single().second["category"])
    }
}
