package direct.kabu.alertkun.monitoring

import android.content.Context
import android.content.res.Configuration
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Called inside the existing executionMutex, after mail success was durably recorded. */
internal class MonitoringHealthReporter(
    private val context: Context,
    private val status: MonitoringStatusRepository = MonitoringStatusRepository(context),
    private val countAlerts: suspend (Long, Long) -> Int = { start, end ->
        AlertKunDatabase.getInstance(context).receivedMailDao().countTriggeredAlerts(start, end)
    },
) {
    suspend fun report(completedAt: Long, generation: Long): Boolean {
        try {
            val health = status.healthStatus()
            if (!MonitoringHealthNotificationPolicy.shouldNotify(completedAt, health)) return false
            val language = SettingsRepository(context).appLanguage.first()
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language.languageTag))
            })
            val notifier = MonitoringHealthNotifier(localized)
            if (!notifier.canPost()) return false
            val count = countAlerts(completedAt - MonitoringHealthNotificationPolicy.DAY_MILLIS, completedAt)
            val report = MonitoringHealthNotificationPolicy.report(completedAt, health, count) ?: return false
            if (!status.claimHealthNotification(completedAt, generation)) return false
            notifier.post(report.lastSuccessfulCheckAt, report.alertCount)
            monitoringDebugLog(context, "health notification posted completedMs=$completedAt alerts=$count generation=$generation")
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Health UI/Room count/notification failures must never turn successful mail into a failed check.
            monitoringDebugLog(context, "health notification unavailable type=${error.javaClass.simpleName}")
            return false
        }
    }
}
