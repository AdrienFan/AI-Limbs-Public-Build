package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

@Composable
internal fun StudioFreehandOptions(snapshot: JSONObject, selectedLayer: String, busy: Boolean,
    mode: String, precision: Float, closed: Boolean, fill: Boolean,
    onMode: (String) -> Unit, onPrecision: (Float) -> Unit, onClosed: (Boolean) -> Unit,
    onFill: (Boolean) -> Unit, onEdit: (String, JSONObject) -> Unit) {
    val layer = ArtMenuOperations.layers(snapshot.getJSONObject("state"))
        .firstOrNull { it.getString("id") == selectedLayer }
    Text("徒手路径", style = MaterialTheme.typography.labelSmall)
    if (layer?.getString("kind") != "vector") {
        Text("需矢量层", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = {
            onEdit("VECTOR_LAYER_CREATE", JSONObject().put("id", UUID.randomUUID().toString())
                .put("name", "矢量图层").put("select", true).put("parentId",
                    if (layer?.getString("kind") == "group") selectedLayer else layer?.optString("parentId").orEmpty()))
        }, enabled = !busy) { Text("新建矢量层", style = MaterialTheme.typography.labelSmall) }
    }
    for ((id, label) in listOf("raw" to "原始轨迹", "curve" to "拟合曲线", "straight" to "直线化")) {
        FilterChip(selected = mode == id, onClick = { onMode(id) }, enabled = !busy,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) })
    }
    Text("精度 " + String.format(Locale.ROOT, "%.2f", precision),
        style = MaterialTheme.typography.labelSmall)
    Slider(value = precision, onValueChange = onPrecision, enabled = !busy && mode != "raw",
        valueRange = 0.25f..32f, modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "路径误差精度，图层局部像素；越小越贴近轨迹"
        })
    Text("值小更贴近", style = MaterialTheme.typography.labelSmall)
    FilterChip(selected = closed, onClick = { onClosed(!closed) }, enabled = !busy,
        label = { Text("闭合路径", style = MaterialTheme.typography.labelSmall) })
    FilterChip(selected = fill, onClick = { onFill(!fill) }, enabled = !busy,
        label = { Text("闭合填色", style = MaterialTheme.typography.labelSmall) })
    Text("拖动绘制；Shift闭合。画后用形状选择修改。",
        style = MaterialTheme.typography.labelSmall)
}
