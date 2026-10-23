package direct.kabu.alertkun.push

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Uses only dummy identities and private fixture preferences; no Firebase or network calls. */
class RegistrationVerificationStateTest {
    private lateinit var context: FixtureContext
    private val time = 1_800_000_000_000L
    private val target = FcmRegistrationTarget("fixture-master", 5, revision = 7)
    private val token = "dummy-registration-token"
    private val identity = "fixture-identity"

    @Before fun setUp() {
        context = FixtureContext(ApplicationProvider.getApplicationContext())
        mainCredentials().save(FcmTokenRegistrar.IDENTITY_VERSION_KEY, identity)
        mainCredentials().save(FcmTokenRegistrar.DEVICE_TOKEN_KEY, "dummy-existing-bearer")
    }

    @After fun tearDown() {
        // Remove only this test's uniquely named ciphertext. Never touch the shared Keystore key.
        context.deleteSharedPreferences("main")
        context.deleteSharedPreferences("pending")
    }

    @Test fun encryptedRequestPersistsBeforeBeginAndReloadCanComplete() = runBlocking {
        val endpoints = mutableListOf<String>()
        val first = registrar { endpoint ->
            assertNotNull("Local request must precede HTTPS", pendingStore().read())
            endpoints.add(endpoint)
            FakeConnection(202, "{\"status\":\"pending\"}")
        }
        assertEquals(GatewayRegistrationKind.PENDING, first.register(target.masterId, token, 5, 7).kind)
        val saved = pendingStore().read()!!
        val raw = context.getSharedPreferences("pending", Context.MODE_PRIVATE).all.values.joinToString()
        for (secret in listOf(token, saved.nonce, saved.replyKey, target.masterId)) {
            assertFalse("Request fields must be encrypted at rest", raw.contains(secret))
        }
        assertTrue("Existing credential remains until confirmation",
            mainCredentials().load(FcmTokenRegistrar.DEVICE_TOKEN_KEY) == "dummy-existing-bearer")

        // Fresh stores and registrar model component recreation using persisted ciphertext.
        val reloaded = pendingStore()
        assertTrue("Recreated store retains the original local request", reloaded.read()!!.nonce == saved.nonce)
        assertTrue(reloaded.receive(message(saved, 1), time))
        val completed = registrar { endpoint ->
            endpoints.add(endpoint)
            FakeConnection(200, "{\"status\":\"success\",\"deviceToken\":\"${"d".repeat(43)}\"}")
        }.register(target.masterId, token, 5, 7)
        assertEquals(GatewayRegistrationKind.SUCCESS, completed.kind)
        assertEquals(listOf("registration/begin", "registration/confirm"), endpoints)
        assertTrue("Confirmed bearer survives another store recreation",
            mainCredentials().load(FcmTokenRegistrar.DEVICE_TOKEN_KEY) == "d".repeat(43))
        assertNull(pendingStore().read())
    }

    @Test fun reloadedRequestRejectsChangedIdentityTargetTokenAndExpiry() {
        val saved = prepare()
        val loaded = pendingStore().read()!!
        assertTrue(loaded.matches(target, token, identity, time))
        assertFalse(loaded.matches(target.copy(masterId = "other-master"), token, identity, time))
        assertFalse(loaded.matches(target.copy(intervalMinutes = 6), token, identity, time))
        assertFalse(loaded.matches(target.copy(revision = 8), token, identity, time))
        assertFalse(loaded.matches(target, "other-dummy-token", identity, time))
        assertFalse(loaded.matches(target, token, "changed-password-identity", time))
        assertFalse(loaded.matches(target, token, identity, saved.createdAt + PendingRegistration.TTL))
        assertFalse(loaded.matches(target, token, identity, saved.createdAt - 1))
    }

    @Test fun unsolicitedVerificationNeverAuthorizesAResponseOrMailCheck() {
        val foreign = PendingRegistration("other-master", token, 5, 7, identity,
            PendingRegistration.randomSecret(), PendingRegistration.randomSecret(), time)
        var responses = 0
        var checks = 0
        var statusUpdates = 0
        fun dispatch(data: Map<String, String>) {
            if (dispatchRegistrationVerification(data) {
                if (pendingStore().receive(it, time)) responses++
            }) return
            statusUpdates++
            FcmMailCheckDispatcher { checks++ }.dispatch(data)
        }
        dispatch(message(foreign, 1))
        prepare()
        dispatch(message(foreign, 1))
        assertEquals(0, responses)
        assertEquals(0, checks)
        assertEquals(0, statusUpdates)
        assertNull(pendingStore().read()!!.challenge)
    }

