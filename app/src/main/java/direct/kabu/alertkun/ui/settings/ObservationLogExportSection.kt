package direct.kabu.alertkun.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.R
import direct.kabu.alertkun.monitoring.ObservationExportStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ObservationLogExportSection(viewModel: ObservationLogExportViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingDestination by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        choosingDestination = false
        viewModel.export(uri)
    }
    ObservationLogExportContent(state, choosingDestination) {
        choosingDestination = true
        try {
            val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
            launcher.launch("Alert-kun-observability-$date.zip")
        } catch (_: Exception) {
            choosingDestination = false
            viewModel.pickerFailed()
        }
    }
}

@Composable
internal fun ObservationLogExportContent(
    state: ObservationLogExportState,
    choosingDestination: Boolean = false,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("observation_log_export")) {
        Text(stringResource(R.string.diagnostics))
        Text(stringResource(R.string.observation_export_description))
        Text(stringResource(R.string.observation_export_privacy), Modifier.testTag("observation_export_privacy"))
        Button(onClick = onExport, enabled = !state.busy && !choosingDestination,
            modifier = Modifier.fillMaxWidth().testTag("observation_export_save")) {
            Text(stringResource(R.string.observation_export_save))
        }
        if (state.busy) Text(stringResource(R.string.observation_export_running), Modifier.testTag("observation_export_running"))
        if (state.pickerFailed) Text(stringResource(R.string.observation_export_picker_failed), Modifier.testTag("observation_export_result"))
        state.result?.let { result ->
            val message = when (result.status) {
                ObservationExportStatus.SUCCESS -> R.string.observation_export_success
                ObservationExportStatus.NO_FILES -> R.string.observation_export_no_files
                ObservationExportStatus.FAILURE -> R.string.observation_export_failure
            }
            Text(stringResource(message, result.targetCount, result.exportedCount), Modifier.testTag("observation_export_result"))
        }
    }
}
