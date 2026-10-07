package direct.kabu.alertkun.monitoring

import kotlin.math.abs

internal data class ClockBaseline(
    val wallClockMillis: Long,
    val elapsedRealtimeMillis: Long,
)

internal object ClockChangePolicy {
    const val LARGE_ADJUSTMENT_MILLIS = 60_000L

    fun adjustmentMillis(previous: ClockBaseline, currentWallClockMillis: Long, currentElapsedRealtimeMillis: Long): Long {
        val expectedWallClock = previous.wallClockMillis + (currentElapsedRealtimeMillis - previous.elapsedRealtimeMillis)
        return currentWallClockMillis - expectedWallClock
    }

    fun requiresRebase(adjustmentMillis: Long): Boolean = abs(adjustmentMillis) >= LARGE_ADJUSTMENT_MILLIS
}

internal class ClockBaselineTracker {
    private var baseline: ClockBaseline? = null

    fun initialize(wallClockMillis: Long, elapsedRealtimeMillis: Long) {
        baseline = ClockBaseline(wallClockMillis, elapsedRealtimeMillis)
    }

    fun recordTimeChanged(wallClockMillis: Long, elapsedRealtimeMillis: Long): Boolean {
        val previous = baseline
        baseline = ClockBaseline(wallClockMillis, elapsedRealtimeMillis)
        return previous != null && ClockChangePolicy.requiresRebase(
            ClockChangePolicy.adjustmentMillis(previous, wallClockMillis, elapsedRealtimeMillis),
        )
    }
}
