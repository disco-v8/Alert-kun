package direct.kabu.alertkun.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** No secrets in input/output data; one encrypted request is revalidated on every attempt. */
class RegistrationVerificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val result = FcmRegistrationCoordinator.runRegistration(applicationContext)
            when {
                result.kind in setOf(GatewayRegistrationKind.SUCCESS, GatewayRegistrationKind.SKIPPED) -> Result.success()
                result.kind in setOf(GatewayRegistrationKind.NOT_CONFIGURED, GatewayRegistrationKind.AUTHENTICATION_ERROR) -> Result.failure()
                result.httpStatus in listOf(400, 403, 409, 410, 426) -> Result.failure()
                runAttemptCount >= 7 -> Result.failure()
                else -> Result.retry()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount >= 7) Result.failure() else Result.retry()
        }
    }
    companion object {
        internal fun enqueue(context: Context, replace: Boolean) {
            val work = OneTimeWorkRequestBuilder<RegistrationVerificationWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("alertkun_registration_verification",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
        }
    }
}
