package direct.kabu.alertkun.monitoring

/** Pure state holder; notification is requested only once per threshold crossing. */
class ConsecutiveFailureTracker {
    var currentCount: Int = 0
        private set
    private var thresholdReported = false

    fun onSuccess() {
        currentCount = 0
        thresholdReported = false
    }

    fun onFailure() { currentCount++ }
    fun thresholdReached(threshold: Int): Boolean = currentCount >= threshold
    fun consumeThresholdNotification(threshold: Int): Boolean {
        if (!thresholdReached(threshold) || thresholdReported) return false
        thresholdReported = true
        return true
    }
}
