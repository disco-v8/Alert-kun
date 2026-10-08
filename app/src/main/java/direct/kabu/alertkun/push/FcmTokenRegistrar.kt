package direct.kabu.alertkun.push

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only posts registration data. Uses platform TLS validation without custom trust settings. */
class FcmTokenRegistrar internal constructor(
    private val openConnection: () -> HttpURLConnection = {
        URL(GATEWAY_URL).openConnection() as HttpsURLConnection
    },
) {
    suspend fun register(masterId: String, token: String, intervalMinutes: Int): GatewayRegistrationResult =
        withContext(Dispatchers.IO) {
            if (masterId.isBlank()) return@withContext GatewayRegistrationResult(GatewayRegistrationKind.SKIPPED)
            require(token.isNotBlank())
            require(intervalMinutes in 1..1500)
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection()
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val payload = buildJsonObject {
                    put("masterid", masterId)
                    put("fcmtoken", token)
                    put("rcvinterval", intervalMinutes)
                }.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    GatewayRegistrationResult(GatewayRegistrationKind.HTTP_ERROR, status)
                } else {
                    // Limit parsing memory and never log a server body (it could echo credentials).
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
                        try { Json.parseToJsonElement(it) as? JsonObject }
                        catch (_: IllegalArgumentException) { null }
                    }
                    val kind = when {
                        parsed == null -> GatewayRegistrationKind.JSON_ERROR
                        parsed["status"] == JsonPrimitive("success") -> GatewayRegistrationKind.SUCCESS
                        else -> GatewayRegistrationKind.RESPONSE_ERROR
                    }
                    GatewayRegistrationResult(kind, status)
                }
            } catch (_: UnknownHostException) {
                GatewayRegistrationResult(GatewayRegistrationKind.DNS_ERROR)
            } catch (_: ConnectException) {
                GatewayRegistrationResult(GatewayRegistrationKind.CONNECTION_ERROR)
            } catch (_: SocketTimeoutException) {
                GatewayRegistrationResult(GatewayRegistrationKind.TIMEOUT)
            } catch (_: SSLException) {
                GatewayRegistrationResult(GatewayRegistrationKind.TLS_ERROR)
            } catch (_: IOException) {
                GatewayRegistrationResult(GatewayRegistrationKind.NETWORK_ERROR)
            } finally {
                connection?.disconnect()
            }
        }

    companion object {
        private const val GATEWAY_URL = "https://fcm.gpg.jp:8119/alertkun-api/set-interval"
    }
}

enum class GatewayRegistrationKind { SUCCESS, SKIPPED, DNS_ERROR, CONNECTION_ERROR, TIMEOUT, TLS_ERROR, NETWORK_ERROR, HTTP_ERROR, JSON_ERROR, RESPONSE_ERROR }
data class GatewayRegistrationResult(val kind: GatewayRegistrationKind, val httpStatus: Int? = null)