    @Test fun delayedOlderChallengeCannotReplaceNewerPersistedProof() {
        val saved = prepare()
        assertTrue(pendingStore().receive(message(saved, 1), time))
        assertTrue(pendingStore().receive(message(saved, 2), time))
        assertFalse(pendingStore().receive(message(saved, 1), time))
        assertFalse(pendingStore().receive(message(saved, 2), time))
        val restored = pendingStore().read()!!
        assertEquals(2, restored.challengeVersion)
        assertTrue("Latest challenge must survive stale and duplicate messages",
            restored.challenge == "c".repeat(42) + "2")
    }

    @Test fun lostResponseCanRetryIdenticalProofAfterStoreRecreation() = runBlocking {
        val saved = prepare()
        assertTrue(pendingStore().receive(message(saved, 2), time))
        var originalProof: JsonObject? = null
        val failed = registrar { endpoint ->
            assertEquals("registration/confirm", endpoint)
            FakeConnection(503).also { connection ->
                connection.onRequest = { originalProof = it }
            }
        }.register(target.masterId, token, 5, 7)
        assertEquals(GatewayRegistrationKind.HTTP_ERROR, failed.kind)
        assertTrue("Ambiguous failure must retain local recovery proof",
            pendingStore().read()!!.replyKey == saved.replyKey)
        val retried = registrar { endpoint ->
            assertEquals("registration/confirm", endpoint)
            FakeConnection(200, "{\"status\":\"success\",\"deviceToken\":\"${"e".repeat(43)}\"}").also { connection ->
                connection.onRequest = { assertTrue("Retry must submit identical proof", originalProof == it) }
            }
        }.register(target.masterId, token, 5, 7)
        assertEquals(GatewayRegistrationKind.SUCCESS, retried.kind)
        assertNull(pendingStore().read())
    }

    @Test fun existingCheckMailActionStillDispatchesOnlyOneMailCheck() {
        var checks = 0
        val dispatcher = FcmMailCheckDispatcher { checks++ }
        dispatcher.dispatch(mapOf("action" to "VERIFY_REGISTRATION"))
        dispatcher.dispatch(mapOf("action" to "unknown"))
        dispatcher.dispatch(emptyMap())
        assertEquals(0, checks)
        dispatcher.dispatch(mapOf("action" to "CHECK_MAIL"))
        assertEquals(1, checks)
    }

    private fun mainCredentials() = AndroidKeystoreCredentialStore(context, "main")
    private fun pendingStore() = PendingRegistrationStore(AndroidKeystoreCredentialStore(context, "pending"))
    private fun registrar(connection: (String) -> HttpURLConnection) = FcmTokenRegistrar(
        mainCredentials(), openConnection = connection, pending = pendingStore(), now = { time })
    private fun prepare() = registrar { throw AssertionError("Preparation must not perform network I/O") }
        .prepare(target, token)
    private fun message(p: PendingRegistration, version: Int) = mapOf(
        "action" to PendingRegistration.VERIFY,
        "clientNonce" to p.nonce,
        "tokenHash" to PendingRegistration.sha256(p.token),
        "requestId" to "11111111-1111-4111-8111-111111111111",
        "challenge" to "c".repeat(42) + version,
        "challengeVersion" to version.toString(),
    )

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        private val prefix = "registration_fixture_${UUID.randomUUID()}_"
        private val directory = File(base.cacheDir, prefix)
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(prefix + name, mode)
        override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences(prefix + name)
        override fun getFilesDir(): File = File(directory, "files").also { it.mkdirs() }
        override fun getCacheDir(): File = File(directory, "cache").also { it.mkdirs() }
        override fun getNoBackupFilesDir(): File = File(directory, "no_backup").also { it.mkdirs() }
        override fun getDatabasePath(name: String): File {
            require(File(name).name == name)
            return File(File(directory, "databases").also { it.mkdirs() }, name)
        }
    }

    private class FakeConnection(private val status: Int, private val body: String = "{}") :
        HttpURLConnection(URL("https://example.invalid")) {
        private val request = ByteArrayOutputStream()
        var onRequest: ((JsonObject) -> Unit)? = null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream() = request
        override fun getResponseCode(): Int {
            onRequest?.invoke(Json.parseToJsonElement(request.toString("UTF-8")) as JsonObject)
            return status
        }
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
