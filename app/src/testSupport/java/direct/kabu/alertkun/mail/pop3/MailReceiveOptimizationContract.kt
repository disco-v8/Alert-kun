package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.mail.common.*
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.*
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Identical contracts run on the JVM and Android with a disposable local POP3 server. */
abstract class MailReceiveOptimizationContract {
    private class Store : MonitoringMailStore {
        val mails = mutableListOf<ReceivedMail>()
        var saves = 0
        var failSave = false
        var onSave: (() -> Unit)? = null
        override suspend fun findByRemoteId(accountId: String, remoteMessageId: String) =
            mails.firstOrNull { it.accountId == accountId && it.remoteMessageId == remoteMessageId }
        override suspend fun knownRemoteIds(accountId: String) = mails.filter { it.accountId == accountId }.map { it.remoteMessageId }.toSet()
        override suspend fun receivedMails() = mails.filter { it.processingState == MailProcessingState.RECEIVED }
        override suspend fun saveReceived(mail: ReceivedMail): Boolean {
            onSave?.invoke()
            saves++
            if (failSave) error("Fixture save failure")
            if (findByRemoteId(mail.accountId, mail.remoteMessageId) != null) return false
            mails += mail
            return true
        }
        override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {
            val i = mails.indexOfFirst { it.id == id }
            mails[i] = mails[i].copy(processingState = MailProcessingState.PROCESSED,
                alertTriggered = alertTriggered, matchedRuleId = matchedRuleId, backgroundColor = backgroundColor)
        }
    }

    private val bodyRule = AlertRule("r", "Fixture rule", "ALERT", RuleTarget.BODY, 1, null,
        VibrationPattern.NONE, AlarmMode.ONCE, true)
    private val credentials = object : CredentialStore {
        override fun load(key: String) = "fixture-secret"
        override fun save(key: String, password: String) = error("Read-only fixture")
        override fun delete(key: String) = error("Read-only fixture")
    }
    private fun mail(account: String = "a", uid: String = "uid") = ReceivedMail("$account-$uid", account, uid,
        1_700_000_000_000L, null, emptyMap(), null, null, false, MailProcessingState.PROCESSED, false, null)
    private fun coordinator(store: Store, receiver: MailReceiver, rules: () -> List<AlertRule> = { listOf(bodyRule) },
        alarm: () -> Unit = {}) = MailCheckCoordinator(store,
        object : MonitoringRuleSource { override suspend fun allRules() = rules() }, receiver,
        object : MonitoringAlarmSink { override fun start(event: AlarmEvent) = alarm() })

    @Test fun knownIdentitySkipsBodyRuleEvaluationSaveAndAlarmForPop3AndImapIds() = runBlocking {
        for (uid in listOf("uid", "123:456")) {
            val store = Store().apply { mails += mail(uid = uid) }
            val known = store.mails.single()
            var bodyCalls = 0
            val receiver = object : MailReceiver {
                override suspend fun fetchNewMessages() = listOf(RemoteMail("a", uid, null, null))
                override suspend fun fetchBody(remoteMail: RemoteMail): String? { bodyCalls++; error("Known body") }
            }
            // Evaluating this body-less saved record would yield BodyRequired and fail the poll.
            val result = coordinator(store, receiver, alarm = { fail("Known mail alarm") }).check()
            assertTrue(result.success)
            assertEquals(0, result.checkedCount)
            assertEquals(0, bodyCalls)
            assertEquals(0, store.saves)
            assertEquals(known, store.mails.single())
        }
    }

