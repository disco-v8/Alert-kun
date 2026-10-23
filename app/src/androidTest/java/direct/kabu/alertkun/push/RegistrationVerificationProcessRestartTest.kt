package direct.kabu.alertkun.push

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.data.repository.MyDnsSettingsRepository
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Run each method in a separate instrumentation process with registrationRestartPhase=persist/verify.
 * A regular suite skips both phases. Only the two fixed fixture preferences are changed.
 */
class RegistrationVerificationProcessRestartTest {
    @Test fun persistRequest() = runBlocking {
        assumeTrue(phase() == "persist")
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearFixture(context)
        val credentials = mainCredentials(context)
        credentials.save(FcmTokenRegistrar.IDENTITY_VERSION_KEY, IDENTITY)
        credentials.save(MyDnsSettingsRepository.CREDENTIAL_KEY, "dummy-restart-password")
        credentials.save(FcmTokenRegistrar.DEVICE_TOKEN_KEY, "dummy-restart-existing-bearer")
        credentials.save(PROCESS_KEY, Process.myPid().toString())
        val store = pendingStore(context)
        val result = FcmTokenRegistrar(credentials, openConnection = { endpoint ->
            assertEquals("registration/begin", endpoint)
            assertNotNull("Encrypted request must be durable before any begin call", pendingStore(context).read())
            FakeConnection(202, "{\"status\":\"pending\"}")
        }, pending = store).register(MASTER_ID, TOKEN, INTERVAL, REVISION)
        assertEquals(GatewayRegistrationKind.PENDING, result.kind)
        assertNotNull("Request must remain available for a separate instrumentation process", pendingStore(context).read())
        assertTrue("Pending confirmation must preserve the existing credential",
            mainCredentials(context).load(FcmTokenRegistrar.DEVICE_TOKEN_KEY) == "dummy-restart-existing-bearer")
        // Deliberately retain only fixture ciphertext for the verify phase.
    }

    @Test fun verifyRequestAfterRestart() = runBlocking {
        assumeTrue(phase() == "verify")
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val credentials = mainCredentials(context)
            val priorProcess = credentials.load(PROCESS_KEY)?.toIntOrNull()
            assertNotNull("Persist phase must have completed first", priorProcess)
            assertTrue("Verify phase must run after actual process recreation", priorProcess != Process.myPid())
            val pending = pendingStore(context).read()
            assertNotNull("Previously committed ciphertext must reload after process recreation", pending)
            val request = pending!!
            val currentTime = System.currentTimeMillis()
            val target = FcmRegistrationTarget(MASTER_ID, INTERVAL, revision = REVISION)
            assertTrue(request.matches(target, TOKEN, IDENTITY, currentTime))
            assertFalse(request.matches(target.copy(revision = REVISION + 1), TOKEN, IDENTITY, currentTime))
            assertFalse(request.matches(target, "dummy-restart-new-token", IDENTITY, currentTime))
            assertFalse(request.matches(target, TOKEN, "dummy-restart-new-identity", currentTime))
            val receipt = mapOf(
                "action" to PendingRegistration.VERIFY,
                "requestId" to "22222222-2222-4222-8222-222222222222",
                "clientNonce" to request.nonce,
                "tokenHash" to PendingRegistration.sha256(TOKEN),
                "challenge" to "r".repeat(43),
                "challengeVersion" to "1",
            )
            assertTrue(pendingStore(context).receive(receipt, currentTime))
            val result = FcmTokenRegistrar(credentials, openConnection = { endpoint ->
                assertEquals("registration/confirm", endpoint)
                FakeConnection(200, "{\"status\":\"success\",\"deviceToken\":\"${"s".repeat(43)}\"}")
            }, pending = pendingStore(context)).register(MASTER_ID, TOKEN, INTERVAL, REVISION)
            assertEquals(GatewayRegistrationKind.SUCCESS, result.kind)
            assertTrue("Confirmed credential must be persisted after the recovered request",
                mainCredentials(context).load(FcmTokenRegistrar.DEVICE_TOKEN_KEY) == "s".repeat(43))
            assertNull(pendingStore(context).read())
        } finally {
            clearFixture(context)
        }
    }

    private fun phase() = InstrumentationRegistry.getArguments().getString("registrationRestartPhase")
    private fun mainCredentials(context: Context) = AndroidKeystoreCredentialStore(context, MAIN_PREFERENCES)
    private fun pendingStore(context: Context) = PendingRegistrationStore(AndroidKeystoreCredentialStore(context, PENDING_PREFERENCES))
    private fun clearFixture(context: Context) {
        context.deleteSharedPreferences(MAIN_PREFERENCES)
        context.deleteSharedPreferences(PENDING_PREFERENCES)
    }

    private class FakeConnection(private val status: Int, private val body: String) :
        HttpURLConnection(URL("https://example.invalid")) {
        private val request = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream() = request
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val MAIN_PREFERENCES = "fixture_registration_restart_main"
        const val PENDING_PREFERENCES = "fixture_registration_restart_pending"
        const val PROCESS_KEY = "fixture_origin_process"
        const val MASTER_ID = "fixture-restart-master"
        const val TOKEN = "dummy-restart-registration-token"
        const val IDENTITY = "fixture-restart-identity"
        const val INTERVAL = 5
        const val REVISION = 19L
    }
}
