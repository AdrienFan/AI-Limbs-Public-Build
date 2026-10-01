package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** The dialog only collects parameters; Store owns validation, rendering and history. */
@Composable
internal fun StudioTextEditor(captured: JSONObject, fonts: JSONArray, busy: Boolean,
    onDismiss: () -> Unit, onSubmit: (JSONObject) -> Unit) {
    var content by remember(captured) { mutableStateOf(captured.getString("content")) }
    var fontId by remember(captured) { mutableStateOf(captured.getString("fontId")) }
    var fontSize by remember(captured) { mutableStateOf(captured.getDouble("fontSize").toString()) }
    var boxWidth by remember(captured) { mutableStateOf(captured.getInt("boxWidth").toString()) }
    var lineSpacing by remember(captured) { mutableStateOf(captured.getDouble("lineSpacing").toString()) }
    var color by remember(captured) { mutableStateOf(captured.getString("color")) }
    var align by remember(captured) { mutableStateOf(captured.getString("align")) }
    var x by remember(captured) { mutableStateOf(captured.getDouble("x").toString()) }
    var y by remember(captured) { mutableStateOf(captured.getDouble("y").toString()) }
    var fontMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val choices = (0 until fonts.length()).map { fonts.getJSONObject(it) }
    val selectedFont = choices.firstOrNull { it.getString("id") == fontId }
    val numbersValid = fontSize.toDoubleOrNull()?.isFinite() == true &&
        boxWidth.toIntOrNull() != null && lineSpacing.toDoubleOrNull()?.isFinite() == true &&
        x.toDoubleOrNull()?.isFinite() == true && y.toDoubleOrNull()?.isFinite() == true
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (captured.has("id")) "编辑文字" else "添加文字") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(content, { content = it }, label = { Text("文字内容") },
                    minLines = 3, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { fontMenu = true }, enabled = !busy) {
                        Text(selectedFont?.getString("label") ?: "原字体不可用，请选择字体")
                    }
                    DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false },
                        modifier = Modifier.heightIn(max = 280.dp)) {
                        choices.forEach { item ->
                            DropdownMenuItem(text = { Text(item.getString("label")) }, onClick = {
                                fontId = item.getString("id"); fontMenu = false
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fontSize, { fontSize = it }, label = { Text("字号 px") },
                        singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedTextField(boxWidth, { boxWidth = it }, label = { Text("换行宽度 px") },
                        singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(lineSpacing, { lineSpacing = it }, label = { Text("行距倍数 1–3") },
                    singleLine = true, enabled = !busy)
                OutlinedTextField(color, { color = it }, label = { Text("颜色 #AARRGGBB") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("left" to "左对齐", "center" to "居中", "right" to "右对齐").forEach { (id, label) ->
                        FilterChip(selected = align == id, onClick = { align = id },
                            label = { Text(label) }, enabled = !busy)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(x, { x = it }, label = { Text("图层 X") },
                        singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedTextField(y, { y = it }, label = { Text("图层 Y") },
                        singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                }
                Text("原文保留在文字图层中；选择该图层后可再次编辑。移动、缩放、旋转使用现有图层工具。",
                    style = MaterialTheme.typography.bodySmall)
                Text(ArtText.NOTICE, style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && content.isNotBlank() && numbersValid && selectedFont != null,
                onClick = {
                    val p = JSONObject(captured.toString()).put("content", content).put("fontId", fontId)
                        .put("fontSize", fontSize.toDouble()).put("boxWidth", boxWidth.toInt())
                        .put("lineSpacing", lineSpacing.toDouble()).put("color", color).put("align", align)
                        .put("x", x.toDouble()).put("y", y.toDouble())
                    try { ArtText.normalize(p); error = ""; onSubmit(p) }
                    catch (problem: IllegalArgumentException) { error = problem.message.orEmpty() }
                }) { Text(if (busy) "处理中…" else "确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
