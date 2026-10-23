package direct.kabu.alertkun.monitoring

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ObservationExportStatus { SUCCESS, NO_FILES, FAILURE }

internal data class ObservationExportResult(
    val status: ObservationExportStatus,
    val targetCount: Int,
    val exportedCount: Int,
)

/** Read-only, per-file byte snapshots. Never acquires the monitoring/logger locks or rewrites logs. */
internal class ObservationLogExporter(private val directory: File) {
    private data class Source(val name: String, val channel: FileChannel, var size: Long = 0)

    suspend fun export(openOutput: () -> OutputStream): ObservationExportResult = withContext(Dispatchers.IO) {
        val sources = mutableListOf<Source>()
        var targetCount = 0
        var exportedCount = 0
        try {
            currentCoroutineContext().ensureActive()
            // Only the caller's fixed observability directory; never follow directory/file links.
            if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
                return@withContext ObservationExportResult(ObservationExportStatus.NO_FILES, 0, 0)
            }
            // Android may expose filesDir through a trusted system alias (/data/user/0).
            // Reject a link at observability itself without rejecting those parent aliases.
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
                throw IOException("Invalid observation directory")
            }
            val files = (directory.listFiles() ?: throw IOException("Cannot list observation logs"))
                .filter { isLogName(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
                .sortedBy { it.name }
            targetCount = files.size
            if (files.isEmpty()) return@withContext ObservationExportResult(ObservationExportStatus.NO_FILES, 0, 0)
            var totalBytes = 0L
            for (file in files) {
                currentCoroutineContext().ensureActive()
                val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!before.isRegularFile) throw IOException("Observation file changed")
                val channel = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS)
                // Register immediately so every failure/cancellation closes already opened descriptors.
                val source = Source(file.name, channel)
                sources += source
                source.size = channel.size()
                val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!after.isRegularFile || before.fileKey() != after.fileKey()) {
                    throw IOException("Observation file replaced while opening")
                }
                if (source.size > MAX_BYTES - totalBytes) throw IOException("Observation snapshot exceeds size limit")
                totalBytes += source.size
            }
            // Open all sources before the destination. Later append bytes are excluded; an open
            // descriptor still reads the original inode after retention rename/replacement/deletion.
            val buffer = ByteBuffer.allocate(16 * 1024)
            ZipOutputStream(openOutput()).use { zip ->
                for (source in sources) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(source.name))
                    var remaining = source.size
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        buffer.clear()
                        buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                        val count = source.channel.read(buffer)
                        if (count <= 0) throw EOFException("Observation snapshot shortened")
                        zip.write(buffer.array(), 0, count)
                        remaining -= count
                    }
                    zip.closeEntry()
                    exportedCount++
                }
            }
            sources.forEach { it.channel.close() }
            ObservationExportResult(ObservationExportStatus.SUCCESS, targetCount, exportedCount)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No exception messages, output URI or source contents enter logs or UI.
            ObservationExportResult(ObservationExportStatus.FAILURE, targetCount, exportedCount)
        } finally {
            sources.forEach { runCatching { it.channel.close() } }
        }
    }

    private fun isLogName(name: String): Boolean = name == "monitoring-observation.log" ||
        name.matches(Regex("monitoring-observation(?:\\.[1-3]\\.log|\\.log\\.[1-3])")) ||
        name.matches(Regex("monitoring-observation-\\d{4}-\\d{2}-\\d{2}(?:\\.\\d+)?\\.log"))

    private companion object { const val MAX_BYTES = 64L * 1024 * 1024 }
}
