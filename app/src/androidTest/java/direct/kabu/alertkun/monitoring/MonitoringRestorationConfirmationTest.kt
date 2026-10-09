package direct.kabu.alertkun.monitoring

import androidx.concurrent.futures.ResolvableFuture
import androidx.work.WorkInfo
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic Operations/WorkInfo: never mutates user settings or WorkManager reservations. */
class MonitoringRestorationConfirmationTest {
    private fun complete() = ResolvableFuture.create<Unit>().apply { set(Unit) }
    private fun info(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state,
        setOf("backup_generation_7", "backup_interval_15"), periodicityInfo = WorkInfo.PeriodicityInfo(900_000L, 900_000L))
    private suspend fun confirm(infos: List<WorkInfo>, enabled: Boolean = true) =
        MonitoringDevicePreparation.awaitPeriodicState(listOf(complete()), enabled, 7, 15, 50, query = { infos })
    private suspend fun mustTimeout(infos: List<WorkInfo>) {
        try { confirm(infos); fail("Invalid or missing backup must not restore monitoring") }
        catch (error: TimeoutException) { assertTrue(error.message!!.contains("Periodic backup confirmation timed out")) }
    }

    @Test fun enqueuedAndRunningPeriodicWorkConfirmRestoration() = runBlocking {
        confirm(listOf(info(WorkInfo.State.ENQUEUED)))
        confirm(listOf(info(WorkInfo.State.RUNNING)))
    }
    @Test fun offConfirmsCancelledPeriodicWork() = runBlocking { confirm(listOf(info(WorkInfo.State.CANCELLED)), false) }
    @Test fun cancelledFailedAndSucceededNeverConfirmRestoration() = runBlocking {
        for (state in listOf(WorkInfo.State.CANCELLED, WorkInfo.State.FAILED, WorkInfo.State.SUCCEEDED)) mustTimeout(listOf(info(state)))
    }
    @Test fun missingWorkNeverConfirmsRestoration() = runBlocking { mustTimeout(emptyList()) }
    @Test fun confirmationWaitsForEnqueueCompletionBeforeQueryingWork() = runBlocking {
        val operation = ResolvableFuture.create<Unit>()
        var queries = 0
        val confirmation = async {
            MonitoringDevicePreparation.awaitPeriodicState(listOf(operation), true, 7, 15, 2_000,
                query = { queries++; listOf(info(WorkInfo.State.ENQUEUED)) })
        }
        delay(30)
        assertFalse(confirmation.isCompleted)
        assertEquals(0, queries)
        operation.set(Unit)
        confirmation.await()
        assertEquals(1, queries)
    }
    @Test fun unfinishedOperationTimesOutWithoutQueryingWork() = runBlocking {
        try {
            MonitoringDevicePreparation.awaitPeriodicState(listOf(ResolvableFuture.create<Unit>()), true, 7, 15, 50,
                query = { error("Query before Operation completion") })
            fail("An unfinished Operation must time out")
        } catch (_: TimeoutException) { }
    }
    @Test fun failedOperationNeverConfirmsRestoration() = runBlocking {
        val operation = ResolvableFuture.create<Unit>().apply { setException(IllegalStateException("Fixture enqueue failure")) }
        try {
            MonitoringDevicePreparation.awaitPeriodicState(listOf(operation), true, 7, 15,
                query = { error("Query after Operation failure") })
            fail()
        } catch (_: ExecutionException) { }
    }
    @Test fun duplicateActiveUniqueWorkFails() = runBlocking {
        try { confirm(listOf(info(WorkInfo.State.ENQUEUED), info(WorkInfo.State.RUNNING))); fail() }
        catch (error: IllegalStateException) { assertEquals("Duplicate active Periodic backups", error.message) }
    }
    @Test fun staleGenerationAndOneTimeWorkNeverConfirmRestoration() = runBlocking {
        mustTimeout(listOf(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, setOf("backup_generation_6", "backup_interval_15"),
            periodicityInfo = WorkInfo.PeriodicityInfo(900_000L, 900_000L))))
        mustTimeout(listOf(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, setOf("backup_generation_7", "backup_interval_15"))))
    }
}
