package direct.kabu.alertkun.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import direct.kabu.alertkun.R

/** Shared with the mail account password field; toggles only the current editor input. */
@Composable
internal fun PasswordVisibilityButton(visible: Boolean, onToggle: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(if (visible) R.string.password_hide else R.string.password_show)
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onToggle, enabled = enabled, modifier = modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.size(24.dp)) {
            drawOval(color, topLeft = Offset(0f, size.height * .25f), size = Size(size.width, size.height * .5f), style = Stroke(2.dp.toPx()))
            drawCircle(color, radius = size.width * .12f)
            if (!visible) drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
