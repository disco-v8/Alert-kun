package direct.kabu.alertkun.monitoring

import android.content.Context
import android.util.Log
import androidx.work.Operation
import androidx.work.WorkManager
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Always enabled in debug/release. No UI or scheduler; only observes existing execution. */
internal object MonitoringObservation {
    @Volatile private var writer: RotatingObservationLog? = null
    @Volatile var generation: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(context: Context, event: String, vararg fields: Pair<String, Any?>) {
        try {
            val log = writer ?: synchronized(this) {
                writer ?: RotatingObservationLog(File(context.applicationContext.filesDir, "observability")).also { writer = it }
            }
            if (!log.record(event, fields.toMap())) Log.w("MonitoringObservation", "observation append unavailable")
        } catch (_: Exception) {
            Log.w("MonitoringObservation", "observation storage unavailable")
        }
    }

    fun sink(context: Context, workId: String, trigger: MailCheckTrigger, generation: Long): ObservationSink = { event, values ->
        record(context, event, "workId" to workId, "trigger" to trigger.name, "generation" to generation, *values.toList().toTypedArray())
    }

    fun enqueueCommitted(context: Context, operation: Operation, workId: String, name: String, trigger: MailCheckTrigger, policy: String) {
        // Persist the operation result in its existing callback; only the read-only snapshot is async.
        try {
            operation.result.addListener({
                try {
                    operation.result.get() // Already complete: this callback never waits for enqueue.
                    record(context, "WORK_ENQUEUE_COMMITTED", "requestedWorkId" to workId,
                        "uniqueName" to name, "trigger" to trigger.name, "policy" to policy,
                        "generation" to generation)
                    scope.launch {
                        try {
                            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
                            val current = MonitoringStatusRepository(context).executionStatus().scheduleGeneration
                            val sampledAt = System.currentTimeMillis()
                            infos.forEach { info ->
                                record(context, "WORK_ENQUEUE_SNAPSHOT", "requestedWorkId" to workId,
                                    "retainedWorkId" to info.id.toString(), "uniqueName" to name,
                                    "trigger" to trigger.name, "state" to info.state.name, "snapshotAtMs" to sampledAt,
                                    "currentGeneration" to current, "requestedRetained" to infos.any { it.id.toString() == workId })
                            }
                        } catch (error: Exception) {
                            record(context, "WORK_ENQUEUE_OBSERVATION_FAILURE", "requestedWorkId" to workId,
                                "trigger" to trigger.name, "exceptionClass" to error.javaClass.simpleName)
                        }
                    }
                } catch (error: Exception) {
                    record(context, "WORK_ENQUEUE_FAILURE", "requestedWorkId" to workId,
                        "trigger" to trigger.name, "exceptionClass" to error.javaClass.simpleName)
                }
            }, { command -> command.run() })
        } catch (error: Exception) {
            record(context, "WORK_ENQUEUE_OBSERVATION_FAILURE", "requestedWorkId" to workId,
                "trigger" to trigger.name, "exceptionClass" to error.javaClass.simpleName)
        }
    }
}
