package direct.kabu.alertkun.monitoring

/** Display evidence for one read attempt, never an input to connectivity or retry policy. */
internal data class CheckNetworkSnapshot(
    val networkId: String?,
    val version: Long,
    val cellularOnly: Boolean,
    val restricted: Boolean,
)

internal class CellularRestrictionEvidence {
    private var started: CheckNetworkSnapshot? = null
    private var failed: CheckNetworkSnapshot? = null
    private var failure: Throwable? = null

    fun readStarted(snapshot: CheckNetworkSnapshot) {
        started = snapshot
        failed = null
        failure = null
    }

    fun readFailed(snapshot: CheckNetworkSnapshot, error: Throwable) {
        val before = started
        if (before?.networkId != null && before.networkId == snapshot.networkId &&
            before.version == snapshot.version && before.cellularOnly && snapshot.cellularOnly &&
            snapshot.restricted && NetworkStabilization.isTransientNetworkRetryCandidate(error)) {
            failed = snapshot
            failure = error
        }
    }

    fun readSucceeded() { started = null; failed = null; failure = null }

    fun shouldAdvise(result: MailCheckResult, current: CheckNetworkSnapshot): Boolean {
        val snapshot = failed ?: return false
        return !result.success && result.error === failure && current == snapshot &&
            result.failureKind in setOf(MonitoringFailureKind.DNS_FAILURE,
                MonitoringFailureKind.CONNECTION_TIMEOUT, MonitoringFailureKind.CONNECTION_REFUSED,
                MonitoringFailureKind.SERVER_UNAVAILABLE)
    }
}
