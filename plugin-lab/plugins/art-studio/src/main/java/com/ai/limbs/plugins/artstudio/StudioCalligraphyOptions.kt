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
internal fun StudioCalligraphyOptions(snapshot: JSONObject,selected: String,busy: Boolean,
    angle: Float,fixation: Float,thinning: Float,smoothing: Float,pressure: Boolean,cap: String,
    onAngle: (Float)->Unit,onFixation: (Float)->Unit,onThinning: (Float)->Unit,
    onSmoothing: (Float)->Unit,onPressure: (Boolean)->Unit,onCap: (String)->Unit,
    onEdit: (String,JSONObject)->Unit) {
    val layer=ArtMenuOperations.layers(snapshot.getJSONObject("state")).firstOrNull { it.getString("id")==selected }
    Text("矢量书法笔",style=MaterialTheme.typography.labelSmall)
    if(layer?.getString("kind")!="vector") {
        TextButton(onClick={
            onEdit("VECTOR_LAYER_CREATE",JSONObject().put("id",UUID.randomUUID().toString())
                .put("name","矢量图层").put("select",true).put("parentId",
                    if(layer?.getString("kind")=="group") selected else layer?.optString("parentId").orEmpty()))
        },enabled=!busy) { Text("新建矢量层",style=MaterialTheme.typography.labelSmall) }
    }
    @Composable fun option(label: String,value: Float,range: ClosedFloatingPointRange<Float>,change: (Float)->Unit) {
        Text(label+" "+String.format(Locale.ROOT,"%.2f",value),style=MaterialTheme.typography.labelSmall)
        Slider(value=value,onValueChange=change,enabled=!busy,valueRange=range,
            modifier=Modifier.fillMaxWidth().semantics { contentDescription=label })
    }
    option("笔尖角度",angle,0f..180f,onAngle)
    option("固定度",fixation,0f..1f,onFixation)
    Text("1固定斜头；0随轨迹转向",style=MaterialTheme.typography.labelSmall)
    option("速度变细",thinning,-1f..1f,onThinning)
    Text("负值越快越粗；0关闭",style=MaterialTheme.typography.labelSmall)
    option("轨迹平滑",smoothing,0f..1f,onSmoothing)
    FilterChip(selected=pressure,onClick={onPressure(!pressure)},enabled=!busy,
        label={Text("笔压控制宽度",style=MaterialTheme.typography.labelSmall)})
    for((id,label) in listOf("flat" to "平头","round" to "圆头")) {
        FilterChip(selected=cap==id,onClick={onCap(id)},enabled=!busy,
            label={Text(label,style=MaterialTheme.typography.labelSmall)})
    }
    Text("宽度使用上方笔刷大小。鼠标/手指恒定压力，数位笔使用实际笔压。画后用形状选择和节点工具编辑轮廓。",
        style=MaterialTheme.typography.labelSmall)
    for(label in listOf("沿选中路径书写","数位笔角度感应","质量与阻力","书法预设")) {
        TextButton(onClick={},enabled=false) { Text(label+"（待实现）",style=MaterialTheme.typography.labelSmall) }
    }
}
