package direct.kabu.alertkun.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import java.io.FileInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Temporary explicit-argument device trial. No application/server data writes. */
class NetworkTransitionDeviceDiagnosticTest {
    @Test fun switchBothDirectionsAndFullOutage() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowNetworkSwitchTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun trace(message: String) {
            println("NETWORK_DIAG $message")
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("networkDiagnosis", message) })
        }
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }
        // Explicit initial-state requirements permit exact restoration in finally.
        assertEquals("1", shell("settings get global wifi_on"))
        assertEquals("1", shell("settings get global mobile_data"))
        assertEquals("0", shell("settings get global airplane_mode_on"))
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun mobile(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        fun wifi(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        MailCheckWorker.executionMutex.withLock {
            val saved = AccountRepository(AlertKunDatabase.getInstance(context).accountDao()).accounts.first().single { it.enabled }
            val receiver = AccountMailReceiver(listOf(saved), AndroidKeystoreCredentialStore(context))
            DefaultNetworkMonitor(context).use { monitor ->
                fun policy() = NetworkStabilization(monitor, ::trace,
                    elapsedMillis = { android.os.SystemClock.elapsedRealtime() })
                try {
                    assertTrue(monitor.awaitReady(8_000))
                    assertTrue(wifi())
                    policy().read { receiver.testConnection(saved) }
                    trace("initial_wifi_mail_success=true")
                    val wifiId = monitor.state.value.networkId
                    shell("svc wifi disable")
                    kotlinx.coroutines.withTimeout(10_000) {
                        monitor.state.first { it.networkId != wifiId }
                    }
                    policy().read { receiver.testConnection(saved) }
                    assertTrue(mobile())
                    trace("wifi_to_mobile_mail_success=true")
                    shell("svc wifi enable")
                    kotlinx.coroutines.withTimeout(20_000) {
                        monitor.state.first { it.ready && wifi() }
                    }
                    policy().read { receiver.testConnection(saved) }
                    trace("mobile_to_wifi_mail_success=true")
                    shell("svc data disable")
                    shell("svc wifi disable")
                    shell("cmd connectivity airplane-mode enable")
                    // Radio disabling is asynchronous; do not mistake an old VALIDATED snapshot for a full outage.
                    delay(2_000)
                    kotlinx.coroutines.withTimeout(10_000) { monitor.state.first { it.networkId == null } }
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        policy().read { receiver.testConnection(saved) }
                        fail("An outage must fail after the grace period")
                    } catch (failure: MailReceiverException) {
                        assertEquals(MonitoringFailureKind.DEVICE_OFFLINE, failure.failureKind)
                    }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    assertTrue(elapsed >= 7_800 && elapsed < 10_000)
                    trace("full_outage_failure=true graceMs=$elapsed")
                } finally {
                    shell("cmd connectivity airplane-mode disable")
                    shell("svc data enable")
                    shell("svc wifi enable")
                    assertTrue(monitor.awaitReady(20_000))
                    trace("original_network_settings_restored=true")
                }
            }
            assertEquals(saved, AccountRepository(AlertKunDatabase.getInstance(context).accountDao()).get(saved.id))
        }
    }
}
