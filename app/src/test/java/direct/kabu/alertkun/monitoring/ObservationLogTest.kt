package direct.kabu.alertkun.monitoring

import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObservationLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun timestampIncludesMillisecondsAndCurrentOffset() {
        assertEquals("1970-01-01T09:00:00.123+09:00 FCM_RECEIVED ttl=300\n",
            ObservationFormat.line("FCM_RECEIVED", mapOf("ttl" to 300), 123, TimeZone.getTimeZone("Asia/Tokyo")))
        assertTrue(ObservationFormat.line("CHECK_START", emptyMap(), 0, TimeZone.getTimeZone("America/New_York"))
            .startsWith("1969-12-31T19:00:00.000-05:00"))
    }

    @Test fun untrustedWhitespaceCannotInjectEventsOrFields() {
        val line = ObservationFormat.line("WORKER_START", mapOf("tags" to "safe\nFCM_RECEIVED password=secret\r\t"), 0, TimeZone.getTimeZone("UTC"))
        assertEquals(1, line.count { it == '\n' })
        assertFalse(line.contains(" password="))
        assertFalse(line.contains('\r'))
        assertFalse(line.contains('\t'))
    }

    @Test fun secretFieldsAndObjectsAreNotSerialized() {
        val secrets = listOf("password", "MasterPwd", "token", "fcmtoken", "Authorization", "body", "subject", "host", "username", "credentialKey")
        val values = secrets.associateWith { "forbidden-secret" } + ("exceptionClass" to IllegalStateException("forbidden-secret"))
        val line = ObservationFormat.line("CHECK_FAILURE", values, 0, TimeZone.getTimeZone("UTC"))
        assertFalse(line.contains("forbidden-secret"))
        secrets.forEach { assertFalse(line.contains("$it=")) }
        assertTrue(line.contains("exceptionClass=omitted"))
    }

    @Test fun messageIdsAreHashedDeterministically() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ObservationFormat.hash("abc"))
        assertNull(ObservationFormat.hash(null))
    }

    @Test fun restrictionMetadataRetainsUnknownsAndWorkCorrelationWithoutNetworkIdentity() {
        val line = ObservationFormat.line("DEVICE_NETWORK_STATE", mapOf(
            "workId" to "fixture", "observationPhase" to "CHECK_END", "elapsedRealtimeMs" to 123L,
            "processId" to 456,
            "restrictBackgroundStatus" to 3, "interactive" to false, "deviceIdle" to true,
            "powerSave" to null, "cellular" to true, "wifi" to false, "networkBlocked" to true,
            "ssid" to "private-network", "ipAddress" to "private-address", "callerPackage" to "untrusted"),
            0, TimeZone.getTimeZone("UTC"))
        for (field in listOf("workId=fixture", "processId=456", "restrictBackgroundStatus=3", "powerSave=null", "cellular=true", "networkBlocked=true")) {
            assertTrue(line.contains(field))
        }
        assertFalse(line.contains("private-"))
        assertFalse(line.contains("callerPackage="))
    }

    @Test fun notificationResultMetadataIsRetainedWithoutDisplayContent() {
        for (state in listOf("NORMAL", "RULE_MATCH")) {
            val line = ObservationFormat.line("MONITORING_NOTIFICATION_UPDATE", mapOf(
                "notificationId" to 6004, "resultState" to state, "notificationPhase" to "CHECK_RESULT",
                "workId" to "fixture-work", "generation" to 42L, "trigger" to "FCM",
                "subject" to "private-subject", "mailFrom" to "private-sender", "ruleName" to "private-rule",
                "body" to "private-body"), 0L, TimeZone.getTimeZone("UTC"))
            assertTrue(line.contains("resultState=$state")); assertTrue(line.contains("notificationPhase=CHECK_RESULT"))
            assertTrue(line.contains("workId=fixture-work")); assertTrue(line.contains("generation=42"))
            assertEquals(1, line.count { it == '\n' }); assertFalse(line.contains("private-"))
        }
    }

    @Test fun rotationRetainsNewestEventsWithinTotalBound() {
        val dir = temporary.newFolder()
        val writer = RotatingObservationLog(dir, maxBytes = 180, maxTotalBytes = 720)
        repeat(40) { assertTrue(writer.record("WORKER_END", mapOf("runAttemptCount" to it))) }
        val files = dir.listFiles()!!
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.length() <= 180 })
        assertTrue(files.sumOf { it.length() } <= 720)
        assertTrue(files.any { it.readText().contains("runAttemptCount=39") })
        assertFalse(files.joinToString { it.readText() }.contains("runAttemptCount=0\n"))
    }

    @Test fun recreatedWriterAppendsToExistingLog() {
        val dir = temporary.newFolder()
        assertTrue(RotatingObservationLog(dir).record("CHECK_START"))
        assertTrue(RotatingObservationLog(dir).record("CHECK_SUCCESS"))
        assertEquals(listOf("CHECK_START", "CHECK_SUCCESS"), dir.listFiles()!!.flatMap { it.readLines() }.map { it.split(' ')[1] })
    }

    @Test fun concurrentWritesRemainCompleteSingleLines() {
        val dir = temporary.newFolder()
        val writer = RotatingObservationLog(dir)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 100).map { n -> executor.submit<Boolean> { writer.record("WORKER_START", mapOf("runAttemptCount" to n)) } }
            jobs.forEach { assertTrue(it.get()) }
        } finally { executor.shutdown() }
        val lines = dir.listFiles()!!.flatMap { it.readLines() }
        assertEquals(100, lines.size)
        assertEquals(100, lines.map { it.substringAfter("runAttemptCount=") }.toSet().size)
    }

    @Test fun storageFailureDoesNotThrowOrCreateUnboundedFiles() {
        val file = temporary.newFile()
        assertFalse(RotatingObservationLog(file).record("CHECK_START"))
        assertFalse(RotatingObservationLog(temporary.newFolder(), maxBytes = 10).record("CHECK_START"))
    }

    @Test fun invalidEventCannotInjectLine() {
        assertFalse(RotatingObservationLog(temporary.newFolder()).record("CHECK_START\nCHECK_SUCCESS"))
    }

    @Test fun callbackFieldsAreAllowedButLinkAndNetworkSecretsAreRejected() {
        val line = ObservationFormat.line("NETWORK_CALLBACK", mapOf(
            "callbackType" to "LOST", "networkHandle" to 123L, "transport" to "CELLULAR",
            "internet" to true, "validated" to false, "metered" to true, "networkBlocked" to true,
            "isDefaultNetwork" to false, "trackedDefault" to true, "capabilitySource" to "LAST_KNOWN",
            "ssid" to "private-ssid", "bssid" to "private-bssid", "ipAddress" to "private-ip",
            "dnsServers" to "private-dns", "linkProperties" to "private-links", "Bearer" to "private-bearer"),
            0, TimeZone.getTimeZone("UTC"))
        assertTrue(line.contains("callbackType=LOST")); assertTrue(line.contains("networkHandle=123"))
        assertTrue(line.contains("capabilitySource=LAST_KNOWN")); assertFalse(line.contains("private-"))
    }

    @Test fun rollingRetentionKeepsExactBoundaryAndPrunesOnlyExpiredLines() {
        val dir = temporary.newFolder()
        val utc = TimeZone.getTimeZone("UTC")
        val base = 1_700_000_000_000L
        var time = base + RotatingObservationLog.RETENTION_MILLIS
        val legacy = File(dir, "monitoring-observation.log")
        legacy.writeText(listOf(base - 1, base, base + 1).mapIndexed { index, stamp ->
            ObservationFormat.line("CHECK_START", mapOf("count" to index), stamp, utc)
        }.joinToString(""))
        RotatingObservationLog(dir, now = { time }, zone = { utc })
        assertFalse(legacy.readText().contains("count=0"))
        assertTrue(legacy.readText().contains("count=1"))
        assertTrue(legacy.readText().contains("count=2"))
        time++
        assertTrue(RotatingObservationLog(dir, now = { time }, zone = { utc }).record("CHECK_SUCCESS"))
        assertFalse(legacy.readText().contains("count=1"))
        assertTrue(legacy.readText().contains("count=2"))
    }

    @Test fun expiryDeletesWholeOldFileButNotFreshLegacyFormats() {
        val dir = temporary.newFolder()
        val time = 1_800_000_000_000L
        val utc = TimeZone.getTimeZone("UTC")
        val old = File(dir, "monitoring-observation-2020-01-01.log")
        old.writeText(ObservationFormat.line("CHECK_START", emptyMap(), time - RotatingObservationLog.RETENTION_MILLIS - 1, utc))
        val legacy = listOf("monitoring-observation.log", "monitoring-observation.1.log", "monitoring-observation.log.2")
            .map { File(dir, it).apply { writeText(ObservationFormat.line("CHECK_START", emptyMap(), time - 1000, utc)) } }
        val original = legacy.map { it.readBytes().toList() }
        assertTrue(RotatingObservationLog(dir, now = { time }, zone = { utc }).record("CHECK_SUCCESS"))
        assertFalse(old.exists())
        assertEquals(original, legacy.map { it.readBytes().toList() })
    }

    @Test fun localMidnightSelectsNewDailyFile() {
        val dir = temporary.newFolder()
        var time = java.time.Instant.parse("2026-10-15T14:59:59Z").toEpochMilli()
        val writer = RotatingObservationLog(dir, now = { time }, zone = { TimeZone.getTimeZone("Asia/Tokyo") })
        assertTrue(writer.record("CHECK_START"))
        time += 2000
        assertTrue(writer.record("CHECK_SUCCESS"))
        assertTrue(File(dir, "monitoring-observation-2026-10-15.log").readText().contains("CHECK_START"))
        assertTrue(File(dir, "monitoring-observation-2026-10-16.log").readText().contains("CHECK_SUCCESS"))
    }

    @Test fun absolute64MiBLimitDeletesOldestFileFirst() {
        val dir = temporary.newFolder()
        val time = 1_800_000_000_000L
        val utc = TimeZone.getTimeZone("UTC")
        fun fixture(name: String, age: Long, bytes: Long) = File(dir, name).apply {
            writeText(ObservationFormat.line("CHECK_START", emptyMap(), time - age, utc))
            java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        }
        val oldest = fixture("monitoring-observation.2.log", 2000, 33L * 1_048_576)
        val newer = fixture("monitoring-observation.1.log", 1000, 32L * 1_048_576)
        assertTrue(RotatingObservationLog(dir, now = { time }, zone = { utc }).record("CHECK_SUCCESS"))
        assertFalse(oldest.exists()); assertTrue(newer.exists())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 64L * 1_048_576)
    }

    @Test fun capacityNeverDeletesActiveFileAndExpiryRunsBeforeCapacity() {
        val dir = temporary.newFolder()
        var time = 1_800_000_000_000L
        val utc = TimeZone.getTimeZone("UTC")
        val writer = RotatingObservationLog(dir, maxBytes = 160, maxTotalBytes = 320, now = { time }, zone = { utc })
        repeat(20) { time++; assertTrue(writer.record("CHECK_SUCCESS", mapOf("count" to it))) }
        assertTrue(dir.listFiles()!!.any { it.readText().contains("count=19") })
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 320)
        assertFalse(dir.listFiles()!!.any { it.readText().contains("count=0\n") })
        val expired = File(dir, "monitoring-observation.log")
        expired.writeText(ObservationFormat.line("CHECK_START", emptyMap(), time - RotatingObservationLog.RETENTION_MILLIS - 1, utc))
        val retained = dir.listFiles()!!.filter { it != expired }.map { it.name }
        RotatingObservationLog(dir, maxBytes = 160, maxTotalBytes = 320, now = { time }, zone = { utc })
        assertFalse(expired.exists())
        assertTrue(retained.all { File(dir, it).exists() })
    }

    @Test fun cleanupFailureDoesNotPreventAppendingOrEscapeToMonitoring() {
        val dir = temporary.newFolder()
        val time = 1_800_000_000_000L
        val expired = File(dir, "monitoring-observation.log")
        expired.writeText(ObservationFormat.line("CHECK_START", emptyMap(), time - RotatingObservationLog.RETENTION_MILLIS - 1, TimeZone.getTimeZone("UTC")))
        val writer = RotatingObservationLog(dir, now = { time }, deleteFile = { false })
        assertTrue(writer.record("CHECK_SUCCESS")); assertTrue(expired.exists())
        assertTrue(dir.listFiles()!!.any { it.readText().contains("CHECK_SUCCESS") })
    }

    @Test fun capacitySkipsActiveEvenWhenItsTimestampIsOldest() {
        val dir = temporary.newFolder()
        val time = 1_800_000_000_000L
        val utc = TimeZone.getTimeZone("UTC")
        val other = File(dir, "monitoring-observation.log")
        other.writeText(ObservationFormat.line("CHECK_START", mapOf("tags" to "x".repeat(180)), time + 1000, utc))
        val deleted = mutableListOf<String>()
        val writer = RotatingObservationLog(dir, maxBytes = 512, maxTotalBytes = 512,
            now = { time }, zone = { utc }, deleteFile = { deleted += it.name; it.delete() })
        repeat(8) { assertTrue(writer.record("CHECK_SUCCESS", mapOf("count" to it))) }
        val daily = dir.listFiles()!!.single()
        assertTrue(daily.readText().contains("count=0\n"))
        assertTrue(daily.readText().contains("count=7\n"))
        assertEquals(listOf(other.name), deleted)
    }

    @Test fun interruptedRetentionReplacementRestoresOriginalBeforeAppending() {
        val dir = temporary.newFolder()
        val time = 1_800_000_000_000L
        val backup = File(dir, "monitoring-observation.log.retention-backup")
        val text = ObservationFormat.line("CHECK_START", emptyMap(), time - 1000, TimeZone.getTimeZone("UTC"))
        backup.writeText(text)
        assertTrue(RotatingObservationLog(dir, now = { time }).record("CHECK_SUCCESS"))
        assertEquals(text, File(dir, "monitoring-observation.log").readText())
        assertFalse(backup.exists())
    }

    @Test fun sameDayClockRollbackStillExpiresByEventTimeAfterProcessRestart() {
        val dir = temporary.newFolder()
        val utc = TimeZone.getTimeZone("UTC")
        val base = java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        var time = base
        val writer = RotatingObservationLog(dir, now = { time }, zone = { utc })
        assertTrue(writer.record("CHECK_START", mapOf("count" to 0)))
        time = base - 3_600_000
        assertTrue(writer.record("CHECK_START", mapOf("count" to 1)))
        assertEquals(2, dir.listFiles()!!.size)
        time += RotatingObservationLog.RETENTION_MILLIS + 1
        RotatingObservationLog(dir, now = { time }, zone = { utc })
        val remaining = dir.listFiles()!!.joinToString("") { it.readText() }
        assertTrue(remaining.contains("count=0")); assertFalse(remaining.contains("count=1"))
    }

    @Test fun cleanupIsCoalescedAcrossCallbackBurstsAndRetriedOnHourlyWrite() {
        val dir = temporary.newFolder()
        var time = 1_800_000_000_000L
        val utc = TimeZone.getTimeZone("UTC")
        File(dir, "monitoring-observation.log").writeText(ObservationFormat.line("CHECK_START", emptyMap(),
            time - RotatingObservationLog.RETENTION_MILLIS - 1, utc))
        var deletes = 0
        val writer = RotatingObservationLog(dir, now = { time }, zone = { utc }, deleteFile = { deletes++; false })
        assertEquals(1, deletes)
        repeat(50) { time++; assertTrue(writer.record("NETWORK_CALLBACK", mapOf("callbackType" to "AVAILABLE"))) }
        assertEquals(1, deletes)
        time += 3_600_000
        assertTrue(writer.record("CHECK_SUCCESS"))
        assertEquals(2, deletes)
    }
}
