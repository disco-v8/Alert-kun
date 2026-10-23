package direct.kabu.alertkun.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.R
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.monitoring.ObservationExportResult
import direct.kabu.alertkun.monitoring.ObservationExportStatus
import direct.kabu.alertkun.monitoring.ObservationLogExporter
import direct.kabu.alertkun.ui.theme.AlertKunTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated UI / cache fixtures only. Never changes monitoring, accounts, credentials or real mail. */
class ObservationLogExportTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test fun englishAndJapaneseUiExposeNoticeAndIndependentExportButton() {
        val locale = mutableStateOf(Locale.ENGLISH)
        var clicks = 0
        compose.setContent {
            val configuration = Configuration(application.resources.configuration).apply { setLocale(locale.value) }
            val localized = application.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                AlertKunTheme { ObservationLogExportContent(ObservationLogExportState()) { clicks++ } }
            }
        }
        compose.onNodeWithTag("observation_export_save").assertIsEnabled().assertTextContains("Export observation logs").performClick()
        compose.onNodeWithTag("observation_export_privacy").assertTextContains("account identifiers", substring = true)
        compose.runOnIdle { assertEquals(1, clicks); locale.value = Locale.JAPANESE }
        compose.onNodeWithTag("observation_export_save").assertIsEnabled().assertTextContains("観測ログを保存")
        compose.onNodeWithTag("observation_export_privacy").assertTextContains("アカウント識別子", substring = true)
        val japanese = application.createConfigurationContext(Configuration(application.resources.configuration).apply { setLocale(Locale.JAPANESE) })
        assertEquals("監視・通信状態の診断ログをZIPファイルに保存します", japanese.getString(R.string.observation_export_description))
    }

    @Test fun resultsShowCountsAndBusyPickerStatesPreventDuplicateExports() {
        val state = mutableStateOf(ObservationLogExportState(result = ObservationExportResult(ObservationExportStatus.SUCCESS, 3, 3)))
        val choosing = mutableStateOf(false)
        compose.setContent { AlertKunTheme { ObservationLogExportContent(state.value, choosing.value) { } } }
        compose.onNodeWithTag("observation_export_result").assertTextContains("3", substring = true)
        compose.onNodeWithTag("observation_export_save").assertIsEnabled()
        compose.runOnIdle { choosing.value = true }
        compose.onNodeWithTag("observation_export_save").assertIsNotEnabled()
        compose.runOnIdle { choosing.value = false; state.value = ObservationLogExportState(busy = true) }
        compose.onNodeWithTag("observation_export_save").assertIsNotEnabled()
        compose.onNodeWithTag("observation_export_running").assertExists()
        compose.runOnIdle { state.value = ObservationLogExportState(result = ObservationExportResult(ObservationExportStatus.FAILURE, 4, 2)) }
        compose.onNodeWithTag("observation_export_result").assertTextContains("4", substring = true).assertTextContains("2", substring = true)
        compose.runOnIdle { state.value = ObservationLogExportState(result = ObservationExportResult(ObservationExportStatus.NO_FILES, 0, 0)) }
        compose.onNodeWithTag("observation_export_result").assertTextContains("0", substring = true)
        compose.onNodeWithTag("observation_export_save").assertIsEnabled()
    }

    @Test fun documentContractUsesZipAndCancellationDoesNotChangeViewModelOrMonitoring() = runBlocking {
        val contract = ActivityResultContracts.CreateDocument("application/zip")
        val name = "Alert-kun-observability-20261021.zip"
        val intent = contract.createIntent(application, name)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
        val uri = Uri.parse("content://synthetic.fixture/logs.zip")
        assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        val settings = SettingsRepository(application)
        val status = MonitoringStatusRepository(application)
        val beforeSettings = settings.settings.first()
        val beforeStatus = status.current()
        val beforeExecution = status.executionStatus()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val vm = ObservationLogExportViewModel(application)
            val before = vm.state.value
            vm.export(null)
            assertEquals(before, vm.state.value)
        }
        assertEquals(beforeSettings, settings.settings.first())
        assertEquals(beforeStatus, status.current())
        assertEquals(beforeExecution, status.executionStatus())
    }

    @Test fun androidReadOnlySnapshotsExportBytesWithoutChangingMonitoringAndExcludeSymlinks() = runBlocking {
        val settings = SettingsRepository(application)
        val status = MonitoringStatusRepository(application)
        val beforeSettings = settings.settings.first()
        val beforeStatus = status.current()
        val beforeExecution = status.executionStatus()
        val root = Files.createTempDirectory(application.cacheDir.toPath(), "export-fixture-").toFile()
        try {
            val directory = File(root, "observability").apply { mkdirs() }
            val log = File(directory, "monitoring-observation-2026-10-19.log").apply { writeText("synthetic original\n日本語\n") }
            val original = log.readBytes()
            val excluded = File(root, "credentials.fixture").apply { writeText("excluded synthetic bytes") }
            Files.createSymbolicLink(File(directory, "monitoring-observation.log").toPath(), excluded.toPath())
            File(directory, "monitoring-observation.log.retention-pending").writeText("excluded work file")
            val output = ByteArrayOutputStream()
            val result = ObservationLogExporter(directory).export { log.appendText("later append\n"); output }
            assertEquals(ObservationExportResult(ObservationExportStatus.SUCCESS, 1, 1), result)
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                assertEquals(log.name, zip.nextEntry.name)
                assertArrayEquals(original, zip.readBytes())
                assertNull(zip.nextEntry)
            }
            assertTrue(log.exists())
            assertEquals("synthetic original\n日本語\nlater append\n", log.readText())
            assertEquals(beforeSettings, settings.settings.first())
            assertEquals(beforeStatus, status.current())
            assertEquals(beforeExecution, status.executionStatus())
        } finally {
            // Only the newly created disposable fixture directory; no app logs/data are removed.
            root.deleteRecursively()
        }
    }
}
