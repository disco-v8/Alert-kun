package direct.kabu.alertkun.alarm

/** Counts whole sound/vibration cycles; cancellation discards every remaining cycle. */
internal class FiniteAlarmSequence(count: Int) {
    var remainingCount: Int = count
        private set

    init { require(count in listOf(1, 3, 5)) }

    fun completeCycle(): Boolean {
        if (remainingCount > 0) remainingCount--
        return remainingCount > 0
    }

    fun cancel() { remainingCount = 0 }
}
