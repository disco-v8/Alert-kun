package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.ReceivedMail
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class DefaultNetworkState(
    val networkId: String? = null,
    val internet: Boolean = false,
    val validated: Boolean = false,
    val changeVersion: Long = 0,
) {
    val ready: Boolean get() = networkId != null && internet && validated
}

internal interface DefaultNetworkSource {
    val state: StateFlow<DefaultNetworkState>
    suspend fun awaitReady(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { state.first { it.ready }; true } ?: false
}

/** One check's bounded waiting budget and single read-only retry; no Android dependency. */
internal class NetworkStabilization(
    private val network: DefaultNetworkSource,
    private val log: (String) -> Unit = {},
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitBudgetMillis: Long = WAIT_BUDGET_MILLIS,
) {
    private var remainingWait = waitBudgetMillis
    private var retried = false

    suspend fun awaitNetwork() {
        if (!network.state.value.ready && remainingWait > 0) {
            val started = elapsedMillis()
            log("network stabilization wait start remainingMs=$remainingWait")
            val ready = try { network.awaitReady(remainingWait) } finally {
                remainingWait = (remainingWait - (elapsedMillis() - started).coerceAtLeast(0)).coerceAtLeast(0)
            }
            if (!ready) remainingWait = 0
            log(if (ready) "network stabilization recovered" else "network stabilization timeout")
        }
        // Validation is a hint, not proof that the mail server is reachable (or unreachable).
        // If any default network exists, the actual connection determines the outcome.
        if (network.state.value.networkId == null) throw MailReceiverException(MonitoringFailureKind.DEVICE_OFFLINE)
    }

    suspend fun <T> read(operation: suspend () -> T): T {
        awaitNetwork()
        val before = network.state.value.changeVersion
        try { return operation() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (retried || network.state.value.changeVersion == before || !isTransitionRetryCandidate(error)) throw error
            retried = true
            log("network transition limited retry attempt=1")
            awaitNetwork()
            return operation()
        }
    }

    companion object {
        const val WAIT_BUDGET_MILLIS = 8_000L
        fun isTransitionRetryCandidate(error: Throwable): Boolean {
            val chain = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).toList()
            val kind = chain.filterIsInstance<MailReceiverException>().firstOrNull()?.failureKind
            if (kind != null && kind !in setOf(MonitoringFailureKind.DNS_FAILURE, MonitoringFailureKind.CONNECTION_TIMEOUT,
                    MonitoringFailureKind.CONNECTION_REFUSED, MonitoringFailureKind.SERVER_UNAVAILABLE)) return false
            if (chain.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException || it is jakarta.mail.AuthenticationFailedException }) return false
            return chain.any { it is IOException }
        }
    }
}

/** Retry only read-only remote work before persistence/handoff; never replay the whole Coordinator. */
internal class NetworkStabilizingMailReceiver(
    private val delegate: MailReceiver,
    private val network: NetworkStabilization,
    private val hasDeletionConnection: Boolean = true,
) : MailReceiver {
    override suspend fun fetchNewMessages(): List<RemoteMail> = network.read { delegate.fetchNewMessages() }
    override suspend fun fetchBody(remoteMail: RemoteMail): String? = network.read { delegate.fetchBody(remoteMail) }
    override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
        if (hasDeletionConnection) network.awaitNetwork()
        // DELE/QUIT may already have reached the server. Never retry an uncertain deletion here.
        delegate.deleteProcessedMessages(lookup, now)
    }
}
