package direct.kabu.alertkun.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.MyDnsSettingsRepository
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import direct.kabu.alertkun.monitoring.MonitoringObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Registration is independent of MailCheck, alarms and monitoring scheduling. */
object FcmRegistrationCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationMutex = Mutex()
    private var started = false
    private val mutableResult = MutableStateFlow<GatewayRegistrationKind?>(null)
    val registrationResult = mutableResult.asStateFlow()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch { MyDnsSettingsRepository.gatewayCredentialChanges.collect { acquireCurrentToken(app) } }
        scope.launch { SettingsRepository(app).fcmRegistrationTargets().collect { acquireCurrentToken(app) } }
    }
    private fun acquireCurrentToken(context: Context) {
        scope.launch {
            try { onToken(context, currentToken()) }
            catch (error: Exception) { if (error is CancellationException) throw error }
        }
    }
    private suspend fun currentToken(): String = suspendCancellableCoroutine { continuation ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(IllegalStateException("Token unavailable"))
                }
            }
        } catch (_: Exception) { continuation.resumeWithException(IllegalStateException("Token unavailable")) }
    }
    internal fun pendingStore(context: Context) = PendingRegistrationStore(
        AndroidKeystoreCredentialStore(context, PendingRegistrationStore.PREFERENCES))

    fun onToken(context: Context, token: String) {
        if (token.isBlank() || token.length > 4096) return
        val app = context.applicationContext
        scope.launch {
            try {
                registrationMutex.withLock {
                    MyDnsSettingsRepository.withGatewayCredentials {
                        val target = SettingsRepository(app).fcmRegistrationTargets().first()
                        val store = pendingStore(app)
                        if (!target.configured) { store.clear(); mutableResult.value = GatewayRegistrationKind.SKIPPED; return@withGatewayCredentials }
                        // Durable state precedes both the network request and its durable worker handoff.
                        FcmTokenRegistrar(AndroidKeystoreCredentialStore(app), pending = store).prepare(target, token)
                        mutableResult.value = GatewayRegistrationKind.PENDING
                        RegistrationVerificationWorker.enqueue(app, replace = true)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableResult.value = GatewayRegistrationKind.STORAGE_ERROR
            }
        }
    }

    /** Called from FCM with bounded private persistence only; no HTTPS or mail-check side effects. */
    internal fun receiveVerification(context: Context, data: Map<String, String>) {
        try {
            if (pendingStore(context).receive(data, System.currentTimeMillis()))
                RegistrationVerificationWorker.enqueue(context, replace = true)
        } catch (_: Exception) { /* Unreadable local state cannot authorize a response. */ }
    }

    internal suspend fun runRegistration(context: Context): GatewayRegistrationResult {
        val token = try { withTimeout(20_000) { currentToken() } }
        catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            return GatewayRegistrationResult(GatewayRegistrationKind.NETWORK_ERROR)
        }
        return registrationMutex.withLock {
            MyDnsSettingsRepository.withGatewayCredentials {
                val settings = SettingsRepository(context)
                val target = settings.fcmRegistrationTargets().first()
                val credentials = AndroidKeystoreCredentialStore(context)
                val store = pendingStore(context)
                val p = store.read() ?: return@withGatewayCredentials GatewayRegistrationResult(GatewayRegistrationKind.SKIPPED)
                if (!target.configured || !p.validAt(System.currentTimeMillis())) {
                    store.clearIf(p.nonce)
                    return@withGatewayCredentials GatewayRegistrationResult(GatewayRegistrationKind.SKIPPED)
                }
                if (!p.matches(target, token, credentials.load(FcmTokenRegistrar.IDENTITY_VERSION_KEY) ?: "initial", System.currentTimeMillis())) {
                    store.clearIf(p.nonce)
                    onToken(context, token)
                    return@withGatewayCredentials GatewayRegistrationResult(GatewayRegistrationKind.SKIPPED)
                }
                val result = FcmTokenRegistrar(credentials, { event, fields ->
                    MonitoringObservation.record(context, event, *fields.toList().toTypedArray())
                }, pending = store).register(target.masterId, token, target.intervalMinutes, target.revision)
                mutableResult.value = result.kind
                val completedAt = System.currentTimeMillis()
                if (result.kind == GatewayRegistrationKind.SUCCESS && settings.fcmRegistrationTargets().first() == target) {
                    MonitoringStatusRepository(context).recordGatewayRegistrationSuccess(completedAt, target.revision)
                    MonitoringObservation.record(context, "GATEWAY_INTERVAL_REGISTERED", "interval" to target.intervalMinutes,
                        "completedMs" to completedAt, "revision" to target.revision)
                }
                result
            }
        }
    }
}
