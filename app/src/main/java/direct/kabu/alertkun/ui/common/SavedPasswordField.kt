package direct.kabu.alertkun.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import direct.kabu.alertkun.R

/** Same saved-mask/editor behavior for mail and MyDNS. The editor never contains the mask. */
@Composable
internal fun SavedPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit,
    saved: Boolean,
    enabled: Boolean,
    credentialLoading: Boolean,
    labelResource: Int,
    fieldTag: String,
    tagPrefix: String,
    showSupportingText: Boolean = true,
) {
    var editingSavedPassword by remember(saved, value.isEmpty()) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource, saved, value.isEmpty()) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) editingSavedPassword = true
        }
    }
    val showSavedMask = saved && value.isEmpty() && !editingSavedPassword
    val savedMask = stringResource(R.string.account_password_saved_mask)
    val maskTransformation = remember(savedMask) { SavedPasswordMaskTransformation(savedMask) }
    OutlinedTextField(value, {
        editingSavedPassword = true
        onValueChange(it)
    }, enabled = enabled, interactionSource = interactionSource, label = { Text(stringResource(labelResource)) },
        supportingText = if (!showSupportingText) null else { {
            Column(Modifier.testTag("${tagPrefix}_password_status")) {
                Text(stringResource(when {
                    credentialLoading -> R.string.account_password_checking
                    saved -> R.string.account_password_saved
                    else -> R.string.account_password_not_saved
                }))
                if (saved && !credentialLoading) {
                    Text(stringResource(R.string.account_password_change_hint), fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("${tagPrefix}_password_change_hint"))
                }
            }
        } },
        visualTransformation = when {
            showSavedMask -> maskTransformation
            visible -> VisualTransformation.None
            else -> PasswordVisualTransformation()
        },
        trailingIcon = if (value.isEmpty()) null else { {
            PasswordVisibilityButton(visible, onToggle, enabled, Modifier.testTag("${tagPrefix}_password_visibility"))
        } }, modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (it.isFocused) editingSavedPassword = true
            else if (value.isEmpty()) editingSavedPassword = false
        }.testTag(fieldTag))
}

/** Display-only: the actual TextField value stays empty and cannot persist the mask. */
internal class SavedPasswordMaskTransformation(private val mask: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isNotEmpty()) return PasswordVisualTransformation().filter(text)
        return TransformedText(AnnotatedString(mask), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = 0
            override fun transformedToOriginal(offset: Int) = 0
        })
    }
}
