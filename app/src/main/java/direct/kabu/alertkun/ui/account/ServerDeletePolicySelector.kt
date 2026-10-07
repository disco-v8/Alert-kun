package direct.kabu.alertkun.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.ServerDeletePolicy

@Composable
internal fun ServerDeletePolicySelector(selected: ServerDeletePolicy, enabled: Boolean, onSelected: (ServerDeletePolicy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.server_delete_policy))
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("server_delete_policy")) {
            Text(stringResource(policyLabel(selected)))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            ServerDeletePolicy.entries.forEach { policy ->
                DropdownMenuItem(text = { Text(stringResource(policyLabel(policy))) },
                    modifier = Modifier.testTag("server_delete_policy_${policy.name}"),
                    onClick = { onSelected(policy); expanded = false })
            }
        }
        if (selected == ServerDeletePolicy.IMMEDIATELY) Text(stringResource(R.string.server_delete_immediately_hint))
    }
}

private fun policyLabel(policy: ServerDeletePolicy): Int = when (policy) {
    ServerDeletePolicy.IMMEDIATELY -> R.string.server_delete_immediately
    ServerDeletePolicy.AFTER_1_DAY -> R.string.server_delete_after_1_day
    ServerDeletePolicy.AFTER_1_WEEK -> R.string.server_delete_after_1_week
    ServerDeletePolicy.AFTER_10_DAYS -> R.string.server_delete_after_10_days
    ServerDeletePolicy.AFTER_15_DAYS -> R.string.server_delete_after_15_days
    ServerDeletePolicy.AFTER_1_MONTH -> R.string.server_delete_after_1_month
    ServerDeletePolicy.NEVER -> R.string.server_delete_never
}
