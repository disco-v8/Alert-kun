package direct.kabu.alertkun.monitoring

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObservationLogExporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportsAllRetainedFormatsWithoutChangingBytesOrIncludingOtherFiles() = runBlocking {
        val directory = temporary.newFolder("observability")
        val names = listOf("monitoring-observation-2026-10-19.log", "monitoring-observation-2026-10-20.log",
            "monitoring-observation-2026-10-20.1.log", "monitoring-observation-2026-10-20.12.log",
            "monitoring-observation.log") + (1..3).flatMap {
            listOf("monitoring-observation.$it.log", "monitoring-observation.log.$it")
        }
        val original = names.associateWith { name ->
            ("2026-10-20T20:01:00+09:00 CHECK_FAILURE accountId=fixture\n$name\n日本語\r\n".toByteArray() + byteArrayOf(0, -1))
                .also { File(directory, name).writeBytes(it) }
        }
        val modified = names.associateWith { File(directory, it).lastModified() }
        listOf("monitoring-observation-2026-10-20.log.retention-pending",
            "monitoring-observation-2026-10-20.log.retention-backup", "monitoring-observation.log.4",
            "monitoring-observation-2026-10-20.log.zip", "unrelated.log", "accounts.db", "settings.preferences_pb",
            "credentials.xml").forEach { File(directory, it).writeText("excluded fixture") }
        File(directory, "monitoring-observation-2026-10-18.log").mkdir()
        File(directory, "nested").mkdir()
        File(directory, "nested/monitoring-observation-2026-10-17.log").writeText("excluded nested fixture")
        File(directory.parentFile, "outside.db").writeText("excluded sibling fixture")
        val output = ByteArrayOutputStream()
        val result = ObservationLogExporter(directory).export { output }
        assertEquals(ObservationExportResult(ObservationExportStatus.SUCCESS, names.size, names.size), result)
        val actual = unzip(output.toByteArray())
        assertEquals(names.toSet(), actual.keys)
        original.forEach { (name, bytes) ->
            assertArrayEquals(bytes, actual.getValue(name))
            assertArrayEquals(bytes, File(directory, name).readBytes())
            assertEquals(modified.getValue(name), File(directory, name).lastModified())
        }
        // Retain synthetic evidence for independent PC ZIP extraction/hash verification.
        val evidence = File("build/reports/observation-export-verification").apply { mkdirs() }
        File(evidence, "logs.zip").writeBytes(output.toByteArray())
        val originals = File(evidence, "originals").apply { mkdirs() }
        original.forEach { (name, bytes) -> File(originals, name).writeBytes(bytes) }
    }

    @Test fun missingEmptyOrExcludedOnlyDirectoriesDoNotOpenDestinationOrCreateSources() = runBlocking {
        val missing = File(temporary.root, "missing")
        val empty = temporary.newFolder("empty")
        val excluded = temporary.newFolder("excluded")
        File(excluded, "monitoring-observation.log.retention-backup").writeText("fixture")
        for (directory in listOf(missing, empty, excluded)) {
            assertEquals(ObservationExportResult(ObservationExportStatus.NO_FILES, 0, 0),
                ObservationLogExporter(directory).export { fail("Must not open destination"); ByteArrayOutputStream() })
        }
        assertFalse(missing.exists())
    }

    @Test fun destinationOpenWriteAndCloseFailuresAreNeverSuccessAndKeepSources() = runBlocking {
        val directory = temporary.newFolder()
        val log = File(directory, "monitoring-observation.log").apply { writeText("synthetic diagnostic bytes") }
        val before = log.readBytes()
        val failures: List<() -> OutputStream> = listOf(
            { throw IOException("Synthetic open failure") },
            { object : OutputStream() { override fun write(value: Int) { throw IOException("Synthetic write failure") } } },
            { object : ByteArrayOutputStream() { override fun close() { throw IOException("Synthetic close failure") } } },
        )
        for (open in failures) {
            val result = ObservationLogExporter(directory).export(open)
            assertEquals(ObservationExportStatus.FAILURE, result.status)
            assertEquals(1, result.targetCount)
            assertArrayEquals(before, log.readBytes())
        }
    }

    @Test fun appendAndRetentionReplacementAfterSnapshotKeepTheOriginalPrefix() = runBlocking {
        val directory = temporary.newFolder()
        val log = File(directory, "monitoring-observation-2026-10-20.log").apply { writeText("original\n") }
        val replacement = File(directory, "monitoring-observation-2026-10-20.1.log").apply { writeText("retained\n") }
        val bytes = log.readBytes()
        val retained = replacement.readBytes()
        val output = ByteArrayOutputStream()
        val result = ObservationLogExporter(directory).export {
            log.appendText("later append\n")
            Files.move(replacement.toPath(), File(directory, "rotated.fixture").toPath())
            replacement.writeText("replacement inode\n")
            output
        }
        assertEquals(ObservationExportStatus.SUCCESS, result.status)
        val zip = unzip(output.toByteArray())
        assertArrayEquals(bytes, zip.getValue(log.name))
        assertArrayEquals(retained, zip.getValue(replacement.name))
        assertEquals("original\nlater append\n", log.readText())
        assertEquals("replacement inode\n", replacement.readText())
    }

    @Test fun partialWriteReportsFailureWithOnlyFullyWrittenEntriesCounted() = runBlocking {
        val directory = temporary.newFolder()
        val first = File(directory, "monitoring-observation-2026-10-19.log").apply { writeText("first fixture\n") }
        val bytes = ByteArray(64 * 1024).also { java.util.Random(1).nextBytes(it) }
        val second = File(directory, "monitoring-observation-2026-10-20.log").apply { writeBytes(bytes) }
        var written = 0
        val result = ObservationLogExporter(directory).export {
            object : OutputStream() {
                override fun write(value: Int) {
                    if (++written > 4096) throw IOException("Synthetic partial output failure")
                }
            }
        }
        assertEquals(ObservationExportResult(ObservationExportStatus.FAILURE, 2, 1), result)
        assertEquals("first fixture\n", first.readText())
        assertArrayEquals(bytes, second.readBytes())
    }

    @Test fun disappearingPathAfterOpeningStillCopiesOriginalButTruncationFails() = runBlocking {
        val directory = temporary.newFolder()
        val log = File(directory, "monitoring-observation.log").apply { writeText("fixture original") }
        val output = ByteArrayOutputStream()
        val result = ObservationLogExporter(directory).export { Files.delete(log.toPath()); output }
        assertEquals(ObservationExportStatus.SUCCESS, result.status)
        assertEquals("fixture original", unzip(output.toByteArray()).getValue(log.name).toString(Charsets.UTF_8))
        log.writeText("fixture original")
        val truncated = ObservationLogExporter(directory).export {
            RandomAccessFile(log, "rw").use { it.setLength(0) }
            ByteArrayOutputStream()
        }
        assertEquals(ObservationExportResult(ObservationExportStatus.FAILURE, 1, 0), truncated)
    }

    @Test fun symbolicLinksCannotExportUnrelatedPrivateFiles() = runBlocking {
        val directory = temporary.newFolder()
        val secret = temporary.newFile("not-a-log.fixture").apply { writeText("must never be exported") }
        val link = File(directory, "monitoring-observation.log").toPath()
        try { Files.createSymbolicLink(link, secret.toPath()) } catch (error: Exception) { assumeNoException(error) }
        assertEquals(ObservationExportStatus.NO_FILES,
            ObservationLogExporter(directory).export { fail("Must not open output"); ByteArrayOutputStream() }.status)
        val alias = File(temporary.root, "directory-link").toPath()
        Files.createSymbolicLink(alias, directory.toPath())
        assertEquals(ObservationExportStatus.FAILURE, ObservationLogExporter(alias.toFile()).export {
            fail("Must not open output"); ByteArrayOutputStream()
        }.status)
    }

    @Test fun cancellationPropagatesWithoutChangingSources() = runBlocking {
        val directory = temporary.newFolder()
        val log = File(directory, "monitoring-observation.log").apply { writeText("fixture") }
        try {
            ObservationLogExporter(directory).export { throw CancellationException("Synthetic cancellation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("fixture", log.readText())
    }

    @Test fun sizeLimitAndFull64MiBSnapshotUseStreamingIo() = runBlocking {
        val directory = temporary.newFolder()
        val log = File(directory, "monitoring-observation.log")
        val length = 64L * 1024 * 1024
        RandomAccessFile(log, "rw").use { it.setLength(length + 1) }
        val oversized = ObservationLogExporter(directory).export { fail("Do not open oversized output"); ByteArrayOutputStream() }
        assertEquals(ObservationExportStatus.FAILURE, oversized.status)
        RandomAccessFile(log, "rw").use { it.setLength(length) }
        val archive = temporary.newFile("full.zip")
        assertEquals(ObservationExportStatus.SUCCESS, ObservationLogExporter(directory).export { archive.outputStream() }.status)
        val expected = log.inputStream().use(::sha256)
        ZipInputStream(archive.inputStream()).use { zip ->
            assertEquals(log.name, zip.nextEntry.name)
            assertArrayEquals(expected, sha256(zip))
            assertNull(zip.nextEntry)
        }
        assertEquals(length, log.length())
    }

    private fun sha256(input: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
        }
    }
}
