package direct.kabu.alertkun.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import direct.kabu.alertkun.BuildConfig
import direct.kabu.alertkun.monitoring.MonitoringScheduler
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import direct.kabu.alertkun.monitoring.MonitoringObservation
import direct.kabu.alertkun.monitoring.ObservationFormat
import direct.kabu.alertkun.monitoring.MonitoringStatusNotifier
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
        val messageHash = ObservationFormat.hash(message.messageId)
        MonitoringObservation.record(applicationContext, "FCM_RECEIVED",
            "action" to if (accepted) "CHECK_MAIL" else "IGNORED",
            "priority" to priorityName(message.priority), "originalPriority" to priorityName(message.originalPriority),
            "sentTime" to message.sentTime, "ttl" to message.ttl, "messageIdHash" to messageHash,
            "collapseKeyHash" to ObservationFormat.hash(message.collapseKey))
        if (accepted) {
            val receivedAt = System.currentTimeMillis()
            val context = applicationContext
            statusScope.launch {
                try {
                    val status = MonitoringStatusRepository(context)
                    status.recordFcmReceived(receivedAt)
                    MonitoringObservation.record(context, "FCM_GENERATION_SNAPSHOT", "messageIdHash" to messageHash,
                        "currentGeneration" to status.executionStatus().scheduleGeneration)
                } catch (error: Exception) {
                    MonitoringObservation.record(context, "FCM_REFERENCE_FAILURE", "messageIdHash" to messageHash,
                        "exceptionClass" to error.javaClass.simpleName)
                    debugLog("FCM display reference save failed type=${error.javaClass.simpleName}")
                }
            }
            // Notify before dispatch. Notification failures never suppress the existing handoff.
            try {
                MonitoringStatusNotifier(applicationContext).onFcmReceived(receivedAt, messageHash)
            } catch (_: Exception) {
                MonitoringObservation.record(applicationContext, "MONITORING_NOTIFICATION_BLOCKED",
                    "notificationId" to MonitoringStatusNotifier.NOTIFICATION_ID,
                    "channelId" to MonitoringStatusNotifier.CHANNEL_ID, "action" to "SKIP", "trigger" to "FCM",
                    "messageIdHash" to messageHash, "reason" to "NOTIFICATION_ERROR")
            }
        }
        debugLog("FCM received action=${if (accepted) "CHECK_MAIL" else "ignored"} priority=${message.priority} originalPriority=${message.originalPriority}")
        try {
            FcmMailCheckDispatcher {
                debugLog("CHECK_MAIL accepted; handing off to runNow")
                val operation = MonitoringScheduler.runNow(applicationContext, MailCheckTrigger.FCM, messageHash)
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
            MonitoringObservation.record(applicationContext, "FCM_DISPATCH_FAILURE", "messageIdHash" to messageHash,
                "trigger" to "FCM", "exceptionClass" to error.javaClass.simpleName)
            debugLog("CHECK_MAIL handoff failed type=${error.javaClass.simpleName}")
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d("AlertKunFcm", "wallMs=${System.currentTimeMillis()} $message")
    }

    private fun priorityName(priority: Int): String = when (priority) {
        RemoteMessage.PRIORITY_HIGH -> "HIGH"
        RemoteMessage.PRIORITY_NORMAL -> "NORMAL"
        else -> "UNKNOWN"
    }

    private companion object {
        val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
