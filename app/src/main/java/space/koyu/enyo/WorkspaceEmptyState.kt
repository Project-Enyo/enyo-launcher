package space.koyu.enyo

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

@Composable
internal fun WorkspaceEmptyState(@StringRes message: Int) {
    val tokens = LocalTokens.current
    Surface(
        color = tokens.widgetSurface,
        shape = RoundedCornerShape(tokens.radius),
        shadowElevation = tokens.elevation[0]
    ) {
        Text(
            stringResource(message),
            Modifier.padding(horizontal = tokens.padding, vertical = tokens.gap),
            style = tokens.state.copy(color = tokens.onAccentContainer),
            textAlign = TextAlign.Center
        )
    }
}
