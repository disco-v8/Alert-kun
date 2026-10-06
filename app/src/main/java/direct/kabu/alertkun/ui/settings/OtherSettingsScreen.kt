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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
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
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OtherSettingsScreen(
    onBack: () -> Unit,
    viewModel: AlarmTestViewModel = viewModel(),
    languageViewModel: LanguageSettingsViewModel = viewModel(),
    monitoringViewModel: MonitoringSettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val language by languageViewModel.language.collectAsStateWithLifecycle()
    val monitoringSettings by monitoringViewModel.settings.collectAsStateWithLifecycle()
    var pollInterval by remember { mutableStateOf("") }
    var failureThreshold by remember { mutableStateOf("") }
    var monitoringEnabled by remember { mutableStateOf(false) }
    var checkImmediatelyOnAppStart by remember { mutableStateOf(true) }
    LaunchedEffect(monitoringSettings) {
        pollInterval = monitoringSettings.pollIntervalMinutes.toString()
        failureThreshold = monitoringSettings.consecutiveFailureThreshold.toString()
        monitoringEnabled = monitoringSettings.monitoringEnabled
        checkImmediatelyOnAppStart = monitoringSettings.checkImmediatelyOnAppStart
    }
    var pendingAlarm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var testSound by remember { mutableStateOf<String?>(null) }
    var testSoundType by remember { mutableStateOf(AlarmSoundType.ALARM) }
    var testVibration by remember { mutableStateOf(VibrationPattern.PATTERN_1) }
    var testCount by remember { mutableStateOf(AlarmMode.ONCE) }
    val vibrationPreview = rememberVibrationPreview()
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.languages))
            LanguageChoice(AppLanguage.ENGLISH, language, languageViewModel::select)
            LanguageChoice(AppLanguage.JAPANESE, language, languageViewModel::select)
            Text(stringResource(R.string.monitoring_settings))
            val pollValid = pollInterval.toIntOrNull() in 1..1500
            val thresholdValid = failureThreshold.toIntOrNull() in 1..99
            OutlinedTextField(value = pollInterval, onValueChange = { pollInterval = it }, isError = pollInterval.isNotEmpty() && !pollValid, label = { Text(stringResource(R.string.poll_interval_minutes)) }, modifier = Modifier.fillMaxWidth().testTag("monitoring_poll_interval"))
            OutlinedTextField(value = failureThreshold, onValueChange = { failureThreshold = it }, isError = failureThreshold.isNotEmpty() && !thresholdValid, label = { Text(stringResource(R.string.consecutive_failure_threshold)) }, modifier = Modifier.fillMaxWidth().testTag("monitoring_failure_threshold"))
            androidx.compose.foundation.layout.Row {
                Checkbox(checked = monitoringEnabled, onCheckedChange = { monitoringEnabled = it })
                Text(stringResource(R.string.monitoring_enabled))
            }
            androidx.compose.foundation.layout.Row {
                Switch(checked = checkImmediatelyOnAppStart, onCheckedChange = { checkImmediatelyOnAppStart = it }, modifier = Modifier.testTag("check_immediately_on_app_start"))
                Text(stringResource(R.string.check_immediately_on_app_start))
            }
            Button(onClick = { val poll = pollInterval.toIntOrNull(); val threshold = failureThreshold.toIntOrNull(); if (pollValid && thresholdValid) monitoringViewModel.update(poll!!, threshold!!, monitoringEnabled, checkImmediatelyOnAppStart) }, enabled = pollValid && thresholdValid, modifier = Modifier.fillMaxWidth().testTag("monitoring_save")) { Text(stringResource(R.string.save_monitoring_settings)) }
            HorizontalDivider(Modifier.testTag("alarm_test_divider"))
            Text(stringResource(R.string.developer_tools))
            Button(onClick = monitoringViewModel::queueMatching, modifier = Modifier.fillMaxWidth().testTag("queue_dummy_matching_mail")) { Text(stringResource(R.string.queue_dummy_matching_mail)) }
            Button(onClick = monitoringViewModel::queueNonMatching, modifier = Modifier.fillMaxWidth().testTag("queue_dummy_non_matching_mail")) { Text(stringResource(R.string.queue_dummy_non_matching_mail)) }
            Button(onClick = monitoringViewModel::runNow, modifier = Modifier.fillMaxWidth().testTag("run_poll_now")) { Text(stringResource(R.string.run_poll_now)) }
            OutlinedButton(onClick = monitoringViewModel::simulateFailure, modifier = Modifier.fillMaxWidth().testTag("simulate_poll_failure")) { Text(stringResource(R.string.simulate_poll_failure)) }
            Text(stringResource(R.string.manual_alarm_note), Modifier.testTag("alarm_test_note"))
            AlarmPermissionControls()
            AlarmSoundSelector(testSound, testSoundType,
                onSoundTypeSelected = { type ->
                    if (type != testSoundType) {
                        testSoundType = type
                        testSound = null
                    }
                },
                onSelected = { testSound = it })
            VibrationPatternSelector(testVibration, "alarm_test_vibration") { pattern ->
                if (pattern != testVibration) {
                    testVibration = pattern
                    vibrationPreview.select(pattern)
                }
            }
            AlarmCountSelector(testCount, "alarm_test_count") { testCount = it }
            AlarmTestControls(
                onStart = { startWithPermission { viewModel.start(testCount, testSound, testVibration, testSoundType) } },
                onStop = { viewModel.stop() },
            )
        }
    }
}

@Composable
fun AlarmTestControls(
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().testTag("alarm_test_start"),
        ) { Text(stringResource(R.string.test_alarm)) }
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
