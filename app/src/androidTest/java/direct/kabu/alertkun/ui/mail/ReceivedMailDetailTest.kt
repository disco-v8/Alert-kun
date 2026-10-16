package direct.kabu.alertkun.ui.mail

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.R
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.ui.main.formatMonitoringTimestamp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReceivedMailDetailTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private fun mail(body: String?) = ReceivedMail("detail", "fixture", "fixture-uid", 1_700_000_000_000,
        "監視担当 <sender@example.invalid>", emptyMap(), "Detail subject", body, body != null,
        MailProcessingState.RECEIVED, false, null)

    @Test fun storedFieldsAndMultilineBodyAreDisplayed() {
        val mail = mail("first line\n日本語の本文\nlast line")
        compose.setContent { ReceivedMailDetailContent(mail.id, ReceivedMailUiState(listOf(mail), loading = false), {}) }
        compose.onNodeWithTag("mail_detail_subject").assertTextEquals(mail.subject!!)
        compose.onNodeWithTag("mail_detail_from").assertTextEquals(application.getString(R.string.received_mail_from, mail.from))
        compose.onNodeWithTag("mail_detail_timestamp").assertTextEquals(application.getString(R.string.received_mail_timestamp,
            formatMonitoringTimestamp(mail.receivedAtEpochMillis)))
        compose.onNodeWithTag("mail_detail_body").assertTextEquals(mail.body!!).assertIsDisplayed()
    }

    @Test fun longBodyRemainsCompleteAndScrollable() {
        val body = (1..1500).joinToString("\n") { "Line $it: long saved message" }
        val mail = mail(body)
        compose.setContent { ReceivedMailDetailContent(mail.id, ReceivedMailUiState(listOf(mail), loading = false), {}) }
        compose.onNodeWithTag("mail_detail_body").assertTextEquals(body)
        compose.onNodeWithTag("mail_detail").performTouchInput { swipeUp() }
        val range = compose.onNodeWithTag("mail_detail").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.value() > 0f)
    }

    @Test fun emptyBodyShowsPlaceholder() = checkBodyPlaceholder("")
    @Test fun unfetchedBodyShowsPlaceholder() = checkBodyPlaceholder(null)

    private fun checkBodyPlaceholder(body: String?) {
        val mail = mail(body)
        compose.setContent { ReceivedMailDetailContent(mail.id, ReceivedMailUiState(listOf(mail), loading = false), {}) }
        compose.onNodeWithTag("mail_detail_body").assertTextEquals(application.getString(R.string.received_mail_no_body))
    }

    @Test fun missingMailIsSafeAndBackWorks() {
        var backed = false
        compose.setContent { ReceivedMailDetailContent("missing", ReceivedMailUiState(loading = false), { backed = true }) }
        compose.onNodeWithTag("mail_detail_missing").assertIsDisplayed()
        compose.onNodeWithTag("mail_detail_back").performClick()
        assertTrue(backed)
    }

    @Test fun deletionWhileReadingRemovesContentFromRoomFlowAndList() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        try {
            val repository = MailRepository(database.receivedMailDao())
            val mail = mail("Saved body to be removed")
            runBlocking { repository.saveReceived(mail); repository.finalizeProcessing(mail.id, false, null) }
            val vm = ReceivedMailViewModel(application, repository)
            compose.setContent { ReceivedMailDetailScreen(mail.id, {}, vm) }
            compose.waitUntil(5_000) { vm.state.value.mails.size == 1 }
            compose.onNodeWithTag("mail_detail_body").assertTextEquals(mail.body!!)
            runBlocking { database.receivedMailDao().redactLocalMails(listOf(mail.id)) }
            compose.waitUntil(5_000) { !vm.state.value.loading && vm.state.value.mails.isEmpty() }
            compose.onNodeWithTag("mail_detail_missing").assertIsDisplayed()
            compose.onNodeWithTag("mail_detail_body").assertDoesNotExist()
        } finally { database.close() }
    }
}
