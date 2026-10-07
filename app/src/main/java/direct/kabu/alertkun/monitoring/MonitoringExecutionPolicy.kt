package direct.kabu.alertkun.monitoring

import java.util.Calendar
import java.util.TimeZone

/** Pure timing and generation rules used after the worker has acquired its execution mutex. */
object MonitoringExecutionPolicy {
    /**
     * Establishes an interval target on a local minute boundary.
     * Seconds 0 through 30 round down; 30 seconds plus any millisecond rounds up.
     */
    fun calculateNextTarget(
        startedAtEpochMillis: Long,
        intervalMinutes: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        require(intervalMinutes in 1..1500)
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = startedAtEpochMillis
            val roundUp = get(Calendar.SECOND) > 30 ||
                (get(Calendar.SECOND) == 30 && get(Calendar.MILLISECOND) > 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (roundUp) add(Calendar.MINUTE, 1)
            add(Calendar.MINUTE, intervalMinutes)
        }.timeInMillis
    }

    fun delayMillis(targetAtEpochMillis: Long, nowEpochMillis: Long): Long = (targetAtEpochMillis - nowEpochMillis).coerceAtLeast(0)
    fun shouldSkipScheduled(nowEpochMillis: Long, lastStartedAtEpochMillis: Long?, lastCompletedAtEpochMillis: Long?, intervalMinutes: Int): Boolean =
        lastStartedAtEpochMillis != null && lastCompletedAtEpochMillis != null && nowEpochMillis < calculateNextTarget(lastStartedAtEpochMillis, intervalMinutes)
    fun mayReschedule(monitoringEnabled: Boolean, currentGeneration: Long, workerGeneration: Long): Boolean =
        monitoringEnabled && currentGeneration == workerGeneration
}
