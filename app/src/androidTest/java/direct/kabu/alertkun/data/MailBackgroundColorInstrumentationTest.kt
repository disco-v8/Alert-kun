package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.data.repository.RuleRepository
import direct.kabu.alertkun.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MailBackgroundColorInstrumentationTest {
    @Test fun ruleRoundTripEditCopyAndDeletionPreserveMailSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build()
        try {
            val rules = RuleRepository(db.ruleDao())
            val mails = MailRepository(db.receivedMailDao())
            val red = AlertRule("r", "Rule", "WARN", RuleTarget.SUBJECT, 1, null,
                VibrationPattern.NONE, AlarmMode.ONCE, true, MailBackgroundColor.RED)
            rules.upsert(red)
            assertEquals(MailBackgroundColor.RED, rules.get(red.id)!!.backgroundColor)
            rules.upsert(rules.get(red.id)!!.copy(id = "copy", name = "Copy"))
            assertEquals(MailBackgroundColor.RED, rules.get("copy")!!.backgroundColor)
            val mail = ReceivedMail("m", "a", "uid", 1, null, emptyMap(), "WARN", null, false,
                MailProcessingState.RECEIVED, false, null)
            mails.saveReceived(mail)
            mails.finalizeProcessing(mail.id, true, red.id, red.backgroundColor)
            rules.upsert(red.copy(backgroundColor = MailBackgroundColor.PURPLE))
            assertEquals(MailBackgroundColor.PURPLE, rules.get(red.id)!!.backgroundColor)
            assertEquals(MailBackgroundColor.RED, mails.mails.first().single().backgroundColor)
            rules.delete(red.id)
            assertEquals(MailBackgroundColor.RED, mails.mails.first().single().backgroundColor)
        } finally { db.close() }
    }
}
