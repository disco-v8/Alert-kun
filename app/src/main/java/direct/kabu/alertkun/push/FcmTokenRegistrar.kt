package direct.kabu.alertkun.push

import direct.kabu.alertkun.data.repository.MyDnsSettingsRepository
import direct.kabu.alertkun.monitoring.ObservationSink
import direct.kabu.alertkun.security.CredentialStore
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Platform HTTPS only. Secrets never enter result objects or observation metadata. */
class FcmTokenRegistrar internal constructor(
    private val credentials: CredentialStore,
    private val observation: ObservationSink = { _, _ -> },
    private val openConnection: (String) -> HttpURLConnection = {
        URL(BASE_URL + it).openConnection() as HttpsURLConnection
    },
) {
    suspend fun register(masterId: String, token: String, intervalMinutes: Int): GatewayRegistrationResult =
        withContext(Dispatchers.IO) {
            if (masterId.isBlank()) return@withContext GatewayRegistrationResult(GatewayRegistrationKind.SKIPPED)
            require(token.isNotBlank())
            require(intervalMinutes in 1..1500)
            try {
                var bearer = ensureGatewayAuthenticated(masterId, token, intervalMinutes, 0)
                if (bearer.token == null) return@withContext bearer.result
                var result = setInterval(bearer.token, token, intervalMinutes, 0)
                if (result.httpStatus == 401) {
                    credentials.delete(DEVICE_TOKEN_KEY)
                    event("GATEWAY_AUTH_RETRY", intervalMinutes, 1, result)
                    bearer = ensureGatewayAuthenticated(masterId, token, intervalMinutes, 1)
                    if (bearer.token == null) return@withContext bearer.result
                    result = setInterval(bearer.token, token, intervalMinutes, 1)
                    if (result.httpStatus == 401) credentials.delete(DEVICE_TOKEN_KEY)
                }
                result
            } catch (_: Exception) {
                // Credential persistence/read failures are not network authentication failures.
                val result = GatewayRegistrationResult(GatewayRegistrationKind.STORAGE_ERROR)
                event("GATEWAY_AUTH_FAILURE", intervalMinutes, 0, result)
                result
            }
        }

    private fun ensureGatewayAuthenticated(masterId: String, fcmToken: String, interval: Int, retry: Int): Authentication {
        credentials.load(DEVICE_TOKEN_KEY)?.takeIf { it.isNotBlank() }?.let {
            return Authentication(it, GatewayRegistrationResult(GatewayRegistrationKind.SUCCESS))
        }
        val password = credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY)
        if (password.isNullOrEmpty()) return Authentication(null, GatewayRegistrationResult(GatewayRegistrationKind.NOT_CONFIGURED))
        event("GATEWAY_AUTH_START", interval, retry)
        val response = post("auth", buildJsonObject {
            put("masterid", masterId)
            put("masterpwd", password)
            put("fcmtoken", fcmToken)
        }, null)
        val issued = (response.json?.get("deviceToken") as? JsonPrimitive)?.takeIf { it.isString }?.content
        var result = response.result
        if (result.kind == GatewayRegistrationKind.SUCCESS) {
            if (issued.isNullOrBlank() || issued.length > 1024 || issued.any { it <= ' ' || it.code >= 127 }) {
                result = GatewayRegistrationResult(GatewayRegistrationKind.RESPONSE_ERROR, result.httpStatus)
            } else {
                credentials.save(DEVICE_TOKEN_KEY, issued)
                check(credentials.load(DEVICE_TOKEN_KEY) == issued)
                event("GATEWAY_AUTH_SUCCESS", interval, retry, result)
                return Authentication(issued, result)
            }
        }
        event("GATEWAY_AUTH_FAILURE", interval, retry, result)
        return Authentication(null, result)
    }

    private fun setInterval(bearer: String, token: String, interval: Int, retry: Int): GatewayRegistrationResult {
        event("GATEWAY_SET_INTERVAL_START", interval, retry)
        val result = post("set-interval", buildJsonObject {
            put("fcmtoken", token)
            put("rcvinterval", interval)
        }, bearer).result
        event(if (result.kind == GatewayRegistrationKind.SUCCESS) "GATEWAY_SET_INTERVAL_SUCCESS" else "GATEWAY_SET_INTERVAL_FAILURE",
            interval, retry, result)
        return result
    }

    private fun post(endpoint: String, payload: JsonObject, bearer: String?): Response {
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(endpoint)
            require(connection.url.protocol == "https")
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status != 200) return Response(GatewayRegistrationResult(
                if (status == 401) GatewayRegistrationKind.AUTHENTICATION_ERROR else GatewayRegistrationKind.HTTP_ERROR, status))
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8193)
                var count = 0
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > 8192) null else String(buffer, 0, count)
            }
            val parsed = body?.let {
                try { Json.parseToJsonElement(it) as? JsonObject } catch (_: IllegalArgumentException) { null }
            }
            val kind = when {
                parsed == null -> GatewayRegistrationKind.JSON_ERROR
                parsed["status"] == JsonPrimitive("success") -> GatewayRegistrationKind.SUCCESS
                else -> GatewayRegistrationKind.RESPONSE_ERROR
            }
            return Response(GatewayRegistrationResult(kind, status), parsed)
        } catch (_: UnknownHostException) { return Response(GatewayRegistrationResult(GatewayRegistrationKind.DNS_ERROR))
        } catch (_: ConnectException) { return Response(GatewayRegistrationResult(GatewayRegistrationKind.CONNECTION_ERROR))
        } catch (_: SocketTimeoutException) { return Response(GatewayRegistrationResult(GatewayRegistrationKind.TIMEOUT))
        } catch (_: SSLException) { return Response(GatewayRegistrationResult(GatewayRegistrationKind.TLS_ERROR))
        } catch (_: IOException) { return Response(GatewayRegistrationResult(GatewayRegistrationKind.NETWORK_ERROR))
        } finally { connection?.disconnect() }
    }

    private fun event(name: String, interval: Int, retry: Int, result: GatewayRegistrationResult? = null) {
        runCatching { observation(name, mapOf("interval" to interval, "retryNumber" to retry,
            "httpStatus" to result?.httpStatus, "category" to result?.kind?.name)) }
    }

    private class Authentication(val token: String?, val result: GatewayRegistrationResult)
    private class Response(val result: GatewayRegistrationResult, val json: JsonObject? = null)

    companion object {
        const val DEVICE_TOKEN_KEY = "fcm_gateway_device_token_v1"
        private const val BASE_URL = "https://fcm.gpg.jp:8119/alertkun-api/"
    }
}

enum class GatewayRegistrationKind { SUCCESS, SKIPPED, NOT_CONFIGURED, AUTHENTICATION_ERROR, STORAGE_ERROR,
    DNS_ERROR, CONNECTION_ERROR, TIMEOUT, TLS_ERROR, NETWORK_ERROR, HTTP_ERROR, JSON_ERROR, RESPONSE_ERROR }
data class GatewayRegistrationResult(val kind: GatewayRegistrationKind, val httpStatus: Int? = null)
