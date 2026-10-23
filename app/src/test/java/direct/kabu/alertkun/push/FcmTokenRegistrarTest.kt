package direct.kabu.alertkun.push

import direct.kabu.alertkun.data.repository.MyDnsSettingsRepository
import direct.kabu.alertkun.security.CredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FcmTokenRegistrarTest {
    @Test fun pendingIsPersistedBeforeNetworkAndBearerIsPreserved() = runBlocking {
        val f = Fixture()
        assertEquals(GatewayRegistrationKind.PENDING, f.register().kind)
        assertNotNull(f.pending.read())
        assertEquals("old-bearer", f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        assertEquals(listOf("registration/begin"), f.endpoints)
        assertEquals(setOf("fcmtoken", "rcvinterval", "clientNonce", "replyKeyHash"), f.connections[0].json().keys)
    }
    @Test fun newRegistrationUsesPrimaryCredentialsWithoutIssuingEarlyBearer() = runBlocking {
        val f = Fixture(bearer = null)
        assertEquals(GatewayRegistrationKind.PENDING, f.register().kind)
        assertNull(f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        assertEquals("fixture-master", f.connections[0].json().getValue("masterid").jsonPrimitive.content)
        assertNull(f.connections[0].getRequestProperty("Authorization"))
    }
    @Test fun matchingReceiptCompletesAndPersistsBearer() = runBlocking {
        val f = Fixture(); f.register(); assertTrue(f.receive())
        f.responses.add(FakeConnection(200, "{\"status\":\"success\",\"deviceToken\":\"${"x".repeat(43)}\"}"))
        assertEquals(GatewayRegistrationKind.SUCCESS, f.register().kind)
        assertEquals("x".repeat(43), f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        assertNull(f.pending.read())
        assertEquals("registration/confirm", f.endpoints.last())
        assertNull(f.connections.last().getRequestProperty("Authorization"))
    }
    @Test fun lostConfirmationResponseKeepsSameRequestAndProofForRetry() = runBlocking {
        val f = Fixture(); f.register(); f.receive()
        val original = f.pending.read()!!
        f.responses.add(FakeConnection(503))
        assertEquals(GatewayRegistrationKind.HTTP_ERROR, f.register().kind)
        assertEquals(original.replyKey, f.pending.read()!!.replyKey)
        f.responses.add(FakeConnection(200, "{\"status\":\"success\",\"deviceToken\":\"${"y".repeat(43)}\"}"))
        assertEquals(GatewayRegistrationKind.SUCCESS, f.register().kind)
        assertEquals(f.connections[1].json(), f.connections[2].json())
    }
    @Test fun invalidOrExpiredOrUnsolicitedMessagesCannotCreateProof() = runBlocking {
        val f = Fixture(); assertFalse(f.receive())
        f.register()
        for ((key,value) in listOf("action" to "CHECK_MAIL", "clientNonce" to "wrong", "tokenHash" to "wrong",
            "challenge" to "bad", "requestId" to "bad")) assertFalse(f.receive(mapOf(key to value)))
        assertNull(f.pending.read()!!.challenge)
        val p = f.pending.read()!!
        assertFalse(f.pending.receive(f.data(), p.createdAt + PendingRegistration.TTL))
        assertFalse(f.pending.receive(f.data(), p.createdAt - 1))
    }
    @Test fun restartCanResumeOnlyMatchingUnexpiredLocalRequest() = runBlocking {
        val f = Fixture(); f.register()
        val reloaded = PendingRegistrationStore(f.pendingCredentials)
        assertEquals(f.pending.read()!!.nonce, reloaded.read()!!.nonce)
        assertTrue(reloaded.receive(f.data(), System.currentTimeMillis()))
        val p = reloaded.read()!!
        assertTrue(p.matches(FcmRegistrationTarget("fixture-master",5,revision=4), "fixture-token", "initial", p.createdAt))
        assertFalse(p.matches(FcmRegistrationTarget("other-master",5,revision=4), "fixture-token", "initial", p.createdAt))
        assertFalse(p.matches(FcmRegistrationTarget("fixture-master",5,revision=5), "fixture-token", "initial", p.createdAt))
        assertFalse(p.matches(FcmRegistrationTarget("fixture-master",5,revision=4), "new-token", "initial", p.createdAt))
        assertFalse(p.matches(FcmRegistrationTarget("fixture-master",5,revision=4), "fixture-token", "new-password", p.createdAt))
    }
    @Test fun configurationOrTokenChangeInvalidatesOldNonce() = runBlocking {
        val f=Fixture(); f.register(); val old=f.data()
        f.responses.add(FakeConnection(202, "{\"status\":\"pending\"}"))
        f.client.register("fixture-master", "fixture-new-token",5,5)
        assertFalse(f.pending.receive(old,System.currentTimeMillis()))
    }
    @Test fun primary401AndNon401FailuresNeverDestroyExistingDeliveryCredentials() = runBlocking {
        val f=Fixture(bearer=null); f.responses.clear(); f.responses.add(FakeConnection(401))
        assertEquals(GatewayRegistrationKind.AUTHENTICATION_ERROR,f.register().kind)
        assertEquals("fixture-password",f.credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
        val g=Fixture(); g.responses.clear();g.responses.add(FakeConnection(503))
        assertEquals(GatewayRegistrationKind.HTTP_ERROR,g.register().kind)
        assertEquals("old-bearer",g.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
    }
    @Test fun malformedConfirmationCannotReplaceBearer() = runBlocking {
        val f=Fixture();f.register();f.receive();f.responses.add(FakeConnection(200,"{\"status\":\"success\"}"))
        assertEquals(GatewayRegistrationKind.RESPONSE_ERROR,f.register().kind)
        assertEquals("old-bearer",f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        assertNotNull(f.pending.read())
    }
    @Test fun observationExcludesAllRegistrationSecrets() = runBlocking {
        val f=Fixture();f.register()
        val text=f.events.toString()
        for(value in listOf("fixture-master","fixture-token","fixture-password","old-bearer",f.pending.read()!!.nonce,
            f.pending.read()!!.replyKey)) assertFalse(text.contains(value))
    }
    @Test fun outOfOrderChallengeMessagesCannotReplaceNewerProof() = runBlocking {
        val f = Fixture(); f.register()
        val old = f.data()
        assertTrue(f.receive(mapOf("challengeVersion" to "2", "challenge" to "d".repeat(43))))
        assertFalse(f.pending.receive(old, System.currentTimeMillis()))
        assertEquals(2, f.pending.read()!!.challengeVersion)
        assertEquals("d".repeat(43), f.pending.read()!!.challenge)
    }
    @Test fun supersededProofWaitsForNewChallengeWithoutDroppingLocalRequest() = runBlocking {
        val f = Fixture(); f.register(); f.receive()
        val nonce = f.pending.read()!!.nonce
        f.responses.add(FakeConnection(425))
        assertEquals(425, f.register().httpStatus)
        assertEquals(nonce, f.pending.read()!!.nonce)
    }
    @Test fun revokedBearerStartsNewPrimaryRequestRatherThanReusingRefreshNonce() = runBlocking {
        val f = Fixture(); f.responses.clear()
        f.responses.add(FakeConnection(401)); f.responses.add(FakeConnection(202, "{\"status\":\"pending\"}"))
        assertEquals(GatewayRegistrationKind.PENDING, f.register().kind)
        assertNull(f.credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        assertNotEquals(f.connections[0].json().getValue("clientNonce"), f.connections[1].json().getValue("clientNonce"))
        assertTrue(f.connections[1].json().containsKey("masterpwd"))
    }
    private class Fixture(bearer:String?="old-bearer") {
        val credentials=MemoryCredentials().apply { save(MyDnsSettingsRepository.CREDENTIAL_KEY,"fixture-password");bearer?.let{save(FcmTokenRegistrar.DEVICE_TOKEN_KEY,it)} }
        val pendingCredentials=MemoryCredentials()
        val pending=PendingRegistrationStore(pendingCredentials)
        val responses=mutableListOf(FakeConnection(202,"{\"status\":\"pending\"}"))
        val endpoints=mutableListOf<String>()
        val connections=mutableListOf<FakeConnection>()
        val events=mutableListOf<Map<String,Any?>>()
        val client=FcmTokenRegistrar(credentials,{_,fields->events.add(fields)},{ endpoint ->
            assertNotNull(pending.read())
            endpoints.add(endpoint); responses[connections.size].also { connections.add(it) }
        },pending)
        suspend fun register()=client.register("fixture-master","fixture-token",5,4)
        fun data():Map<String,String> {
            val p=pending.read() ?: return emptyMap()
            return mapOf("action" to PendingRegistration.VERIFY,"clientNonce" to p.nonce,"tokenHash" to PendingRegistration.sha256(p.token),
                "requestId" to "11111111-1111-4111-8111-111111111111","challenge" to "c".repeat(43), "challengeVersion" to "1")
        }
        fun receive(overrides:Map<String,String> = emptyMap())=pending.receive(data()+overrides,System.currentTimeMillis())
    }
    private class MemoryCredentials:CredentialStore {
        val values=mutableMapOf<String,String>()
        override fun save(key:String,password:String){values[key]=password}
        override fun load(key:String)=values[key]
        override fun delete(key:String){values.remove(key)}
    }
    private class FakeConnection(private val status:Int,private val body:String="{}") : HttpURLConnection(URL("https://example.invalid")) {
        val sent=ByteArrayOutputStream()
        fun json()=Json.parseToJsonElement(sent.toString("UTF-8")).jsonObject
        override fun connect()=Unit
        override fun disconnect()=Unit
        override fun usingProxy()=false
        override fun getOutputStream()=sent
        override fun getResponseCode()=status
        override fun getInputStream()=ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
