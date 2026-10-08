package direct.kabu.alertkun.push

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
    @Test fun postsEscapedValuesAndConfiguredInterval() = runBlocking {
        val connection = FakeConnection(200, "{\"status\":\"success\"}")
        val result = FcmTokenRegistrar { connection }.register("test\"master", "dummy-token", 1500)
        assertEquals(GatewayRegistrationKind.SUCCESS, result.kind)
        val json = Json.parseToJsonElement(connection.sent.toString("UTF-8")).jsonObject
        assertEquals(setOf("masterid", "fcmtoken", "rcvinterval"), json.keys)
        assertEquals("test\"master", json.getValue("masterid").jsonPrimitive.content)
        assertEquals("dummy-token", json.getValue("fcmtoken").jsonPrimitive.content)
        assertEquals("1500", json.getValue("rcvinterval").jsonPrimitive.content)
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun requiresBoth2xxAndSuccessJson() = runBlocking {
        listOf(
            Triple(503, "{\"status\":\"success\"}", GatewayRegistrationKind.HTTP_ERROR),
            Triple(302, "{\"status\":\"success\"}", GatewayRegistrationKind.HTTP_ERROR),
            Triple(200, "invalid", GatewayRegistrationKind.JSON_ERROR),
            Triple(204, "", GatewayRegistrationKind.JSON_ERROR),
            Triple(200, "[]", GatewayRegistrationKind.JSON_ERROR),
            Triple(200, "{\"status\":\"failed\"}", GatewayRegistrationKind.RESPONSE_ERROR),
            Triple(200, "{}", GatewayRegistrationKind.RESPONSE_ERROR),
            Triple(201, "{\"status\":\"success\"}", GatewayRegistrationKind.SUCCESS),
            Triple(200, " ".repeat(8193), GatewayRegistrationKind.JSON_ERROR),
        ).forEach { (status, body, expected) ->
            val connection = FakeConnection(status, body)
            assertEquals(expected, FcmTokenRegistrar { connection }.register("test", "dummy", 3).kind)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun networkFailureIsReturned() = runBlocking {
        assertEquals(GatewayRegistrationKind.NETWORK_ERROR,
            FcmTokenRegistrar { throw IOException("test") }.register("test", "dummy", 1).kind)
    }

    @Test fun emptyMasterIdNeverOpensConnection() = runBlocking {
        assertEquals(GatewayRegistrationKind.SKIPPED,
            FcmTokenRegistrar { error("Must not connect") }.register("  ", "dummy", 3).kind)
    }

    @Test fun classifiesNetworkFailuresWithoutExceptionText() = runBlocking {
        listOf(
            java.net.UnknownHostException("secret") to GatewayRegistrationKind.DNS_ERROR,
            java.net.ConnectException("secret") to GatewayRegistrationKind.CONNECTION_ERROR,
            java.net.SocketTimeoutException("secret") to GatewayRegistrationKind.TIMEOUT,
            javax.net.ssl.SSLHandshakeException("secret") to GatewayRegistrationKind.TLS_ERROR,
        ).forEach { (exception, kind) ->
            assertEquals(kind, FcmTokenRegistrar { throw exception }.register("test", "dummy", 3).kind)
        }
    }

    @Test fun shortTokensAreNeverFullyLogged() {
        assertEquals("[masked] len=5", FcmRegistrationCoordinator.maskedToken("dummy"))
        assertEquals("abcdef...wxyz len=26", FcmRegistrationCoordinator.maskedToken("abcdefghijklmnopqrstuvwxyz"))
    }

    private class FakeConnection(private val status: Int, private val body: String) :
        HttpURLConnection(URL("https://example.invalid")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
