package direct.kabu.alertkun.ui.main

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Formats monitoring timestamps in the device's local time zone. */
internal fun formatMonitoringTimestamp(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String = SimpleDateFormat("yyyy/MM/dd HH:mm", locale).apply {
    this.timeZone = timeZone
}.format(Date(epochMillis))
