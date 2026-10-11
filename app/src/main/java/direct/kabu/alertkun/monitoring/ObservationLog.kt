package direct.kabu.alertkun.monitoring

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

typealias ObservationSink = (String, Map<String, Any?>) -> Unit

/** Only explicitly selected metadata can reach the persistent log; never accept debug messages. */
internal object ObservationFormat {
    private val fields = setOf(
        "workId", "trigger", "generation", "inputGeneration", "currentGeneration", "oldGeneration",
        "newGeneration", "reason", "action", "priority", "originalPriority", "sentTime", "ttl",
        "messageIdHash", "collapseKeyHash", "uniqueName", "policy", "requestedWorkId", "retainedWorkId",
        "state", "requestedRetained", "snapshotAtMs", "runAttemptCount", "tags", "workKind",
        "monitoringEnabled", "statusEnabled", "backupInterval", "userInterval", "nowMs", "skipReason",
        "lastSuccessMs", "lastSuccessTrigger", "lastSuccessGeneration", "backupTargetMs", "windowStartMs",
        "windowEndMs", "lastStartedMs", "lastCompletedMs", "lastAttemptSucceeded", "enabledCondition",
        "generationCondition", "metadataCondition", "triggerCondition", "successGenerationCondition",
        "windowCondition", "attemptCondition", "completedCondition", "startedCondition", "decision",
        "result", "checkResult", "elapsedMs", "category", "exceptionClass", "accountId", "protocol", "operation",
        "defaultNetworkPresent", "networkChanged", "validated", "internet", "waitElapsedMs",
        "remainingWaitMs", "retryUsed", "retryCandidate", "interval", "revision", "completedMs", "stopped",
        "httpStatus", "retryNumber", "attempt", "maxAttempts", "retryWaitMs",
        "notificationId", "channelId", "importance", "silent", "soundPresent", "vibrationEnabled",
        "resultState", "notificationPhase", "count", "matched", "missing",
        "observationPhase", "processId", "elapsedRealtimeMs", "interactive", "deviceIdle", "powerSave",
        "batteryOptimizationExempt", "restrictBackgroundStatus", "metered", "wifi", "cellular", "vpn",
        "blockedCallbackSupported", "networkBlocked",
    )

    fun line(event: String, values: Map<String, Any?>, now: Long, zone: TimeZone): String {
        require(event.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { timeZone = zone }.format(Date(now))
        return buildString {
            append(timestamp).append(' ').append(event)
            values.filterKeys { it in fields }.forEach { (key, value) ->
                // Whitespace/control characters cannot create additional events or fields.
                val safe = when (value) {
                    null -> "null"
                    is Boolean, is Number -> value.toString()
                    is String -> value.take(512).replace(Regex("[^A-Za-z0-9_.:,/+-]"), "_")
                    else -> "omitted"
                }
                append(' ').append(key).append('=').append(safe)
            }
            append('\n')
        }
    }

    fun hash(value: String?): String? = value?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 255) }
    }
}

/** A closed append per event: no queue lost on process death, no fsync/wake/network/scheduling. */
internal class RotatingObservationLog(
    private val directory: File,
    private val maxBytes: Long = 1_048_576,
    private val fileCount: Int = 4,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
) {
    init { require(maxBytes > 0 && fileCount in 2..5) }

    @Synchronized
    fun record(event: String, values: Map<String, Any?> = emptyMap()): Boolean = try {
        val bytes = ObservationFormat.line(event, values, now(), zone()).toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) false else {
            check(directory.isDirectory || directory.mkdirs())
            val active = file(0)
            if (active.length() + bytes.size > maxBytes) {
                for (index in fileCount - 1 downTo 1) {
                    val destination = file(index)
                    check(!destination.exists() || destination.delete())
                    val source = file(index - 1)
                    check(!source.exists() || source.renameTo(destination))
                }
            }
            FileOutputStream(active, true).use { it.write(bytes) }
            true
        }
    } catch (_: Exception) {
        // Disk/format/rotation failures must never become a mail-check failure.
        false
    }

    private fun file(index: Int) = File(directory, if (index == 0) "monitoring-observation.log" else "monitoring-observation.$index.log")
}
