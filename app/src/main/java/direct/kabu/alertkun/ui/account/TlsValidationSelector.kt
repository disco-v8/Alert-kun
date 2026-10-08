package direct.kabu.alertkun.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.TlsValidationMode

@Composable
internal fun TlsValidationSelector(mode: TlsValidationMode, enabled: Boolean, fingerprint: String?,
    pending: String?, onSelect: (TlsValidationMode) -> Unit, onApprove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.tls_validation))
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("tls_validation_mode")) { Text(stringResource(tlsLabel(mode))) }
        DropdownMenu(expanded && enabled, { expanded = false }) {
            TlsValidationMode.entries.forEach { value ->
                DropdownMenuItem(text = { Text(stringResource(tlsLabel(value))) },
                    modifier = Modifier.testTag("tls_validation_${value.name}"),
                    onClick = { expanded = false; onSelect(value) })
            }
        }
        Text(stringResource(when (mode) {
            TlsValidationMode.STRICT -> R.string.tls_strict_description
            TlsValidationMode.CA_VALIDATED_NO_HOSTNAME -> R.string.tls_hostname_description
            TlsValidationMode.PINNED_CERTIFICATE -> R.string.tls_pinned_description
        }))
        if (mode != TlsValidationMode.STRICT) Text(stringResource(R.string.tls_strict_recommended), color = MaterialTheme.colorScheme.error)
        if (mode == TlsValidationMode.PINNED_CERTIFICATE) {
            (pending ?: fingerprint)?.let { Text(stringResource(R.string.tls_fingerprint, it), modifier = Modifier.testTag("tls_fingerprint")) }
            if (pending != null) {
                Text(stringResource(R.string.tls_verify_fingerprint))
                OutlinedButton(onClick = onApprove, enabled = enabled, modifier = Modifier.testTag("tls_approve_certificate")) {
                    Text(stringResource(R.string.tls_trust_certificate))
                }
            }
        }
    }
}

private fun tlsLabel(mode: TlsValidationMode) = when (mode) {
    TlsValidationMode.STRICT -> R.string.tls_strict
    TlsValidationMode.CA_VALIDATED_NO_HOSTNAME -> R.string.tls_hostname
    TlsValidationMode.PINNED_CERTIFICATE -> R.string.tls_pinned
}
