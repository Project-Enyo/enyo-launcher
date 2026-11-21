package space.koyu.enyo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnyoSheet(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalTokens.current
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = tokens.radius, topEnd = tokens.radius),
        containerColor = tokens.surface,
        contentColor = tokens.ink,
        scrimColor = tokens.background.copy(alpha = tokens.sheetScrim),
        dragHandle = { BottomSheetDefaults.DragHandle(color = tokens.quiet) }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = tokens.margin).padding(bottom = tokens.bottomClearance),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            Text(title, style = tokens.state)
            content()
        }
    }
}
