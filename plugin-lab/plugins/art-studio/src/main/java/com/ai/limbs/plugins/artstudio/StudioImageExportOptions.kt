package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Both File export dialogs share format selection and the same explicit animation choice. */
@Composable
internal fun StudioImageExportOptions(context: JSONObject, fullAnimation: Boolean, onAnimation: (Boolean) -> Unit,
    format: String, onFormat: (String) -> Unit, support: List<ArtExportFormats.Support>,
    maxEdge: String, onMaxEdge: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (context.getBoolean("hasTracks")) {
            Text("工程包含动画轨道，请选择导出内容。")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !fullAnimation, onClick = { onAnimation(false) }, label = { Text("当前帧") })
                FilterChip(selected = fullAnimation, onClick = { onAnimation(true) }, label = { Text("完整 GIF") })
            }
        }
        if (fullAnimation) {
            val animation = context.getJSONObject("animation")
            Text("导出播放范围 ${animation.getInt("start")}–${animation.getInt("end")} 帧，${animation.getInt("fps")} FPS。")
            OutlinedTextField(maxEdge, onMaxEdge, Modifier.fillMaxWidth(), label = { Text("GIF最大边（64–1024像素）") }, singleLine = true)
            Text("保持原图比例，不放大。完整GIF使用播放范围和循环设置；最多32 Mi帧像素，自适应255色加二值透明。")
        } else {
            Text(if (context.getBoolean("hasTracks")) "导出时间轴当前第 ${context.getInt("frame")} 帧。" else "导出静态图像。")
            support.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        FilterChip(selected = format == item.format.id, onClick = { onFormat(item.format.id) },
                            enabled = item.available, modifier = Modifier.weight(1f),
                            label = { Text(item.format.label + if (item.available) "" else "（不支持）") })
                    }
                }
            }
            Text(ArtExportFormats.get(format).notice, style = MaterialTheme.typography.bodySmall)
            support.filter { !it.available }.groupBy { it.reason }.forEach { (reason, items) ->
                Text(items.joinToString(" / ") { it.format.label } + "：" + reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
