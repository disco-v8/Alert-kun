package direct.kabu.alertkun.push

import direct.kabu.alertkun.data.repository.MyDnsSettingsRepository
import direct.kabu.alertkun.security.CredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class FcmTokenRegistrarTest {
    @Test fun initialAuthenticationPersistsTokenBeforeRegistration() = runBlocking {
        val f = Fixture()
        f.responses.add(FakeConnection(200, "{\"status\":\"success\",\"deviceToken\":\"new-secret\"}"))
        f.responses.add(FakeConnection(200))
        assertEquals(GatewayRegistrationKind.SUCCESS, f.register().kind)
        assertEquals(listOf("auth", "set-interval"), f.endpoints)
        val auth = f.responses[0].json()
        assertEquals(setOf("masterid", "masterpwd", "fcmtoken"), auth.keys)
        assertEquals("master-secret", auth.getValue("masterpwd").jsonPrimitive.content)
        assertNull(f.responses[0].getRequestProperty("Authorization"))
        assertEquals("new-secret", f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        assertSetInterval(f.responses[1], "new-secret", "current-fcm")
    }

    @Test fun storedBearerAvoidsAuthAndHandlesNewFcmToken() = runBlocking {
        val f = Fixture("existing-secret")
        f.responses.add(FakeConnection(200))
        assertEquals(GatewayRegistrationKind.SUCCESS, f.register("updated-fcm").kind)
        assertEquals(listOf("set-interval"), f.endpoints)
        assertSetInterval(f.responses.single(), "existing-secret", "updated-fcm")
    }

    @Test fun unauthorizedReauthenticatesAndRetriesExactlyOnce() = runBlocking {
        val f = Fixture("old-secret")
        f.responses.addAll(listOf(FakeConnection(401), FakeConnection(200,
            "{\"status\":\"success\",\"deviceToken\":\"replacement-secret\"}"), FakeConnection(200)))
        assertEquals(GatewayRegistrationKind.SUCCESS, f.register().kind)
        assertEquals(listOf("set-interval", "auth", "set-interval"), f.endpoints)
        assertEquals(1, f.credentials.deletes)
        assertSetInterval(f.responses.last(), "replacement-secret", "current-fcm")
        assertTrue(f.events.contains("GATEWAY_AUTH_RETRY"))
    }

    @Test fun secondUnauthorizedStopsAndDiscardsInvalidBearer() = runBlocking {
        val f = Fixture("old-secret")
        f.responses.addAll(listOf(FakeConnection(401), FakeConnection(200,
            "{\"status\":\"success\",\"deviceToken\":\"replacement-secret\"}"), FakeConnection(401)))
        assertEquals(GatewayRegistrationKind.AUTHENTICATION_ERROR, f.register().kind)
        assertEquals(3, f.endpoints.size)
        assertNull(f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
    }

    @Test fun primaryAuth401IsCredentialError() = runBlocking {
        val f = Fixture()
        f.responses.add(FakeConnection(401))
        assertEquals(GatewayRegistrationKind.AUTHENTICATION_ERROR, f.register().kind)
        assertEquals(0, f.credentials.deletes)
        assertEquals("master-secret", f.credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
    }

    @Test fun auth503PreservesMasterPassword() = runBlocking {
        val f = Fixture()
        f.responses.add(FakeConnection(503))
        assertEquals(GatewayRegistrationKind.HTTP_ERROR, f.register().kind)
        assertEquals(0, f.credentials.deletes)
        assertEquals("master-secret", f.credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
    }

    @Test fun non401ErrorsNeverDeleteBearerOrReauthenticate() = runBlocking {
        listOf(503, 500, 302, 403).forEach { status ->
            val f = Fixture("existing-secret")
            f.responses.add(FakeConnection(status))
            assertEquals(GatewayRegistrationKind.HTTP_ERROR, f.register().kind)
            assertEquals(listOf("set-interval"), f.endpoints)
            assertEquals(0, f.credentials.deletes)
            assertEquals("existing-secret", f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        }
    }

    @Test fun networkFailuresPreserveBearerAndNeverIncludeExceptionMessages() = runBlocking {
        listOf(java.net.UnknownHostException("secret") to GatewayRegistrationKind.DNS_ERROR,
            java.net.ConnectException("secret") to GatewayRegistrationKind.CONNECTION_ERROR,
            java.net.SocketTimeoutException("secret") to GatewayRegistrationKind.TIMEOUT,
            javax.net.ssl.SSLHandshakeException("secret") to GatewayRegistrationKind.TLS_ERROR,
            IOException("secret") to GatewayRegistrationKind.NETWORK_ERROR).forEach { (error, kind) ->
            val credentials = MemoryCredentials("existing-secret")
            assertEquals(kind, FcmTokenRegistrar(credentials, openConnection = { throw error })
                .register("test", "current-fcm", 5).kind)
            assertEquals(0, credentials.deletes)
            assertEquals("existing-secret", credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        }
    }

    @Test fun missingCredentialsNeverConnect() = runBlocking {
        val credentials = MemoryCredentials()
        credentials.values.clear()
        val client = FcmTokenRegistrar(credentials, openConnection = { error("Must not connect") })
        assertEquals(GatewayRegistrationKind.SKIPPED, client.register(" ", "fcm", 5).kind)
        assertEquals(GatewayRegistrationKind.NOT_CONFIGURED, client.register("master", "fcm", 5).kind)
    }

    @Test fun malformedAuthResponseNeverPersistsBearer() = runBlocking {
        listOf("invalid", "[]", "{}", "{\"status\":\"success\"}",
            "{\"status\":\"success\",\"deviceToken\":123}", " ".repeat(8193)).forEach { body ->
            val f = Fixture()
            f.responses.add(FakeConnection(200, body))
            assertNotEquals(GatewayRegistrationKind.SUCCESS, f.register().kind)
            assertNull(f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        }
    }

    @Test fun observationContainsOnlySafeMetadata() = runBlocking {
        val f = Fixture()
        f.responses.addAll(listOf(FakeConnection(200, "{\"status\":\"success\",\"deviceToken\":\"new-secret\"}"), FakeConnection(200)))
        f.register()
        val text = f.metadata.toString()
        listOf("master-secret", "new-secret", "current-fcm", "Authorization", "masterid", "masterpwd").forEach {
            assertFalse(text.contains(it))
        }
        assertEquals(listOf("GATEWAY_AUTH_START", "GATEWAY_AUTH_SUCCESS", "GATEWAY_SET_INTERVAL_START", "GATEWAY_SET_INTERVAL_SUCCESS"), f.events)
    }

    private fun assertSetInterval(connection: FakeConnection, bearer: String, fcm: String) {
        val json = connection.json()
        assertEquals(setOf("fcmtoken", "rcvinterval"), json.keys)
        assertEquals(fcm, json.getValue("fcmtoken").jsonPrimitive.content)
        assertEquals("5", json.getValue("rcvinterval").jsonPrimitive.content)
        assertEquals("Bearer $bearer", connection.getRequestProperty("Authorization"))
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    private class Fixture(bearer: String? = null) {
        val credentials = MemoryCredentials(bearer)
        val responses = mutableListOf<FakeConnection>()
        val endpoints = mutableListOf<String>()
        val events = mutableListOf<String>()
        val metadata = mutableListOf<Map<String, Any?>>()
        suspend fun register(fcm: String = "current-fcm") = FcmTokenRegistrar(credentials,
            { event, fields -> events.add(event); metadata.add(fields) }, { endpoint ->
                if (endpoint == "set-interval") assertNotNull(credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
                endpoints.add(endpoint)
                responses[endpoints.lastIndex]
            }).register("test-master", fcm, 5)
    }

    private class MemoryCredentials(bearer: String? = null) : CredentialStore {
        val values = mutableMapOf(MyDnsSettingsRepository.CREDENTIAL_KEY to "master-secret")
        var deletes = 0
        init { if (bearer != null) values[FcmTokenRegistrar.DEVICE_TOKEN_KEY] = bearer }
        override fun save(key: String, password: String) { values[key] = password }
        override fun load(key: String) = values[key]
        override fun delete(key: String) { values.remove(key); deletes++ }
    }

    private class FakeConnection(private val status: Int, private val body: String = "{\"status\":\"success\"}") :
        HttpURLConnection(URL("https://example.invalid")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        fun json() = Json.parseToJsonElement(sent.toString("UTF-8")).jsonObject
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
