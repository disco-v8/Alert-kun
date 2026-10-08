package direct.kabu.alertkun.ui.main

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Prefer FCM receipt; before the first receipt use confirmed Gateway registration. */
internal fun nextFcmCheckAt(lastReceivedAt: Long?, intervalMinutes: Int, registeredAt: Long? = null): Long? {
    val referenceAt = lastReceivedAt?.takeIf { it > 0 } ?: registeredAt?.takeIf { it > 0 } ?: return null
    if (intervalMinutes <= 0) return null
    val intervalMillis = intervalMinutes.toLong() * 60_000L
    return if (referenceAt > Long.MAX_VALUE - intervalMillis) null else referenceAt + intervalMillis
}

/** Formats monitoring timestamps in the device's local time zone. */
internal fun formatMonitoringTimestamp(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String = SimpleDateFormat("yyyy/MM/dd HH:mm", locale).apply {
    this.timeZone = timeZone
}.format(Date(epochMillis))
