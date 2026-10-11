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
        val writer = RotatingObservationLog(dir, maxBytes = 180, fileCount = 4)
        repeat(40) { assertTrue(writer.record("WORKER_END", mapOf("runAttemptCount" to it))) }
        val files = dir.listFiles()!!
        assertEquals(4, files.size)
        assertTrue(files.all { it.length() <= 180 })
        assertTrue(files.sumOf { it.length() } <= 720)
        assertTrue(File(dir, "monitoring-observation.log").readText().contains("runAttemptCount=39"))
        assertFalse(files.joinToString { it.readText() }.contains("runAttemptCount=0\n"))
    }

    @Test fun recreatedWriterAppendsToExistingLog() {
        val dir = temporary.newFolder()
        assertTrue(RotatingObservationLog(dir).record("CHECK_START"))
        assertTrue(RotatingObservationLog(dir).record("CHECK_SUCCESS"))
        assertEquals(listOf("CHECK_START", "CHECK_SUCCESS"), File(dir, "monitoring-observation.log").readLines().map { it.split(' ')[1] })
    }

    @Test fun concurrentWritesRemainCompleteSingleLines() {
        val dir = temporary.newFolder()
        val writer = RotatingObservationLog(dir)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 100).map { n -> executor.submit<Boolean> { writer.record("WORKER_START", mapOf("runAttemptCount" to n)) } }
            jobs.forEach { assertTrue(it.get()) }
        } finally { executor.shutdown() }
        val lines = File(dir, "monitoring-observation.log").readLines()
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
}
