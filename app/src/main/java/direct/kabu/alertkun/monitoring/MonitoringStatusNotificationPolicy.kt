package direct.kabu.alertkun.monitoring

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Channel metadata only; DND, volume and device-wide transient silence are not user channel settings. */
internal object MonitoringStatusNotificationPolicy {
    fun isSilent(importance: Int?, soundPresent: Boolean?, vibrationEnabled: Boolean?): Boolean = when (importance) {
        1, 2 -> true // Android IMPORTANCE_MIN/LOW do not audibly interrupt, even with a stored sound URI.
        3 -> soundPresent == false && vibrationEnabled == false
        else -> false // Missing/unspecified/high importance is not proven silent. NONE is blocked.
    }

    fun timestamp(time: Long, pattern: String, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(time))
}
