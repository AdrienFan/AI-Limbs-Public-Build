package com.ai.assistance.operit.ui.features.toolbox.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ai.assistance.operit.ui.features.toolbox.layout.ToolboxLayoutController
import com.ai.assistance.operit.ui.main.LocalAppNavigationModel
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.navigation.NavigationSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Tool(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val description: String? = null,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun ToolboxScreen(
    navController: NavController,
    onNavigationEntrySelected: (NavigationEntrySpec) -> Unit
) {
    val context = LocalContext.current
    val navigationModel = LocalAppNavigationModel.current
    val layoutController = remember(context.applicationContext) { ToolboxLayoutController.get(context) }
    val editSession by layoutController.editSession.collectAsState()
    val savedOrder by layoutController.toolboxOrder.collectAsState()
    val isEditing = editSession?.surface == ToolboxLayoutController.TOOLBOX_SURFACE &&
        editSession?.mode == ToolboxLayoutController.LAYOUT_MODE

    val toolboxEntries = remember(navigationModel) {
        navigationModel?.navigationEntries.orEmpty()
            .filter { it.surface == NavigationSurface.TOOLBOX }
    }
    val entriesById = remember(toolboxEntries) { toolboxEntries.associateBy { it.entryId } }
    val resolvedIds = remember(toolboxEntries, savedOrder) {
        layoutController.resolveToolboxOrder(toolboxEntries.map { it.entryId })
    }
    val workingIds = remember { mutableStateListOf<String>() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(resolvedIds) {
        if (draggedId == null) {
            workingIds.clear()
            workingIds.addAll(resolvedIds)
        }
    }

    fun saveCurrentOrder() {
        layoutController.saveOrder(ToolboxLayoutController.TOOLBOX_SURFACE, workingIds.toList())
    }

    fun finishEditing() {
        saveCurrentOrder()
        layoutController.finish()
        draggedId = null
        dragOffset = Offset.Zero
    }

    fun resetLayout() {
        layoutController.reset(ToolboxLayoutController.TOOLBOX_SURFACE)
        workingIds.clear()
        workingIds.addAll(toolboxEntries.map { it.entryId })
        draggedId = null
        dragOffset = Offset.Zero
    }

    fun dragTool(id: String, amount: Offset) {
        if (draggedId != id) return
        dragOffset += amount
        val currentIndex = workingIds.indexOf(id)
        if (currentIndex < 0) return
        val visible = gridState.layoutInfo.visibleItemsInfo
        val currentInfo = visible.firstOrNull { it.key == id } ?: return
        val centerX = currentInfo.offset.x + currentInfo.size.width / 2f + dragOffset.x
        val centerY = currentInfo.offset.y + currentInfo.size.height / 2f + dragOffset.y
        val targetInfo = visible.firstOrNull { info ->
            centerX >= info.offset.x && centerX <= info.offset.x + info.size.width &&
                centerY >= info.offset.y && centerY <= info.offset.y + info.size.height
        } ?: return
        val targetIndex = targetInfo.index
        if (targetIndex == currentIndex || targetIndex !in workingIds.indices) return

        dragOffset -= Offset(
            x = (targetInfo.offset.x - currentInfo.offset.x).toFloat(),
            y = (targetInfo.offset.y - currentInfo.offset.y).toFloat()
        )
        workingIds.removeAt(currentIndex)
        workingIds.add(targetIndex, id)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isEditing) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("工具箱布局编辑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("长按并拖动卡片调整顺序", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = ::resetLayout) { Text("恢复默认") }
                    Button(onClick = ::finishEditing) { Text("完成") }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(items = workingIds, key = { it }) { id ->
                val entry = entriesById[id] ?: return@items
                val tool = Tool(
                    id = entry.entryId,
                    name = entry.title,
                    icon = entry.icon,
                    description = entry.description,
                    onClick = { onNavigationEntrySelected(entry) }
                )
                val dragging = draggedId == id
                ToolCard(
                    tool = tool,
                    isEditing = isEditing,
                    isDragging = dragging,
                    dragOffset = if (dragging) dragOffset else Offset.Zero,
                    onDragStart = {
                        draggedId = id
                        dragOffset = Offset.Zero
                    },
                    onDrag = { amount -> dragTool(id, amount) },
                    onDragEnd = {
                        if (draggedId == id) {
                            saveCurrentOrder()
                            draggedId = null
                            dragOffset = Offset.Zero
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCard(
    tool: Tool,
    isEditing: Boolean = false,
    isDragging: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.04f
            isPressed -> 0.95f
            else -> 1f
        },
        animationSpec = tween(durationMillis = if (isPressed) 100 else 200),
        label = "scale"
    )

    val dragModifier = if (isEditing) {
        Modifier.pointerInput(tool.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onDragStart() },
                onDrag = { change, amount ->
                    change.consume()
                    onDrag(amount)
                },
                onDragEnd = onDragEnd,
                onDragCancel = onDragEnd
            )
        }
    } else {
        Modifier
    }

    val modifier = Modifier
        .fillMaxWidth()
        .height(156.dp)
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer {
            translationX = dragOffset.x
            translationY = dragOffset.y
        }
        .scale(scale)
        .then(dragModifier)

    if (isEditing) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 10.dp else 2.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            ToolCardContent(tool)
        }
    } else {
        Card(
            onClick = {
                isPressed = true
                scope.launch {
                    delay(100)
                    tool.onClick()
                    isPressed = false
                }
            },
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 8.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            ToolCardContent(tool)
        }
    }
}

@Composable
private fun ToolCardContent(tool: Tool) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = tool.name,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = tool.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            minLines = 1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        tool.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
                minLines = 1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
