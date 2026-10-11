package direct.kabu.alertkun.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Passive receiver owned by Application; never starts work or keeps the process alive. */
internal class NetworkRestrictionObserver(
    private val context: Context,
    private val sink: ObservationSink,
) : Closeable {
    private val observation = DeviceNetworkObservation(context, sink)
    private var started = false
    private var registered = false

    internal val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED) return
            // Read our current state; do not inspect or log intent extras.
            observation.snapshot("RESTRICT_BACKGROUND_CHANGED")
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        try {
            ContextCompat.registerReceiver(context, receiver,
                IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            emit("NETWORK_RESTRICTION_OBSERVER_REGISTERED", emptyMap())
        } catch (error: Exception) {
            emit("NETWORK_RESTRICTION_OBSERVER_REGISTRATION_FAILURE",
                mapOf("exceptionClass" to error.javaClass.simpleName))
        }
        // Persist startup state even when registration fails. Register first to reduce the gap.
        observation.snapshot("PROCESS_START")
    }

    @Synchronized
    override fun close() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun emit(event: String, fields: Map<String, Any?>) {
        runCatching { sink(event, fields) }
    }
}
