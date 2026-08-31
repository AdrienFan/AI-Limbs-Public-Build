package com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.center.PluginControlSnapshot
import java.text.DateFormat
import java.util.Date

enum class PluginSortMode(val label: String) {
    NAME_ASC("名称 A → Z"),
    NAME_DESC("名称 Z → A"),
    USE_COUNT_DESC("使用次数 多 → 少"),
    USE_COUNT_ASC("使用次数 少 → 多"),
    LAST_USED_DESC("最近使用 近 → 远"),
    LAST_USED_ASC("最近使用 远 → 近")
}

internal fun filterAndSortPlugins(
    items: List<PluginControlSnapshot>,
    query: String,
    sortMode: PluginSortMode
): List<PluginControlSnapshot> {
    val normalized = query.trim().lowercase()
    val filtered = if (normalized.isEmpty()) items else items.filter { snapshot ->
        val manifest = snapshot.plugin.activeManifest
        buildList {
            add(snapshot.plugin.pluginId)
            add(manifest?.display?.name.orEmpty())
            add(manifest?.display?.description.orEmpty())
            addAll(manifest?.roles.orEmpty())
            addAll(manifest?.provides?.capabilities.orEmpty())
            addAll(manifest?.provides?.services.orEmpty())
            addAll(manifest?.provides?.providers.orEmpty())
            manifest?.provides?.extensions.orEmpty().forEach { extension ->
                add(extension.point)
                add(extension.id)
            }
        }.any { it.lowercase().contains(normalized) }
    }
    val name: (PluginControlSnapshot) -> String = {
        it.plugin.activeManifest?.display?.name?.lowercase() ?: it.plugin.pluginId.lowercase()
    }
    return when (sortMode) {
        PluginSortMode.NAME_ASC -> filtered.sortedBy(name)
        PluginSortMode.NAME_DESC -> filtered.sortedByDescending(name)
        PluginSortMode.USE_COUNT_DESC -> filtered.sortedWith(compareByDescending<PluginControlSnapshot> { it.plugin.usage.useCount }.thenBy(name))
        PluginSortMode.USE_COUNT_ASC -> filtered.sortedWith(compareBy<PluginControlSnapshot> { it.plugin.usage.useCount }.thenBy(name))
        PluginSortMode.LAST_USED_DESC -> filtered.sortedWith(compareByDescending<PluginControlSnapshot> { it.plugin.usage.lastUsedAtEpochMs ?: Long.MIN_VALUE }.thenBy(name))
        PluginSortMode.LAST_USED_ASC -> filtered.sortedWith(compareBy<PluginControlSnapshot> { it.plugin.usage.lastUsedAtEpochMs ?: Long.MAX_VALUE }.thenBy(name))
    }
}

internal fun usageSummary(snapshot: PluginControlSnapshot): String {
    val usage = snapshot.plugin.usage
    val last = usage.lastUsedAtEpochMs?.let { epoch ->
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))
    } ?: "从未"
    return "使用 ${usage.useCount} 次 · 最近使用：$last"
}


@Composable
internal fun PluginSearchSortControls(
    input: String,
    appliedQuery: String,
    sortMode: PluginSortMode,
    onInputChange: (String) -> Unit,
    onApplySearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSortModeChange: (PluginSortMode) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var sortExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("搜索插件") },
            placeholder = { Text("名称、说明、ID、能力或扩展点") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onApplySearch()
                focusManager.clearFocus()
            })
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = {
                onApplySearch()
                focusManager.clearFocus()
            }) { Text("搜索") }
            if (appliedQuery.isNotBlank()) {
                TextButton(onClick = {
                    onClearSearch()
                    focusManager.clearFocus()
                }) { Text("清除搜索") }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    Text("排序：${sortMode.label}")
                }
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false }
                ) {
                    PluginSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                sortExpanded = false
                                onSortModeChange(mode)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CollapsiblePluginSectionHeader(
    title: String,
    expanded: Boolean,
    matchedCount: Int,
    totalCount: Int,
    searching: Boolean,
    onToggle: () -> Unit
) {
    val countText = if (searching) "$matchedCount / $totalCount" else totalCount.toString()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) "▼" else "▶", modifier = Modifier.padding(end = 8.dp))
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text("$countText 个", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun LazyListScrollIndicator(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val layoutInfo = state.layoutInfo
    val visible = layoutInfo.visibleItemsInfo
    val total = layoutInfo.totalItemsCount
    if (visible.isEmpty() || total <= visible.size) return

    BoxWithConstraints(
        modifier = modifier
            .width(7.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp, horizontal = 2.dp)
    ) {
        val visibleFraction = (visible.size.toFloat() / total.toFloat()).coerceIn(0.08f, 1f)
        val thumbHeight = maxHeight * visibleFraction
        val maxFirstIndex = (total - visible.size).coerceAtLeast(1)
        val progress = (visible.first().index.toFloat() / maxFirstIndex.toFloat()).coerceIn(0f, 1f)
        val yOffset = (maxHeight - thumbHeight) * progress
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    RoundedCornerShape(99.dp)
                )
        )
        Box(
            modifier = Modifier
                .offset(y = yOffset)
                .height(thumbHeight)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    RoundedCornerShape(99.dp)
                )
        )
    }
}