    @Test fun unknownBodyIsFetchedOnceAndSecondCheckSkips() = runBlocking {
        val store = Store()
        var calls = 0
        val receiver = object : MailReceiver {
            override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
                if ("uid" !in knownIds("a")) { calls++; consume(RemoteMail("a", "uid", "other", null, body = "ALERT", bodyFetched = true)) }
            }
        }
        val check = coordinator(store, receiver)
        assertTrue(check.check().success)
        assertEquals(1, calls); assertEquals(1, store.saves)
        calls = 0
        val second = check.check()
        assertTrue(second.success); assertEquals(0, second.receivedCount); assertEquals(0, second.alertCount)
        assertEquals(0, calls); assertEquals(1, store.saves)
    }

    @Test fun addingBodyRuleAfterHeaderOnlyReceiptDoesNotRevisitSavedMail() = runBlocking {
        val store = Store()
        var rules = emptyList<AlertRule>()
        var calls = 0
        val receiver = object : MailReceiver {
            override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
                if ("uid" !in knownIds("a")) { calls++; consume(RemoteMail("a", "uid", "other", null, body = "ALERT", bodyFetched = true)) }
            }
        }
        val check = coordinator(store, receiver, { rules }, { fail("Retroactive alarm") })
        assertTrue(check.check().success)
        assertTrue(store.mails.single().bodyFetched)
        rules = listOf(bodyRule)
        assertTrue(check.check().success)
        assertEquals(1, calls); assertEquals(1, store.saves)
        assertNull(store.mails.single().matchedRuleId)
    }

    @Test fun sameRemoteIdInAnotherAccountIsNew() = runBlocking {
        val store = Store().apply { mails += mail() }
        var calls = 0
        val receiver = object : MailReceiver {
            override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
                if ("uid" !in knownIds("b")) { calls++; consume(RemoteMail("b", "uid", null, null, body = "ALERT", bodyFetched = true)) }
            }
        }
        assertTrue(coordinator(store, receiver).check().success)
        assertEquals(1, calls); assertEquals(2, store.mails.size)
    }

    @Test fun fetchAndSaveFailuresLeaveUnknownIdentityRetryable() = runBlocking {
        for (saveFailure in listOf(false, true)) {
            val store = Store().apply { failSave = saveFailure }
            var failBody = !saveFailure
            var calls = 0
            val receiver = object : MailReceiver {
                override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
                    calls++
                    if (failBody) error("Fixture fetch failure")
                    consume(RemoteMail("a", "uid", null, null, body = "ALERT", bodyFetched = true))
                }
            }
            val check = coordinator(store, receiver)
            assertFalse(check.check().success)
            assertTrue(store.mails.isEmpty())
            assertEquals(if (saveFailure) 1 else 0, store.saves)
            store.failSave = false; failBody = false
            assertTrue(check.check().success)
            assertEquals(2, calls); assertEquals(1, store.mails.size)
        }
    }

    @Test fun pop3ZeroOneAndFiveBodiesUseOneLoginAndNoSecondCheckRetr() = runBlocking {
        for (count in listOf(0, 1, 5)) {
            Pop3TestServer((1..count).map { "uid-$it" }).use { server ->
                server.bodies = server.remaining().associateWith { "ALERT body" }
                val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials)
                val store = Store()
                val network = NetworkStabilization(object : DefaultNetworkSource {
                    override val state = MutableStateFlow(DefaultNetworkState("fixture", true, true))
                })
                val check = coordinator(store, NetworkStabilizingMailReceiver(receiver, network, false), alarm = {
                    assertEquals("Read session must close before handoff", 1, server.commands.count { it == "QUIT" })
                })
                val first = check.check()
                assertTrue(first.success)
                assertEquals(count, store.mails.size)
                assertEquals(count, server.commands.count { it.startsWith("RETR ") })
                assertEquals(1, server.commands.count { it == "USER" })
                assertEquals(1, server.commands.count { it == "PASS" })
                assertEquals(1, server.commands.count { it == "QUIT" })
                assertEquals(1, server.commands.count { it == "UIDL" })
                assertEquals(0, server.commands.count { it.startsWith("TOP ") })
                server.commands.clear()
                assertTrue(check.check().success)
                assertEquals(1, server.commands.count { it == "USER" })
                assertEquals(1, server.commands.count { it == "QUIT" })
                assertEquals(0, server.commands.count { it.startsWith("RETR ") })
                assertEquals(0, server.commands.count { it.startsWith("TOP ") })
                assertEquals(count, store.mails.size)
            }
        }
    }

    @Test fun pop3KnownFiveAndNewHeaderOnlyMailNeverRetr() = runBlocking {
        Pop3TestServer((1..5).map { "uid-$it" }).use { server ->
            val store = Store().apply { server.remaining().forEach { mails += mail("deletion-test", it) } }
            val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials)
            assertTrue(coordinator(store, receiver).check().success)
            assertEquals(0, store.saves)
            assertEquals(1, server.commands.count { it == "USER" })
            assertTrue(server.commands.contains("UIDL"))
            assertEquals(0, server.commands.count { it.startsWith("TOP ") })
            assertEquals(0, server.commands.count { it.startsWith("RETR ") })
        }
        Pop3TestServer().use { server ->
            val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials)
            val store = Store()
            assertTrue(coordinator(store, receiver, { listOf(bodyRule.copy(target = RuleTarget.SUBJECT)) }).check().success)
            assertEquals(1, store.saves)
            assertTrue(store.mails.single().bodyFetched)
            assertEquals(1, server.commands.count { it.startsWith("RETR ") })
            assertEquals(1, server.commands.count { it == "QUIT" })
        }
    }

    @Test fun pop3KnownFiveOnlyUnknownSixthCanFetchHeadersAndBody() = runBlocking {
        for (bodyRequired in listOf(false, true)) {
            Pop3TestServer((1..6).map { "uid-$it" }).use { server ->
                server.bodies = server.remaining().associateWith { "ALERT" }
                val store = Store().apply {
                    server.remaining().take(5).forEach { mails += mail("deletion-test", it) }
                }
                val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials)
                val network = NetworkStabilization(object : DefaultNetworkSource {
                    override val state = MutableStateFlow(DefaultNetworkState("fixture", true, true))
                })
                val check = coordinator(store, NetworkStabilizingMailReceiver(receiver, network, false), {
                    listOf(if (bodyRequired) bodyRule else bodyRule.copy(target = RuleTarget.SUBJECT))
                })
                val result = check.check()
                assertTrue(result.success)
                assertEquals(1, result.receivedCount)
                assertEquals(1, result.alertCount)
                assertEquals(1, store.saves)
                assertEquals(1, server.commands.count { it == "USER" })
                assertEquals(1, server.commands.count { it == "PASS" })
                assertEquals(1, server.commands.count { it == "UIDL" })
                assertEquals(emptyList<String>(), server.commands.filter { it.startsWith("TOP ") })
                assertEquals(listOf("RETR 6"),
                    server.commands.filter { it.startsWith("RETR ") })
                assertTrue(server.commands.indexOf("UIDL") < server.commands.indexOf("RETR 6"))
                server.commands.clear()
                assertTrue(check.check().success)
                assertEquals(1, server.commands.count { it == "USER" })
                assertEquals(0, server.commands.count { it.startsWith("TOP ") || it.startsWith("RETR ") })
            }
        }
    }

    @Test fun pop3UidResolutionAndKnownLookupFinishWithoutHeaderAccess() = runBlocking {
        Pop3TestServer((1..5).map { "known-$it" }).use { server ->
            val receiver = Pop3MailReceiver(server.account(ServerDeletePolicy.NEVER), "fixture-secret")
            var lookedUp = 0
            receiver.receive({ account ->
                assertEquals("deletion-test", account)
                lookedUp++
                server.remaining().toSet()
            }) { fail("Known message must never be acquired") }
            assertEquals(1, lookedUp)
            assertEquals(1, server.commands.count { it == "UIDL" })
            assertEquals(0, server.commands.count { it.startsWith("UIDL ") })
            assertEquals(0, server.commands.count { it.startsWith("TOP ") || it.startsWith("RETR ") })
            assertEquals(1, server.commands.count { it == "QUIT" })
        }
    }

    @Test fun pop3FailedMissingOrDuplicateUidNeverTouchesAnyHeaderAndNextPollCanRetry() = runBlocking {
        for (failure in listOf("reject", "disconnect", "missing", "duplicate")) {
            Pop3TestServer(listOf("first", "second")).use { server ->
                server.rejectUidl = failure == "reject"
                server.incompleteUidl = failure == "disconnect"
                server.missingUidlNumber = if (failure == "missing") 2 else null
                server.duplicateUidl = failure == "duplicate"
                val store = Store()
                val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials)
                val check = coordinator(store, receiver, { emptyList() })
                assertFalse(check.check().success)
                assertEquals(0, store.saves)
                assertTrue(store.mails.isEmpty())
                assertEquals(0, server.commands.count { it.startsWith("TOP ") || it.startsWith("RETR ") })
                server.rejectUidl = false; server.incompleteUidl = false
                server.missingUidlNumber = null; server.duplicateUidl = false
                server.commands.clear()
                assertTrue(check.check().success)
                assertEquals(2, store.mails.size)
                assertEquals(1, server.commands.count { it == "USER" })
                assertEquals(0, server.commands.count { it.startsWith("TOP ") })
                assertEquals(2, server.commands.count { it.startsWith("RETR ") })
            }
        }
    }

    @Test fun pop3ReadFailureClosesAndNextCheckReconnects() = runBlocking {
        Pop3TestServer().use { server ->
            server.disconnectOnRetrNumber = 1
            server.bodies = server.remaining().associateWith { "ALERT" }
            val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials)
            val store = Store()
            val check = coordinator(store, receiver)
            assertFalse(check.check().success)
            assertTrue(store.mails.isEmpty())
            assertEquals(1, server.commands.count { it == "USER" })
            server.disconnectOnRetrNumber = null
            assertTrue(check.check().success)
            assertEquals(2, server.commands.count { it == "USER" })
            assertEquals(1, store.mails.size)
            assertTrue(server.commands.contains("QUIT"))
        }
    }

    @Test fun pop3BodyDisconnectPreservesSavedMailAndNextPollDoesNotRefetchIt() = runBlocking {
        Pop3TestServer(listOf("first", "second")).use { server ->
            server.bodies = server.remaining().associateWith { "ALERT" }
            server.disconnectOnRetrNumber = 2
            val decisions = mutableListOf<String>()
            val network = NetworkStabilization(object : DefaultNetworkSource {
                override val state = MutableStateFlow(DefaultNetworkState("fixture", true, true))
            }, observation = { event, fields ->
                if (event == "NETWORK_RETRY_ALLOWED" || event == "NETWORK_RETRY_SKIPPED") decisions += "$event:${fields["reason"]}"
            }, pause = {})
            val receiver = NetworkStabilizingMailReceiver(
                AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials), network, false)
            val store = Store()
            val check = coordinator(store, receiver)
            val interrupted = check.check()
            // Streaming RETR preserves the EOF transport cause, so the existing bounded retry applies.
            assertFalse(interrupted.success)
            assertEquals(listOf("NETWORK_RETRY_ALLOWED:TRANSIENT_NETWORK", "NETWORK_RETRY_ALLOWED:TRANSIENT_NETWORK",
                "NETWORK_RETRY_SKIPPED:MAX_RETRIES"), decisions)
            assertEquals(1, store.saves)
            assertEquals(MailProcessingState.RECEIVED, store.mails.single().processingState)
            server.disconnectOnRetrNumber = null
            val result = check.check()
            assertTrue(result.success)
            assertEquals(2, store.saves)
            assertEquals(2, store.mails.size)
            assertEquals(4, server.commands.count { it == "USER" })
            assertEquals(5, server.commands.count { it.startsWith("RETR ") })
            assertEquals(1, result.alertCount)
        }
    }

    @Test fun pop3SaveFailureAndCancellationCloseScopeAndAllowReconnect() = runBlocking {
        Pop3TestServer().use { server ->
            val receiver = Pop3MailReceiver(server.account(ServerDeletePolicy.NEVER), "fixture-secret")
            val store = Store().apply { failSave = true }
            val check = coordinator(store, receiver, { emptyList() })
            assertFalse(check.check().success)
            assertEquals(1, server.commands.count { it == "QUIT" })
            store.failSave = false
            assertTrue(check.check().success)
            assertEquals(2, server.commands.count { it == "QUIT" })
            try {
                receiver.receive({ emptySet() }) { throw CancellationException("Fixture cancellation") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(3, server.commands.count { it == "QUIT" })
            receiver.withReadSession { assertEquals(1, receiver.fetchNewMessages().size) }
            assertEquals(4, server.commands.count { it == "USER" })
            assertEquals(4, server.commands.count { it == "QUIT" })
        }
    }
    @Test fun pop3ListSizeBoundaryAndOversizedTerminalArePersistent() = runBlocking {
        for (size in listOf(16777215L, 16777216L, 16777217L, 104857600L)) {
            Pop3TestServer().use { server ->
                server.reportedSizes = server.remaining().associateWith { size }
                val store = Store()
                val check = coordinator(store, AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials))
                assertTrue(check.check().success)
                val mail = store.mails.single()
                assertEquals(if (size > 16777216) MailProcessingState.UNSUPPORTED else MailProcessingState.PROCESSED, mail.processingState)
                assertEquals(if (size > 16777216) 0 else 1, server.commands.count { it.startsWith("RETR ") })
                assertEquals(0, server.commands.count { it.startsWith("TOP ") })
                assertTrue(server.commands.indexOf("UIDL") < server.commands.indexOf("LIST"))
                if (size > 16777216) {
                    assertFalse(mail.bodyFetched); assertFalse(mail.alertTriggered)
                    assertNull(mail.subject); assertNull(mail.from); assertNull(mail.matchedRuleId)
                }
                server.commands.clear()
                assertTrue(check.check().success)
                assertEquals(1, store.saves)
                assertEquals(0, server.commands.count { it.startsWith("RETR ") || it.startsWith("TOP ") })
            }
        }
    }

    @Test fun invalidListNeverFetchesOrPersistsAndCanRetry() = runBlocking {
        for (reject in listOf(true, false)) {
            Pop3TestServer().use { server ->
                server.rejectList = reject; server.malformedList = !reject
                val store = Store()
                val check = coordinator(store, AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials))
                assertFalse(check.check().success)
                assertTrue(store.mails.isEmpty())
                assertFalse(server.commands.any { it.startsWith("RETR ") || it.startsWith("TOP ") })
                server.rejectList = false; server.malformedList = false
                assertTrue(check.check().success)
                assertEquals(1, store.saves)
            }
        }
    }

    @Test fun cameraAttachmentsStreamThroughOneSessionAndOnlyOneRawScratchFileAtATime() = runBlocking {
        val fixture=java.io.File.createTempFile("camera-fixture-",".eml")
        val directory=java.io.File.createTempFile("camera-scratch-","").apply { check(delete());check(mkdir()) }
        try {
            MimeFixtures.write(fixture,"attachment")
            assertTrue(fixture.length() in 10*1024*1024L..16777216L)
            Pop3TestServer(listOf("camera-1","camera-2")).use { server ->
                server.rawFiles=server.remaining().associateWith { fixture }
                val store=Store().apply { onSave={
                    assertEquals(1,directory.listFiles()!!.size)
                    assertEquals(fixture.length(),directory.listFiles()!!.single().length())
                } }
                val receiver=AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)),credentials,temporaryDirectory=directory)
                assertTrue(coordinator(store,receiver,{emptyList()}).check().success)
                assertEquals(2,store.mails.size)
                assertTrue(store.mails.all { it.bodyFetched && it.body=="x" && it.processingState==MailProcessingState.PROCESSED })
                assertEquals(1,server.commands.count { it=="USER" })
                assertEquals(2,server.commands.count { it.startsWith("RETR ") })
                assertEquals(0,server.commands.count { it.startsWith("TOP ") })
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        } finally { assertTrue(fixture.delete());assertTrue(directory.delete()) }
    }

    @Test fun oversizedMiddleMailDoesNotBlockHigherPriorityLaterMail() = runBlocking {
        Pop3TestServer(listOf("a","b","c","d")).use { server ->
            server.reportedSizes = mapOf("b" to 20971520L)
            server.bodies = mapOf("a" to "LOW", "c" to "HIGH", "d" to "normal")
            val store = Store()
            var alarms = 0
            val low = bodyRule.copy(id = "low", searchText = "LOW", priority = 20)
            val high = bodyRule.copy(id = "high", searchText = "HIGH", priority = 1)
            val check = coordinator(store, AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), credentials),
                { listOf(low, high) }, { alarms++ })
            assertTrue(check.check().success)
            assertEquals(1, alarms)
            assertEquals(4, store.mails.size)
            assertEquals("c",store.mails.single { it.alertTriggered }.remoteMessageId)
            assertEquals(MailProcessingState.UNSUPPORTED,store.mails.single { it.remoteMessageId == "b" }.processingState)
            assertEquals(listOf("RETR 1","RETR 3","RETR 4"),server.commands.filter { it.startsWith("RETR ") })
            assertTrue(check.check().success)
            assertEquals(1,alarms)
            assertEquals(4,store.saves)
        }
    }
}
