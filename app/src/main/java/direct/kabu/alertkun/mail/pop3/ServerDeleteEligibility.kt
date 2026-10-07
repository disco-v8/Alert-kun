package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.model.ServerDeletePolicy
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** No remote Date header is trusted. Elapsed days and calendar months start at local first save. */
object ServerDeleteEligibility {
    fun eligible(policy: ServerDeletePolicy, accountId: String, uidl: String, mail: ReceivedMail?, now: Long): Boolean {
        if (policy == ServerDeletePolicy.NEVER || mail == null || accountId.isBlank() || uidl.isBlank()) return false
        if (mail.accountId != accountId || mail.remoteMessageId != uidl || mail.processingState != MailProcessingState.PROCESSED) return false
        val first = mail.receivedAtEpochMillis
        if (first <= 0 || first > now) return false
        val due = try {
            when (policy) {
                ServerDeletePolicy.IMMEDIATELY -> first
                ServerDeletePolicy.AFTER_1_DAY -> Math.addExact(first, DAY)
                ServerDeletePolicy.AFTER_1_WEEK -> Math.addExact(first, 7 * DAY)
                ServerDeletePolicy.AFTER_10_DAYS -> Math.addExact(first, 10 * DAY)
                ServerDeletePolicy.AFTER_15_DAYS -> Math.addExact(first, 15 * DAY)
                ServerDeletePolicy.AFTER_1_MONTH -> GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = first
                    add(Calendar.MONTH, 1)
                }.timeInMillis
                ServerDeletePolicy.NEVER -> return false
            }
        } catch (_: RuntimeException) {
            return false
        }
        return due >= first && now >= due
    }

    private const val DAY = 86_400_000L
}
