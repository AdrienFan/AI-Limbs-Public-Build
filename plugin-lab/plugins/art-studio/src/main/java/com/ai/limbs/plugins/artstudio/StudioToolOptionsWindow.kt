package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** A bounded in-page surface: only its rectangle consumes input, so drawing stays live. */
@Composable
internal fun BoxWithConstraintsScope.StudioToolOptionsWindow(
    state: StudioToolWindowState,
    title: String,
    onMove: (Float, Float) -> Unit,
    onMinimize: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current.density
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val width = maxWidth.coerceAtMost(320.dp)
    val heightLimit = maxHeight.coerceAtMost(480.dp)
    val maxX = ((maxWidth.value * density - measured.width) / density).coerceAtLeast(0f)
    val maxY = ((maxHeight.value * density - measured.height) / density).coerceAtLeast(0f)
    val x = state.xDp.coerceIn(0f, maxX)
    val y = state.yDp.coerceIn(0f, maxY)
    val liveState by rememberUpdatedState(state)
    val liveMaxX by rememberUpdatedState(maxX)
    val liveMaxY by rememberUpdatedState(maxY)
    val liveMove by rememberUpdatedState(onMove)
    LaunchedEffect(state.xDp, state.yDp, maxX, maxY, measured) {
        // Re-clamp after expanding or rotating; the title must always remain reachable.
        if (measured != IntSize.Zero && (x != state.xDp || y != state.yDp)) onMove(x, y)
    }
    Surface(
        modifier = Modifier.offset { IntOffset((x * density).roundToInt(), (y * density).roundToInt()) }
            .width(width).heightIn(max = heightLimit).onSizeChanged { measured = it }
            .zIndex(20f).semantics { contentDescription = "$title · 工具参数浮窗" }
            .clickable(onClickLabel = "工具参数浮窗") { },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight()
                    .clickable(onClickLabel = if (state.minimized) "恢复参数浮窗" else "工具参数标题") {
                        if (state.minimized) onRestore()
                    }
                    .pointerInput(density) {
                        var dragX = 0f
                        var dragY = 0f
                        detectDragGestures(
                            onDragStart = {
                                dragX = liveState.xDp.coerceIn(0f, liveMaxX)
                                dragY = liveState.yDp.coerceIn(0f, liveMaxY)
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                dragX = (dragX + delta.x / density).coerceIn(0f, liveMaxX)
                                dragY = (dragY + delta.y / density).coerceIn(0f, liveMaxY)
                                liveMove(dragX, dragY)
                            }
                        )
                    }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = if (state.minimized) onRestore else onMinimize,
                    modifier = Modifier.width(48.dp),
                    contentPadding = PaddingValues(0.dp)) {
                    Text(if (state.minimized) "□" else "−",
                        modifier = Modifier.semantics {
                            contentDescription = if (state.minimized) "恢复参数浮窗" else "缩小参数浮窗"
                        })
                }
                TextButton(onClick = onClose, modifier = Modifier.width(48.dp),
                    contentPadding = PaddingValues(0.dp)) {
                    Text("×", modifier = Modifier.semantics { contentDescription = "关闭参数浮窗" })
                }
            }
            if (!state.minimized) {
                HorizontalDivider()
                key(state.toolId) {
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                }
            }
        }
    }
}
