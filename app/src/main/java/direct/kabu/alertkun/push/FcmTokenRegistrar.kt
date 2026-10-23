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

/** Two-phase registration. Pending state is durable before the first HTTPS request. */
class FcmTokenRegistrar internal constructor(
    private val credentials: CredentialStore,
    private val observation: ObservationSink = { _, _ -> },
    private val openConnection: (String) -> HttpURLConnection = {
        URL(BASE_URL + it).openConnection() as HttpsURLConnection
    },
    private val pending: PendingRegistrationStore = PendingRegistrationStore(credentials),
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal fun prepare(target: FcmRegistrationTarget, token: String): PendingRegistration {
        val identity = credentials.load(IDENTITY_VERSION_KEY) ?: "initial"
        pending.read()?.takeIf { it.matches(target, token, identity, now()) }?.let { return it }
        return PendingRegistration(target.masterId, token, target.intervalMinutes, target.revision, identity,
            PendingRegistration.randomSecret(), PendingRegistration.randomSecret(), now()).also { pending.save(it) }
    }

    suspend fun register(masterId: String, token: String, intervalMinutes: Int, revision: Long = 0): GatewayRegistrationResult =
        withContext(Dispatchers.IO) {
            if (masterId.isBlank()) return@withContext GatewayRegistrationResult(GatewayRegistrationKind.SKIPPED)
            require(token.isNotBlank() && token.length <= 4096)
            require(intervalMinutes in 1..1500)
            try {
                val target = FcmRegistrationTarget(masterId, intervalMinutes, revision = revision)
                val p = prepare(target, token)
                if (p.challenge != null && p.requestId != null) confirm(p) else begin(p)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (_: Exception) { GatewayRegistrationResult(GatewayRegistrationKind.STORAGE_ERROR) }
        }

    private fun begin(initial: PendingRegistration): GatewayRegistrationResult {
        var p = initial
        var bearer = credentials.load(DEVICE_TOKEN_KEY)?.takeIf { it.isNotBlank() }
        var response = beginRequest(p, bearer)
        if (response.result.httpStatus == 401 && bearer != null) {
            credentials.delete(DEVICE_TOKEN_KEY)
            pending.clearIf(p.nonce)
            p = prepare(FcmRegistrationTarget(p.masterId, p.interval, revision = p.revision), p.token)
            bearer = null
            response = beginRequest(p, bearer)
        }
        // A pending response is not an authentication failure and never replaces an existing bearer.
        event("GATEWAY_REGISTRATION_BEGIN", p.interval, response.result)
        return response.result
    }

    private fun beginRequest(p: PendingRegistration, bearer: String?): Response {
        val password = if (bearer == null) credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY) else null
        if (bearer == null && password.isNullOrEmpty()) return Response(GatewayRegistrationResult(GatewayRegistrationKind.NOT_CONFIGURED))
        return post("registration/begin", buildJsonObject {
            if (bearer == null) { put("masterid", p.masterId); put("masterpwd", password!!) }
            put("fcmtoken", p.token); put("rcvinterval", p.interval)
            put("clientNonce", p.nonce); put("replyKeyHash", PendingRegistration.sha256(p.replyKey))
        }, bearer)
    }

    private fun confirm(p: PendingRegistration): GatewayRegistrationResult {
        val response = post("registration/confirm", buildJsonObject {
            put("requestId", p.requestId!!); put("challenge", p.challenge!!)
            put("challengeVersion", p.challengeVersion)
            put("clientNonce", p.nonce); put("replyKey", p.replyKey); put("fcmtoken", p.token)
        }, null)
        var result = response.result
        if (result.kind == GatewayRegistrationKind.SUCCESS) {
            val issued = (response.json?.get("deviceToken") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (issued?.matches(Regex("[A-Za-z0-9_-]{43}")) != true) {
                result = GatewayRegistrationResult(GatewayRegistrationKind.RESPONSE_ERROR, result.httpStatus)
            } else {
                // If save/readback fails, retain request and proof so the identical issued bearer can be recovered.
                credentials.save(DEVICE_TOKEN_KEY, issued)
                check(credentials.load(DEVICE_TOKEN_KEY) == issued)
                pending.clearIf(p.nonce)
            }
        } else if (result.httpStatus in listOf(403, 409, 410)) {
            pending.clearIf(p.nonce)
        }
        event("GATEWAY_REGISTRATION_CONFIRM", p.interval, result)
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
            if (status != 200 && status != 202) return Response(GatewayRegistrationResult(
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
                status == 202 && parsed["status"] == JsonPrimitive("pending") -> GatewayRegistrationKind.PENDING
                status == 200 && parsed["status"] == JsonPrimitive("success") -> GatewayRegistrationKind.SUCCESS
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

    private fun event(name: String, interval: Int, result: GatewayRegistrationResult) {
        runCatching { observation(name, mapOf("interval" to interval,
            "httpStatus" to result.httpStatus, "category" to result.kind.name)) }
    }
    private class Response(val result: GatewayRegistrationResult, val json: JsonObject? = null)
    companion object {
        const val DEVICE_TOKEN_KEY = "fcm_gateway_device_token_v1"
        internal const val IDENTITY_VERSION_KEY = "gateway_identity_version_v1"
        private const val BASE_URL = "https://fcm.gpg.jp:8119/alertkun-api/"
    }
}
enum class GatewayRegistrationKind { SUCCESS, PENDING, SKIPPED, NOT_CONFIGURED, AUTHENTICATION_ERROR, STORAGE_ERROR,
    DNS_ERROR, CONNECTION_ERROR, TIMEOUT, TLS_ERROR, NETWORK_ERROR, HTTP_ERROR, JSON_ERROR, RESPONSE_ERROR }
data class GatewayRegistrationResult(val kind: GatewayRegistrationKind, val httpStatus: Int? = null)
