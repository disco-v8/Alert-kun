package direct.kabu.alertkun.push

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import direct.kabu.alertkun.BuildConfig
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent, best-effort process-local registration; never starts a mail check or alarm. */
object FcmRegistrationCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationMutex = Mutex()
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                val repository = SettingsRepository(appContext)
                repository.fcmRegistrationTargets().collect { acquireCurrentToken(appContext) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                debugLog("registration settings observation failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun acquireCurrentToken(context: Context) {
        try {
            val messaging = FirebaseMessaging.getInstance()
            debugLog("Firebase initialized")
            messaging.token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onToken(context, task.result)
                } else {
                    debugLog("FCM token acquisition failed: ${task.exception?.javaClass?.simpleName}")
                }
            }
        } catch (error: Exception) {
            debugLog("Firebase initialization/token acquisition failed: ${error.javaClass.simpleName}")
        }
    }

    fun onToken(context: Context, token: String) {
        if (token.isBlank()) {
            debugLog("FCM token acquisition failed: empty token")
            return
        }
        debugLog("FCM token acquired: ${maskedToken(token)}")
        val appContext = context.applicationContext
        scope.launch {
            try {
                registrationMutex.withLock {
                    val repository = SettingsRepository(appContext)
                    val target = repository.fcmRegistrationTargets().first()
                    if (!target.configured) {
                        debugLog("MyDNS.JP MasterID is not configured")
                        return@withLock
                    }
                    val interval = target.intervalMinutes
                    val result = FcmTokenRegistrar().register(target.masterId, token, interval)
                    val completedAt = System.currentTimeMillis()
                    if (result.kind == GatewayRegistrationKind.SUCCESS && repository.fcmRegistrationTargets().first() == target) {
                        MonitoringStatusRepository(appContext).recordGatewayRegistrationSuccess(completedAt, target.revision)
                    }
                    debugLog("Gateway registration: ${result.kind} HTTP=${result.httpStatus ?: "none"} rcvinterval=$interval")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                debugLog("Gateway registration failed: ${error.javaClass.simpleName}")
            }
        }
    }

    internal fun maskedToken(token: String): String =
        if (token.length > 12) "${token.take(6)}...${token.takeLast(4)} len=${token.length}"
        else "[masked] len=${token.length}"

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d("AlertKunFcm", message)
    }
}
