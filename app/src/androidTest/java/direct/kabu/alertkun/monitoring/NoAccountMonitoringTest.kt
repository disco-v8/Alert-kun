package direct.kabu.alertkun.monitoring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.preferences.settingsDataStore
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.mail.common.DummyMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverSelection
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.push.FcmMailCheckDispatcher
import direct.kabu.alertkun.ui.settings.MonitoringSettingsViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Run only on a disposable empty test device, never a user's configured mailbox. */
class NoAccountMonitoringTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = AlertKunDatabase.getInstance(app)
    private val accounts = AccountRepository(database.accountDao())
    private val settings = SettingsRepository(app)
    private fun account(id: String) = MailAccount(id, "Disposable", MailProtocol.POP3,
        "example.invalid", 110, "fixture", "fixture-unused-key", false, null, true)

    @Test fun startWithoutAccountIsRejectedBeforeAnySettingsOrScheduleWrite() = runBlocking {
        check(accounts.accounts.first().isEmpty()) { "Requires an empty disposable test device" }
        val before = settings.settings.first()
        try {
            MonitoringDevicePreparation.apply(app, before, false)
            settings.updateDemoModeEnabled(false)
            val beforePreferences = app.settingsDataStore.data.first().asMap()
            val statusBefore = MonitoringStatusRepository(app).executionStatus()
            val vm = MonitoringSettingsViewModel(app)
            vm.update(19, 8, true, false, false).join()
            assertTrue(vm.startBlocked.value)
            assertFalse(settings.settings.first().monitoringEnabled)
            assertEquals(beforePreferences, app.settingsDataStore.data.first().asMap())
            assertEquals(statusBefore, MonitoringStatusRepository(app).executionStatus())
            assertTrue(vm.monitoringUpdateOperations.isEmpty())
        } finally {
            settings.updateDemoModeEnabled(before.demoModeEnabled)
            MonitoringDevicePreparation.apply(app, before, before.monitoringEnabled)
        }
    }

    @Test fun fcmWithoutAccountIsSuccessfulSkipAfterLastAccountRemoved() = skipAfterRemoval(MailCheckTrigger.FCM)
    @Test fun scheduledWorkerWithoutAccountIsSuccessfulSkipAfterLastAccountRemoved() = skipAfterRemoval(MailCheckTrigger.SCHEDULED_WORK)
    @Test fun manualWorkerWithoutAccountIsSuccessfulSkipWithoutChangingSuccessOrFailures() = skipAfterRemoval(MailCheckTrigger.MANUAL)

    private fun skipAfterRemoval(trigger: MailCheckTrigger) = runBlocking {
        check(accounts.accounts.first().isEmpty()) { "Requires an empty disposable test device" }
        val before = settings.settings.first()
        val id = "no-account-fixture-${UUID.randomUUID()}"
        val manager = WorkManager.getInstance(app)
        val status = MonitoringStatusRepository(app)
        try {
            settings.updateDemoModeEnabled(false)
            accounts.upsert(account(id))
            MonitoringDevicePreparation.apply(app, before.copy(demoModeEnabled = false), true)
            accounts.delete(id)
            val execution = status.executionStatus()
            status.recordFailure(System.currentTimeMillis(), 99, execution.scheduleGeneration)
            val statusBefore = status.executionStatus()
            val failuresBefore = status.current().consecutiveFailureCount
            val preferenceBefore = settings.settings.first()
            val mailBefore = MailRepository(database.receivedMailDao()).mails.first()
            val workId = if (trigger == MailCheckTrigger.FCM) {
                val previousIds = manager.getWorkInfosForUniqueWork(MonitoringScheduler.FCM_WORK_NAME).get().map { it.id }.toSet()
                FcmMailCheckDispatcher { MonitoringScheduler.runNow(app, MailCheckTrigger.FCM).result.get(10, TimeUnit.SECONDS) }
                    .dispatch(mapOf("action" to "CHECK_MAIL"))
                manager.getWorkInfosForUniqueWork(MonitoringScheduler.FCM_WORK_NAME).get().single { it.id !in previousIds }.id
            } else {
                val work = OneTimeWorkRequestBuilder<MailCheckWorker>().setInputData(workDataOf(
                    MonitoringScheduler.KEY_TRIGGER to trigger.name,
                    MonitoringScheduler.KEY_SCHEDULED to (trigger == MailCheckTrigger.SCHEDULED_WORK),
                    MonitoringScheduler.KEY_GENERATION to execution.scheduleGeneration)).build()
                manager.enqueue(work).result.get(10, TimeUnit.SECONDS)
                work.id
            }
            val info = withTimeout(15_000) {
                var current = manager.getWorkInfoById(workId).get()!!
                while (!current.state.isFinished) { delay(25); current = manager.getWorkInfoById(workId).get()!! }
                current
            }
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            val statusAfter = status.executionStatus()
            assertTrue(settings.settings.first().monitoringEnabled)
            assertEquals(preferenceBefore, settings.settings.first())
            assertEquals(statusBefore.lastSuccessfulCheckAtEpochMillis, statusAfter.lastSuccessfulCheckAtEpochMillis)
            assertEquals(statusBefore.lastCheckStartedAtEpochMillis, statusAfter.lastCheckStartedAtEpochMillis)
            assertEquals(statusBefore.lastCheckCompletedAtEpochMillis, statusAfter.lastCheckCompletedAtEpochMillis)
            assertEquals(failuresBefore, status.current().consecutiveFailureCount)
            assertEquals(statusBefore, statusAfter.copy(nextBackupAtEpochMillis = statusBefore.nextBackupAtEpochMillis))
            assertEquals(mailBefore, MailRepository(database.receivedMailDao()).mails.first())
            val lines = File(app.filesDir, "observability").listFiles().orEmpty().flatMap { it.readLines() }
                .filter { it.contains("workId=$workId") }
            assertTrue(lines.any { it.contains("WORKER_SKIP") && it.contains("skipReason=NO_CONFIGURED_MAIL_ACCOUNT") })
            assertTrue(lines.any { it.contains("WORKER_END") && it.contains("checkResult=SKIPPED") && it.contains("result=SUCCESS") })
            assertFalse(lines.any { it.contains("CHECK_START") || it.contains("MAIL_CONNECT_START") || it.contains("NETWORK_CALLBACK") })
        } finally {
            accounts.delete(id)
            settings.updateDemoModeEnabled(before.demoModeEnabled)
            // Restore through the same verified enqueue/cancel path as the other device tests.
            // A temporary enabled account permits restoration when the initial mode was normal and ON.
            if (before.monitoringEnabled && !before.demoModeEnabled) accounts.upsert(account(id))
            try { MonitoringDevicePreparation.apply(app, before, before.monitoringEnabled) } finally { accounts.delete(id) }
        }
    }

    @Test fun explicitDemoWithRealAccountUsesDummyAndDoesNotRecoverRealMail() = checkDemo(true)
    @Test fun explicitDemoWithoutAccountUsesDummyAndDoesNotRecoverRealMail() = checkDemo(false)

    private fun checkDemo(withAccount: Boolean) = runBlocking {
        val repository = MailRepository(database.receivedMailDao())
        val id = "demo-fixture-${UUID.randomUUID()}"
        val remoteId = UUID.randomUUID().toString()
        val pending = ReceivedMail(id, id, remoteId, System.currentTimeMillis(), "fixture@example.invalid", emptyMap(),
            "Real pending mail", null, false, MailProcessingState.RECEIVED, false, null)
        val beforeSettings = app.settingsDataStore.data.first().asMap()
        val beforeAccounts = accounts.accounts.first()
        try {
            repository.saveReceived(pending)
            DummyMailReceiver.instance.queue("Demo fixture", "Demo body", remoteId)
            val selection = MailReceiverSelection(true, if (withAccount) listOf(account(id)) else emptyList())
            val result = MailCheckCoordinatorFactory.create(app, selection, null).check()
            assertTrue(result.success)
            assertEquals(pending, repository.getByRemoteId(id, remoteId))
            assertNotNull(repository.getByRemoteId(DummyMailReceiver.DUMMY_ACCOUNT_ID, remoteId))
            assertEquals(beforeAccounts, accounts.accounts.first())
            assertEquals(beforeSettings, app.settingsDataStore.data.first().asMap())
        } finally {
            database.openHelper.writableDatabase.execSQL("DELETE FROM received_mails WHERE id = ? OR (accountId = ? AND remoteMessageId = ?)",
                arrayOf(id, DummyMailReceiver.DUMMY_ACCOUNT_ID, remoteId))
        }
    }
}
