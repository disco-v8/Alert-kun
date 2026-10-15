package direct.kabu.alertkun.monitoring

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.text.ParsePosition
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
        "callbackType", "networkHandle", "transport", "isDefaultNetwork", "trackedDefault",
        "capabilitySource",
        "alarmToken", "alarmMode", "soundType", "requestedCount", "completedCount", "usage", "focusGranted",
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
    private val maxBytes: Long = 8L * 1_048_576,
    private val fileCount: Int = 4,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
    private val maxTotalBytes: Long = 64L * 1_048_576,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    private data class Stored(val file: File, var size: Long, var oldest: Long)
    private val stored = linkedMapOf<File, Stored>()
    private var initialized = false
    private var active: File? = null
    private var currentDay: String? = null
    private var nextCleanup = Long.MIN_VALUE
    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
    private val legacyNames = buildSet {
        add("monitoring-observation.log")
        for (index in 1 until fileCount) {
            add("monitoring-observation.$index.log")
            add("monitoring-observation.log.$index")
        }
    }

    init {
        require(maxBytes > 0 && maxTotalBytes >= maxBytes && fileCount in 2..5)
        runCatching { initialize(now()) }
    }

    @Synchronized
    fun record(event: String, values: Map<String, Any?> = emptyMap()): Boolean = try {
        val time = now()
        val timeZone = zone()
        val bytes = ObservationFormat.line(event, values, time, timeZone).toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) false else {
            if (!initialized) initialize(time)
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { this.timeZone = timeZone }.format(Date(time))
            val dateChanged = currentDay != day
            if (dateChanged || active == null || time < (stored[active]?.oldest ?: time) ||
                (stored[active]?.size ?: 0) + bytes.size > maxBytes) {
                val prefix = "monitoring-observation-$day"
                val parts = stored.keys.mapNotNull { file ->
                    when {
                        file.name == "$prefix.log" -> 0 to file
                        file.name.matches(Regex("${Regex.escape(prefix)}\\.\\d+\\.log")) ->
                            file.name.removePrefix("$prefix.").removeSuffix(".log").toIntOrNull()?.let { it to file }
                        else -> null
                    }
                }
                val last = parts.maxByOrNull { it.first }
                active = if (last != null && time >= stored.getValue(last.second).oldest &&
                    stored.getValue(last.second).size + bytes.size <= maxBytes) last.second else {
                    val index = (last?.first ?: -1) + 1
                    File(directory, if (index == 0) "$prefix.log" else "$prefix.$index.log")
                }
                currentDay = day
                stored.getOrPut(checkNotNull(active)) { Stored(checkNotNull(active), 0, time) }
            }
            if (dateChanged || time >= nextCleanup || stored.values.sumOf { it.size } + bytes.size > maxTotalBytes) cleanup(time)
            trimCapacity(bytes.size.toLong())
            check(stored.values.sumOf { it.size } + bytes.size <= maxTotalBytes)
            FileOutputStream(checkNotNull(active), true).use { it.write(bytes) }
            stored.getValue(checkNotNull(active)).apply { size += bytes.size; oldest = minOf(oldest, time) }
            true
        }
    } catch (_: Exception) {
        // Disk/format/rotation failures must never become a mail-check failure.
        false
    }

    private fun initialize(time: Long) {
        check(directory.isDirectory || directory.mkdirs())
        // Recover a retention replacement interrupted between the two renames.
        directory.listFiles()?.filter { it.name.endsWith(".retention-backup") &&
            isLog(it.name.removeSuffix(".retention-backup")) }?.forEach { backup ->
            val original = File(directory, backup.name.removeSuffix(".retention-backup"))
            if (!original.exists()) check(backup.renameTo(original)) else runCatching { deleteFile(backup) }
        }
        directory.listFiles()?.filter { it.name.endsWith(".retention-pending") &&
            isLog(it.name.removeSuffix(".retention-pending")) }?.forEach { runCatching { deleteFile(it) } }
        stored.clear()
        directory.listFiles()?.filter { it.isFile && (isLog(it.name) || isRetentionAuxiliary(it.name)) }?.forEach { file ->
            val first = file.bufferedReader().useLines { lines ->
                // New daily parts never append a time earlier than their first row. Legacy
                // logs have no such invariant, so inspect their small retained rotation too.
                if (file.name in legacyNames) lines.mapNotNull(::lineTime).minOrNull()
                else lines.mapNotNull(::lineTime).firstOrNull()
            }
            stored[file] = Stored(file, file.length(), first ?: file.lastModified())
        }
        initialized = true
        currentDay = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone() }.format(Date(time))
        cleanup(time)
    }

    private fun isLog(name: String) = name in legacyNames ||
        name.matches(Regex("monitoring-observation-\\d{4}-\\d{2}-\\d{2}(?:\\.\\d+)?\\.log"))

    private fun isRetentionAuxiliary(name: String) = listOf(".retention-pending", ".retention-backup")
        .any { name.endsWith(it) && isLog(name.removeSuffix(it)) }

    private fun lineTime(line: String): Long? = timestamp.parse(line.substringBefore(' '), ParsePosition(0))?.time

    private fun cleanup(time: Long) {
        val cutoff = time - RETENTION_MILLIS
        stored.values.toList().filter { isRetentionAuxiliary(it.file.name) }.forEach { entry ->
            runCatching { check(deleteFile(entry.file)); stored.remove(entry.file) }
        }
        stored.values.toList().filter { isLog(it.file.name) && it.oldest < cutoff }.forEach { entry ->
            runCatching { prune(entry, cutoff) }
        }
        // Coalesce boundary-file compaction and cleanup failures. No extra scheduler,
        // and no repeated read/rewrite for each callback in a burst after day 15.
        nextCleanup = time + 3_600_000L
        trimCapacity(0)
    }

    private fun prune(entry: Stored, cutoff: Long) {
        val pending = File(directory, entry.file.name + ".retention-pending")
        val backup = File(directory, entry.file.name + ".retention-backup")
        var oldestKept = Long.MAX_VALUE
        var pendingBytes = 0L
        try {
            pending.outputStream().buffered().use { output ->
                entry.file.bufferedReader().useLines { lines -> lines.forEach { line ->
                    val time = lineTime(line)
                    if (time == null || time >= cutoff) {
                        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                        // Include staging bytes in the absolute bound, not just published logs.
                        check(stored.values.sumOf { it.size } + pendingBytes + bytes.size <= maxTotalBytes)
                        output.write(bytes)
                        pendingBytes += bytes.size
                        oldestKept = minOf(oldestKept, time ?: entry.file.lastModified())
                    }
                } }
            }
            if (pending.length() == 0L && entry.file != active) {
                check(deleteFile(entry.file))
                stored.remove(entry.file)
            } else {
                check(!backup.exists())
                check(entry.file.renameTo(backup))
                if (!pending.renameTo(entry.file)) {
                    check(backup.renameTo(entry.file))
                    error("Observation retention replacement failed")
                }
                val oldSize = entry.size
                val oldTime = entry.oldest
                entry.size = entry.file.length()
                entry.oldest = oldestKept
                if (!deleteFile(backup)) stored[backup] = Stored(backup, oldSize, oldTime)
            }
        } finally {
            if (pending.exists() && !pending.delete()) stored[pending] = Stored(pending, pending.length(), entry.oldest)
        }
    }

    private fun trimCapacity(incoming: Long) {
        var total = stored.values.sumOf { it.size }
        if (total + incoming <= maxTotalBytes) return
        for (entry in stored.values.sortedWith(compareBy<Stored> { it.oldest }.thenBy { it.file.name })) {
            if (total + incoming <= maxTotalBytes) break
            if (entry.file != active && runCatching { deleteFile(entry.file) }.getOrDefault(false)) {
                total -= entry.size
                stored.remove(entry.file)
            }
        }
    }

    companion object {
        const val RETENTION_MILLIS = 15L * 24 * 60 * 60 * 1_000
    }
}
