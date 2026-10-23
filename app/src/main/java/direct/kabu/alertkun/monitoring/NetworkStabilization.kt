package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.ReceivedMail
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
    fun readStarted() {}
    fun readFailed(error: Throwable) {}
    fun readSucceeded() {}
    suspend fun awaitReady(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { state.first { it.ready }; true } ?: false
}

/** One check's shared waiting budget and at most two read-only retries; no Android dependency. */
internal class NetworkStabilization(
    private val network: DefaultNetworkSource,
    private val log: (String) -> Unit = {},
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitBudgetMillis: Long = WAIT_BUDGET_MILLIS,
    private val observation: ObservationSink = { _, _ -> },
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private var remainingWait = waitBudgetMillis
    private var retriesUsed = 0
    private fun observe(event: String, vararg fields: Pair<String, Any?>) {
        runCatching {
            val state = network.state.value
            observation(event, mapOf("defaultNetworkPresent" to (state.networkId != null), "validated" to state.validated,
                "internet" to state.internet, "remainingWaitMs" to remainingWait) + fields.toMap())
        }
    }

    suspend fun awaitNetwork() {
        if (!network.state.value.ready && remainingWait > 0) {
            val started = elapsedMillis()
            log("network stabilization wait start remainingMs=$remainingWait")
            observe("NETWORK_STABILIZE_START")
            val ready = try { network.awaitReady(remainingWait) } finally {
                remainingWait = (remainingWait - (elapsedMillis() - started).coerceAtLeast(0)).coerceAtLeast(0)
            }
            if (!ready) remainingWait = 0
            log(if (ready) "network stabilization recovered" else "network stabilization timeout")
            observe(if (ready) "NETWORK_STABILIZE_RECOVERED" else "NETWORK_STABILIZE_TIMEOUT",
                "waitElapsedMs" to (elapsedMillis() - started).coerceAtLeast(0))
        }
        // Validation is a hint, not proof that the mail server is reachable (or unreachable).
        // If any default network exists, the actual connection determines the outcome.
        if (network.state.value.networkId == null) throw MailReceiverException(MonitoringFailureKind.DEVICE_OFFLINE)
    }

    suspend fun <T> read(operation: suspend () -> T): T {
        awaitNetwork()
        var attempt = 1
        while (true) {
            val before = network.state.value.changeVersion
            network.readStarted()
            try {
                val result = operation()
                network.readSucceeded()
                if (attempt > 1) observe("NETWORK_RETRY_END", "attempt" to attempt, "maxAttempts" to MAX_ATTEMPTS,
                    "retryNumber" to retriesUsed, "result" to "SUCCESS")
                return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                network.readFailed(error)
                val changed = network.state.value.changeVersion != before
                val transient = isTransientNetworkRetryCandidate(error)
                val candidate = retriesUsed < MAX_ATTEMPTS - 1 && remainingWait >= 3_000L && transient
                val retryWait = if (candidate) minOf(if (retriesUsed == 0) 3_000L else 5_000L, remainingWait) else 0L
                observe(if (candidate) "NETWORK_RETRY_ALLOWED" else "NETWORK_RETRY_SKIPPED",
                    "networkChanged" to changed, "retryUsed" to (retriesUsed > 0), "retryCandidate" to candidate,
                    "attempt" to attempt, "maxAttempts" to MAX_ATTEMPTS, "retryNumber" to retriesUsed,
                    "retryWaitMs" to retryWait, "reason" to when {
                        !transient -> "NON_TRANSIENT"
                        retriesUsed >= MAX_ATTEMPTS - 1 -> "MAX_RETRIES"
                        remainingWait < 3_000L -> "WAIT_BUDGET_EXHAUSTED"
                        else -> "TRANSIENT_NETWORK"
                    },
                    "category" to direct.kabu.alertkun.mail.common.MailConnectionSettings.classify(error).name,
                    "exceptionClass" to error.javaClass.simpleName)
                if (attempt > 1) observe("NETWORK_RETRY_END", "attempt" to attempt, "maxAttempts" to MAX_ATTEMPTS,
                    "retryNumber" to retriesUsed, "result" to "FAILURE",
                    "category" to direct.kabu.alertkun.mail.common.MailConnectionSettings.classify(error).name)
                if (!candidate) throw error
                retriesUsed++
                log("transient network limited retry retry=$retriesUsed waitMs=$retryWait networkChanged=$changed")
                val started = elapsedMillis()
                observe("NETWORK_RETRY_WAIT", "attempt" to attempt, "maxAttempts" to MAX_ATTEMPTS,
                    "retryNumber" to retriesUsed, "retryWaitMs" to retryWait)
                try { pause(retryWait) } finally {
                    remainingWait = (remainingWait - (elapsedMillis() - started).coerceAtLeast(retryWait)).coerceAtLeast(0)
                }
                awaitNetwork()
                attempt++
                observe("NETWORK_RETRY_START", "attempt" to attempt, "maxAttempts" to MAX_ATTEMPTS,
                    "retryNumber" to retriesUsed, "retryUsed" to true, "retryWaitMs" to retryWait,
                    "waitElapsedMs" to (elapsedMillis() - started).coerceAtLeast(retryWait),
                    "networkChanged" to (network.state.value.changeVersion != before))
            }
        }
    }

    companion object {
        const val WAIT_BUDGET_MILLIS = 8_000L
        const val MAX_ATTEMPTS = 3
        fun isTransientNetworkRetryCandidate(error: Throwable): Boolean {
            val chain = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).toList()
            if (chain.any { it is direct.kabu.alertkun.mail.common.LocalMailProcessingException }) return false
            val kind = chain.filterIsInstance<MailReceiverException>().firstOrNull()?.failureKind
            if (kind != null && kind !in setOf(MonitoringFailureKind.DNS_FAILURE, MonitoringFailureKind.CONNECTION_TIMEOUT,
                    MonitoringFailureKind.CONNECTION_REFUSED, MonitoringFailureKind.SERVER_UNAVAILABLE)) return false
            if (chain.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException || it is jakarta.mail.AuthenticationFailedException }) return false
            // Transport types only: generic I/O (for example local file failures) is not enough.
            return chain.any { it is java.net.UnknownHostException || it is java.net.SocketTimeoutException ||
                it is java.net.SocketException || it is java.io.EOFException }
        }
    }
}

/** Retry only read-only remote work before persistence/handoff; never replay the whole Coordinator. */
internal class NetworkStabilizingMailReceiver(
    private val delegate: MailReceiver,
    private val network: NetworkStabilization,
    private val hasDeletionConnection: Boolean = true,
) : MailReceiver {
    override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) =
        network.read { delegate.receive(knownIds, consume) }
    override suspend fun fetchNewMessages(): List<RemoteMail> = network.read { delegate.fetchNewMessages() }
    override suspend fun fetchNewMessages(isKnown: suspend (String, String) -> Boolean): List<RemoteMail> =
        network.read { delegate.fetchNewMessages(isKnown) }
    override suspend fun fetchBody(remoteMail: RemoteMail): String? = network.read { delegate.fetchBody(remoteMail) }
    override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
        if (hasDeletionConnection) network.awaitNetwork()
        // DELE/QUIT may already have reached the server. Never retry an uncertain deletion here.
        delegate.deleteProcessedMessages(lookup, now)
    }
}
