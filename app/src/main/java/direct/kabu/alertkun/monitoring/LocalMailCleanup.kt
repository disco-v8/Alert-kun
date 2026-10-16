package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.LocalMailRetention
import direct.kabu.alertkun.model.ServerDeletePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Only called by an actual check under executionMutex; never by receipt, enqueue or backup skip. */
internal class LocalMailCleanup(
    private val settings: SettingsRepository,
    private val purge: suspend (ServerDeletePolicy, Long) -> Int,
    private val observation: ObservationSink = { _, _ -> },
) {
    suspend fun run(checkStartedAt: Long, now: Long = checkStartedAt) {
        fun observe(event: String, values: Map<String, Any?>) { runCatching { observation(event, values) } }
        try {
            val policy = LocalMailRetention.policy(settings.settings.first().mailRetentionDays)
            if (policy == ServerDeletePolicy.NEVER || !settings.claimLocalMailCleanup(LocalMailRetention.hourStart(checkStartedAt))) return
            observe("LOCAL_MAIL_CLEANUP_START", mapOf("policy" to policy.name, "nowMs" to now))
            val count = purge(policy, now)
            observe("LOCAL_MAIL_CLEANUP_SUCCESS", mapOf("policy" to policy.name, "count" to count))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Local content housekeeping must not change mail-check success/failure attribution.
            observe("LOCAL_MAIL_CLEANUP_FAILURE", mapOf("exceptionClass" to error.javaClass.simpleName))
        }
    }
}
