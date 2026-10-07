package direct.kabu.alertkun.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmController

@Composable
internal fun AlarmPermissionControls() {
    val context = LocalContext.current
    val controller = remember(context) { AlarmController.getInstance(context) }
    var exact by remember { mutableStateOf(controller.canScheduleExactAlarms()) }
    var notifications by remember { mutableStateOf(controller.canPostNotifications()) }
    val refresh = { exact = controller.canScheduleExactAlarms(); notifications = controller.canPostNotifications() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.testTag("alarm_permissions")) {
        if (!notifications) {
            Text(stringResource(R.string.alarm_notifications_required))
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                else settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.testTag("alarm_notification_permission")) { Text(stringResource(R.string.alarm_allow_notifications)) }
            TextButton(onClick = {
                settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.alarm_open_app_settings)) }
        }
        if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text(stringResource(R.string.alarm_exact_permission_required))
            TextButton(onClick = {
                settings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.testTag("alarm_exact_permission")) { Text(stringResource(R.string.alarm_allow_exact)) }
        }
    }
}
