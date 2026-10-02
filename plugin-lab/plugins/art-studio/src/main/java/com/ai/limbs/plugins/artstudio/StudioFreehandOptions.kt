package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
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
    onFill: (Boolean) -> Unit, onEdit: (String, JSONObject) -> Unit,settings:JSONObject,onSettings:(JSONObject)->Unit) {
    val layer = ArtMenuOperations.layers(snapshot.getJSONObject("state"))
        .firstOrNull { it.getString("id") == selectedLayer }
    fun setting(key:String,value:Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
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
    Text("精度 " + String.format(Locale.ROOT, "%.2f", if(mode=="raw")settings.getDouble("rawPrecision") else if(mode=="curve")settings.getDouble("curvePrecision") else precision.toDouble()),
        style = MaterialTheme.typography.labelSmall)
    Slider(value = if(mode=="raw")settings.getDouble("rawPrecision").toFloat() else if(mode=="curve")settings.getDouble("curvePrecision").toFloat() else precision,
        onValueChange = {value->if(mode=="raw")setting("rawPrecision",value.toDouble()) else if(mode=="curve")setting("curvePrecision",value.toDouble()) else onPrecision(value)}, enabled = !busy,
        valueRange = 0.25f..32f, modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "路径误差精度，图层局部像素；越小越贴近轨迹"
        })
    if(mode=="raw"||mode=="curve") {
        val key=if(mode=="raw")"optimizeRaw" else "optimizeCurve"
        FilterChip(selected=settings.getBoolean(key),onClick={setting(key,!settings.getBoolean(key))},enabled=!busy,label={Text(if(mode=="raw")"优化 Raw 采样" else "优化 Curve 段数")})
    }
    if(mode=="straight") {
        Text("合并转角："+settings.getDouble("combineAngle").toInt()+"°，0关闭额外合并",style=MaterialTheme.typography.labelSmall)
        Slider(settings.getDouble("combineAngle").toFloat(),onValueChange={setting("combineAngle",it.toDouble())},valueRange=0f..90f,enabled=!busy)
    }
    FilterChip(selected=settings.getBoolean("connectEndpoints"),onClick={setting("connectEndpoints",!settings.getBoolean("connectEndpoints"))},enabled=!busy&&!closed,label={Text("接续已有端点")})
    Text("开启后靠近开放端点起笔/收笔可接续或合并，12dp范围；接回原路径另一端自动闭合。接续时不使用Shift闭合。",style=MaterialTheme.typography.labelSmall)
    Text("值小更贴近", style = MaterialTheme.typography.labelSmall)
    FilterChip(selected = closed, onClick = { onClosed(!closed) }, enabled = !busy&&!settings.getBoolean("connectEndpoints"),
        label = { Text("闭合路径", style = MaterialTheme.typography.labelSmall) })
    FilterChip(selected = fill, onClick = { onFill(!fill) }, enabled = !busy,
        label = { Text("闭合填色", style = MaterialTheme.typography.labelSmall) })
    val state=snapshot.getJSONObject("state")
    if(layer?.getString("kind")=="vector") {
        val ids=ArtShapes.selected(state,selectedLayer);val objects=ArtShapes.items(layer).filter {it.getString("id") in ids}
        if(objects.size>=2&&objects.all {it.getString("kind")=="path"})TextButton(enabled=!busy&&!ArtMenuOperations.isLocked(state,layer)&&objects.none {it.getBoolean("locked")},onClick={onEdit("SHAPE_PATH_COMBINE",JSONObject().put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision")).put("layerId",selectedLayer).put("ids",JSONArray(ids)))}) {Text("合成所选路径")}
    }
    val preview=ArtShapes.normalize(JSONObject().put("id","00000000-0000-0000-0000-000000000000").put("kind","line").put("points",JSONArray().put(JSONArray().put(0).put(0)).put(JSONArray().put(100).put(100))).put("objectStyle",settings.getJSONObject("objectStyle")))
    StudioObjectStyleOptions(listOf(preview),busy) {patch->val next=JSONObject(settings.toString());val style=JSONObject(next.getJSONObject("objectStyle").toString());patch.keys().forEach {style.put(it,patch.get(it))};next.put("objectStyle",style);onSettings(next)}
    Text("拖动绘制；Shift闭合。画后用形状选择修改。",
        style = MaterialTheme.typography.labelSmall)
}
