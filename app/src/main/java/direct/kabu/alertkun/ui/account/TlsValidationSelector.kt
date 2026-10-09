package direct.kabu.alertkun.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.TlsValidationMode

@Composable
internal fun TlsValidationSelector(mode: TlsValidationMode, enabled: Boolean,
    onSelect: (TlsValidationMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.tls_validation))
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("tls_validation_mode")) {
            Text(stringResource(tlsLabel(mode)))
            Text(" ▼")
        }
        DropdownMenu(expanded && enabled, { expanded = false }) {
            TlsValidationMode.entries.forEach { value ->
                DropdownMenuItem(text = { Text(stringResource(tlsLabel(value))) },
                    modifier = Modifier.testTag("tls_validation_${value.name}"),
                    onClick = { expanded = false; onSelect(value) })
            }
        }

    }
}

private fun tlsLabel(mode: TlsValidationMode) = when (mode) {
    TlsValidationMode.STRICT -> R.string.tls_strict
    TlsValidationMode.CA_VALIDATED_NO_HOSTNAME -> R.string.tls_hostname
    TlsValidationMode.SELF_SIGNED_ALLOWED -> R.string.tls_self_signed
    TlsValidationMode.INSECURE -> R.string.tls_insecure
}
