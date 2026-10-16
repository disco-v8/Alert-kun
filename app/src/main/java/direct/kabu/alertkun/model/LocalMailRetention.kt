package direct.kabu.alertkun.model

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** Uses the existing deletion options. The clock starts at the first local receipt, not the mail Date header. */
object LocalMailRetention {
    // Preserve the existing day setting/key. 30 represents a calendar month, not 30 elapsed days.
    fun policy(days: Int): ServerDeletePolicy = when (days) {
        -1 -> ServerDeletePolicy.IMMEDIATELY
        0 -> ServerDeletePolicy.NEVER
        1 -> ServerDeletePolicy.AFTER_1_DAY
        7 -> ServerDeletePolicy.AFTER_1_WEEK
        10 -> ServerDeletePolicy.AFTER_10_DAYS
        15 -> ServerDeletePolicy.AFTER_15_DAYS
        30 -> ServerDeletePolicy.AFTER_1_MONTH
        else -> ServerDeletePolicy.NEVER
    }

    fun days(policy: ServerDeletePolicy): Int = when (policy) {
        ServerDeletePolicy.IMMEDIATELY -> -1
        ServerDeletePolicy.NEVER -> 0
        ServerDeletePolicy.AFTER_1_DAY -> 1
        ServerDeletePolicy.AFTER_1_WEEK -> 7
        ServerDeletePolicy.AFTER_10_DAYS -> 10
        ServerDeletePolicy.AFTER_15_DAYS -> 15
        ServerDeletePolicy.AFTER_1_MONTH -> 30
    }

    fun isExpired(policy: ServerDeletePolicy, receivedAt: Long, now: Long): Boolean {
        if (policy == ServerDeletePolicy.NEVER || receivedAt <= 0 || receivedAt > now) return false
        val due = try {
            when (policy) {
                ServerDeletePolicy.IMMEDIATELY -> receivedAt
                ServerDeletePolicy.AFTER_1_DAY -> Math.addExact(receivedAt, DAY)
                ServerDeletePolicy.AFTER_1_WEEK -> Math.addExact(receivedAt, 7 * DAY)
                ServerDeletePolicy.AFTER_10_DAYS -> Math.addExact(receivedAt, 10 * DAY)
                ServerDeletePolicy.AFTER_15_DAYS -> Math.addExact(receivedAt, 15 * DAY)
                ServerDeletePolicy.AFTER_1_MONTH -> GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = receivedAt
                    add(Calendar.MONTH, 1)
                }.timeInMillis
                ServerDeletePolicy.NEVER -> return false
            }
        } catch (_: RuntimeException) {
            return false
        }
        return due >= receivedAt && now >= due
    }

    /** Local wall-clock hour containing the actual check start; repeated DST hours remain distinct instants. */
    fun hourStart(now: Long, zone: TimeZone = TimeZone.getDefault()): Long = GregorianCalendar(zone).apply {
        timeInMillis = now
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY = 86_400_000L
}
