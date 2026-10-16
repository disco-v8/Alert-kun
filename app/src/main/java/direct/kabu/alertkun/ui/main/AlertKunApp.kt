package direct.kabu.alertkun.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import direct.kabu.alertkun.ui.account.AccountSettingsScreen
import direct.kabu.alertkun.ui.mail.ReceivedMailScreen
import direct.kabu.alertkun.ui.mail.ReceivedMailDetailScreen
import direct.kabu.alertkun.ui.rule.RuleEditScreen
import direct.kabu.alertkun.ui.rule.RuleSettingsScreen
import direct.kabu.alertkun.ui.settings.OtherSettingsScreen

private object AlertKunRoute {
    const val Main = "main"
    const val ReceivedMail = "received_mail"
    const val MailDetail = "received_mail/{mailId}"
    const val Rules = "rules"
    const val CreateRule = "rules/create"
    const val EditRule = "rules/edit/{ruleId}?copy={copy}"
    const val Account = "account"
    const val Settings = "settings"
}

@Composable
fun AlertKunApp(openMailRequest: Int = 0, openMainRequest: Int = 0) {
    val navController = rememberNavController()
    val monitoringViewModel: MainMonitoringViewModel = viewModel()
    val monitoring by monitoringViewModel.state.collectAsStateWithLifecycle()
    val alarmSession by monitoringViewModel.alarmSession.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = AlertKunRoute.Main,
    ) {
        composable(AlertKunRoute.Main) {
            MainScreen(
                monitoringState = monitoring.monitoringState,
                pollIntervalMinutes = monitoring.settings.pollIntervalMinutes,
                timeZoneRevision = monitoring.timeZoneRevision,
                onOpenMail = { navController.navigate(AlertKunRoute.ReceivedMail) },
                onOpenRules = { navController.navigate(AlertKunRoute.Rules) },
                onOpenAccount = { navController.navigate(AlertKunRoute.Account) },
                onOpenSettings = { navController.navigate(AlertKunRoute.Settings) },
                continuousAlarmActive = alarmSession != null,
                onStopAlarm = monitoringViewModel::stopAlarm,
                demoModeEnabled = monitoring.settings.demoModeEnabled,
                hasEnabledAccount = monitoring.hasEnabledAccount,
            )
        }
        composable(AlertKunRoute.ReceivedMail) {
            ReceivedMailScreen(onBack = navController::navigateUp,
                onOpenMail = { id -> navController.navigate("received_mail/${android.net.Uri.encode(id)}") })
        }
        composable(AlertKunRoute.MailDetail) { entry ->
            ReceivedMailDetailScreen(mailId = entry.arguments?.getString("mailId"), onBack = navController::navigateUp)
        }
        composable(AlertKunRoute.Rules) {
            RuleSettingsScreen(
                onBack = navController::navigateUp,
                onAddRule = { navController.navigate(AlertKunRoute.CreateRule) },
                onEditRule = { id -> navController.navigate("rules/edit/$id?copy=false") },
                onCopyRule = { id -> navController.navigate("rules/edit/$id?copy=true") },
            )
        }
        composable(AlertKunRoute.CreateRule) {
            RuleEditScreen(
                ruleId = null,
                copy = false,
                onBack = navController::navigateUp,
                onSaved = navController::navigateUp,
            )
        }
        composable(AlertKunRoute.EditRule) { backStackEntry ->
            RuleEditScreen(
                ruleId = backStackEntry.arguments?.getString("ruleId"),
                copy = backStackEntry.arguments?.getString("copy")?.toBoolean() ?: false,
                onBack = navController::navigateUp,
                onSaved = navController::navigateUp,
            )
        }
        composable(AlertKunRoute.Account) {
            AccountSettingsScreen(onBack = navController::navigateUp)
        }
        composable(AlertKunRoute.Settings) {
            OtherSettingsScreen(onBack = navController::navigateUp)
        }
    }
    LaunchedEffect(openMailRequest, openMainRequest) {
        // The latest notification wins even if two intents arrive before the next composition.
        if (openMainRequest > openMailRequest) navController.popBackStack(AlertKunRoute.Main, inclusive = false)
        else if (openMailRequest > 0) navController.navigate(AlertKunRoute.ReceivedMail) { launchSingleTop = true }
    }
}
