package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** This footer is outside the tool list's scrolling Column. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StudioQuickToolsPanel(
    state: JSONObject?,
    activeTool: String,
    zoomMode: String,
    busy: Boolean,
    onConfigure: (JSONObject) -> Unit,
    onUse: (String, Long) -> Unit
) {
    var configuring by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider()
    if (state == null) {
        Text("读取快捷配置…", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
        return
    }
    val slots = state.getJSONArray("slots")
    val revision = state.getLong("revision")
    fun command(action: String, slotId: String? = null, toolId: String? = null) {
        val p = JSONObject().put("action", action).put("expectedConfigRevision", revision)
        if (slotId != null) p.put("slotId", slotId)
        if (toolId != null) p.put("toolId", toolId)
        onConfigure(p)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // With no slots there is no grid row. Keep a deliberate route to add one back.
        if (slots.length() == 0) TextButton(enabled = !busy,
            onClick = { command("add") }, contentPadding = PaddingValues(2.dp)) {
            Text("＋添加快捷位", style = MaterialTheme.typography.labelSmall)
        }
        val items = (0 until slots.length()).map { slots.getJSONObject(it) }
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                pair.forEach { item ->
                    val id = item.getString("id")
                    val empty = item.isNull("toolId")
                    val toolId = if (empty) null else item.getString("toolId")
                    val available = item.getBoolean("available")
                    val selected = available && toolId == activeTool
                    val label = if (empty) "空快捷位，选择工具"
                        else if (available) item.getString("label") + "；双击配置快捷位"
                        else toolId + "，当前不可用；双击更换"
                    Surface(Modifier.size(40.dp).semantics {
                        contentDescription = label
                        this.selected = selected
                        customActions = listOf(CustomAccessibilityAction("配置快捷位") {
                            configuring = id; choosing = empty; true
                        })
                    }.combinedClickable(
                        enabled = !busy,
                        onClickLabel = label,
                        onClick = {
                            if (empty || !available) { configuring = id; choosing = true }
                            else onUse(id, revision)
                        },
                        onDoubleClick = { configuring = id; choosing = empty }
                    ), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (toolId == "zoom" && available) {
                                Icon(Icons.Default.Search, null, Modifier.size(24.dp))
                                Text(if (zoomMode == "in") "大" else "小", fontSize = 10.sp,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                            } else Text(if (empty) "＋" else if (available) item.getString("glyph") else "!",
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.size(40.dp))
            }
        }
    }
    val configured = (0 until slots.length()).map { slots.getJSONObject(it) }
        .firstOrNull { it.getString("id") == configuring }
    if (configured != null) {
        val id = configured.getString("id")
        AlertDialog(
            onDismissRequest = { if (!busy) configuring = null },
            title = { Text("快捷位配置") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    Text(if (configured.isNull("toolId")) "此位为空"
                        else "当前：" + configured.optString("label", configured.getString("toolId")))
                    Column(Modifier.fillMaxWidth()) {
                        TextButton(enabled = !busy, onClick = { choosing = true }) { Text("更换工具") }
                        TextButton(enabled = !busy && !configured.isNull("toolId"),
                            onClick = { choosing = false; command("clear", id) }) { Text("清空此位") }
                        TextButton(enabled = !busy && slots.length() < ArtQuickTools.MAX_SLOTS,
                            onClick = { command("add") }) { Text("添加快捷位（${slots.length()}/8）") }
                        TextButton(enabled = !busy,
                            onClick = { command("remove", id) }) { Text("移除快捷位") }
                    }
                    if (choosing) {
                        HorizontalDivider()
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            ArtToolCatalog.implemented.forEach { (toolId, label, glyph) ->
                                TextButton(enabled = !busy, onClick = {
                                    command("set", id, toolId); choosing = false
                                }) { Text("$glyph  $label") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { configuring = null }) { Text("完成") } }
        )
    }
}
