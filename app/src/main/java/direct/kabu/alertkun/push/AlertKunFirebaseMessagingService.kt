package direct.kabu.alertkun.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import direct.kabu.alertkun.BuildConfig
import direct.kabu.alertkun.monitoring.MonitoringScheduler
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Token registration and a short handoff to the existing one-time mail-check worker. */
class AlertKunFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FcmRegistrationCoordinator.onToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Never log arbitrary data: unknown actions may contain untrusted or private values.
        val accepted = message.data["action"] == FcmMailCheckDispatcher.CHECK_MAIL
        if (accepted) {
            val receivedAt = System.currentTimeMillis()
            val context = applicationContext
            statusScope.launch {
                try {
                    MonitoringStatusRepository(context).recordFcmReceived(receivedAt)
                } catch (error: Exception) {
                    debugLog("FCM display reference save failed type=${error.javaClass.simpleName}")
                }
            }
        }
        debugLog("FCM received action=${if (accepted) "CHECK_MAIL" else "ignored"} priority=${message.priority} originalPriority=${message.originalPriority}")
        try {
            FcmMailCheckDispatcher {
                debugLog("CHECK_MAIL accepted; handing off to runNow")
                val operation = MonitoringScheduler.runNow(applicationContext, MailCheckTrigger.FCM)
                operation.result.addListener({
                    try {
                        operation.result.get()
                        debugLog("CHECK_MAIL runNow enqueue committed")
                    } catch (error: Exception) {
                        debugLog("CHECK_MAIL enqueue failed type=${error.javaClass.simpleName}")
                    }
                }, { command -> command.run() })
            }.dispatch(message.data)
        } catch (error: Exception) {
            debugLog("CHECK_MAIL handoff failed type=${error.javaClass.simpleName}")
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d("AlertKunFcm", "wallMs=${System.currentTimeMillis()} $message")
    }

    private companion object {
        val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
