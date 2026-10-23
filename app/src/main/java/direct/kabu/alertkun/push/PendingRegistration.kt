package direct.kabu.alertkun.push

import direct.kabu.alertkun.security.CredentialStore
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.*

/** One encrypted, bounded request. Never put this object or its fields into logs/WorkManager data. */
internal class PendingRegistration(
    val masterId: String, val token: String, val interval: Int, val revision: Long,
    val identityVersion: String, val nonce: String, val replyKey: String, val createdAt: Long,
    val requestId: String? = null, val challenge: String? = null, val challengeVersion: Int = 0,
) {
    fun validAt(now: Long) = now >= createdAt && now - createdAt < TTL
    fun matches(target: FcmRegistrationTarget, currentToken: String, identity: String, now: Long) =
        validAt(now) && masterId == target.masterId && interval == target.intervalMinutes &&
            revision == target.revision && token == currentToken && identityVersion == identity
    fun accepts(data: Map<String, String>, now: Long) = validAt(now) &&
        data["action"] == VERIFY && data["clientNonce"] == nonce && data["tokenHash"] == sha256(token) &&
        data["requestId"]?.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) == true &&
        (requestId == null || requestId == data["requestId"]) &&
        data["challengeVersion"]?.toIntOrNull()?.let { it in 1..3 && it > challengeVersion } == true &&
        data["challenge"]?.matches(Regex("[A-Za-z0-9_-]{43}")) == true
    fun withReceipt(id: String, value: String, version: Int) = PendingRegistration(masterId, token, interval, revision,
        identityVersion, nonce, replyKey, createdAt, id, value, version)

    companion object {
        const val VERIFY = "VERIFY_REGISTRATION"
        const val TTL = 30 * 60_000L
        fun randomSecret(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            // java.util.Base64 is available from minSdk 26.
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal class PendingRegistrationStore(private val credentials: CredentialStore) {
    fun read(): PendingRegistration? = synchronized(lock) {
        try {
            val text = credentials.load(KEY) ?: return@synchronized null
            require(text.length <= 16384)
            val j = Json.parseToJsonElement(text).jsonObject
            fun s(key: String) = j.getValue(key).jsonPrimitive.content
            PendingRegistration(s("masterId"), s("token"), s("interval").toInt(), s("revision").toLong(),
                s("identityVersion"), s("nonce"), s("replyKey"), s("createdAt").toLong(),
                j["requestId"]?.jsonPrimitive?.content, j["challenge"]?.jsonPrimitive?.content,
                j["challengeVersion"]?.jsonPrimitive?.int ?: 0).also {
                require(it.masterId.isNotBlank() && it.masterId.length <= 128 && it.token.isNotBlank() && it.token.length <= 4096)
                require(it.interval in 1..1500 && it.nonce.matches(Regex("[A-Za-z0-9_-]{43}")) &&
                    it.replyKey.matches(Regex("[A-Za-z0-9_-]{43}")))
            }
        } catch (_: Exception) { clear(); null }
    }
    fun save(p: PendingRegistration) = synchronized(lock) {
        val text = buildJsonObject {
            put("masterId", p.masterId); put("token", p.token); put("interval", p.interval); put("revision", p.revision)
            put("identityVersion", p.identityVersion); put("nonce", p.nonce); put("replyKey", p.replyKey); put("createdAt", p.createdAt)
            p.requestId?.let { put("requestId", it) }; p.challenge?.let { put("challenge", it) }
            put("challengeVersion", p.challengeVersion)
        }.toString()
        require(text.length <= 16384)
        credentials.save(KEY, text)
        check(credentials.load(KEY) == text)
    }
    fun receive(data: Map<String, String>, now: Long): Boolean = synchronized(lock) {
        val p = read() ?: return@synchronized false
        if (!p.accepts(data, now)) return@synchronized false
        save(p.withReceipt(data.getValue("requestId"), data.getValue("challenge"), data.getValue("challengeVersion").toInt()))
        true
    }
    fun clear() = synchronized(lock) { credentials.delete(KEY) }
    fun clearIf(nonce: String) = synchronized(lock) { if (read()?.nonce == nonce) clear() }
    companion object {
        const val PREFERENCES = "gateway_registration_pending"
        private const val KEY = "pending_v1"
        private val lock = Any()
    }
}
