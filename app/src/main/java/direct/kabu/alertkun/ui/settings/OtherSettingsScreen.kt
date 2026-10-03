package direct.kabu.alertkun.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.AppLanguage

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OtherSettingsScreen(
    onBack: () -> Unit,
    viewModel: AlarmTestViewModel = viewModel(),
    languageViewModel: LanguageSettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val language by languageViewModel.language.collectAsStateWithLifecycle()
    var pendingAlarm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingAlarm?.invoke()
        pendingAlarm = null
    }
    val startWithPermission: ((() -> Unit) -> Unit) = { start ->
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingAlarm = start
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            start()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.languages))
            LanguageChoice(AppLanguage.ENGLISH, language, languageViewModel::select)
            LanguageChoice(AppLanguage.JAPANESE, language, languageViewModel::select)
            Text(stringResource(R.string.developer_tools))
            Text(stringResource(R.string.manual_alarm_note))
            AlarmTestControls(
                onOnce = { startWithPermission { viewModel.startOnce() } },
                onUntilStopped = { startWithPermission { viewModel.startUntilStopped() } },
                onStop = { viewModel.stop() },
            )
        }
    }
}

@Composable
fun AlarmTestControls(
    onOnce: () -> Unit,
    onUntilStopped: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onOnce,
            modifier = Modifier.fillMaxWidth().testTag("alarm_test_once"),
        ) { Text(stringResource(R.string.test_alarm_once)) }
        Button(
            onClick = onUntilStopped,
            modifier = Modifier.fillMaxWidth().testTag("alarm_test_until_stopped"),
        ) { Text(stringResource(R.string.test_alarm_until_stopped)) }
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().testTag("alarm_test_stop"),
        ) { Text(stringResource(R.string.stop_test_alarm)) }
    }
}

@Composable
private fun LanguageChoice(value: AppLanguage, selected: AppLanguage, onSelected: (AppLanguage) -> Unit) {
    val label = stringResource(if (value == AppLanguage.ENGLISH) R.string.english else R.string.japanese)
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected = value == selected, onClick = { onSelected(value) })
        TextButton(onClick = { onSelected(value) }) { Text(label) }
    }
}
