package com.ai.limbs.plugins.artstudio

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONObject

@Composable internal fun StudioMeasureOptions(settings:JSONObject,busy:Boolean,onConfigure:(JSONObject)->Unit,onClear:()->Unit) {
    for(unit in listOf("px","mm","cm","in","pt"))FilterChip(settings.getString("unit")==unit,{onConfigure(JSONObject().put("unit",unit))},enabled=!busy,label={Text(unit)})
    for((mode,label) in listOf("auto" to "端点 / 线身自动","new" to "新建测量线","translate" to "整线平移","baseline" to "拖动设置基线"))
        FilterChip(settings.getString("dragMode")==mode,{onConfigure(JSONObject().put("dragMode",mode))},enabled=!busy,label={Text(label)})
    var ppi by remember(settings.toString()) {mutableStateOf(settings.getDouble("ppi").toString())}
    var baseline by remember(settings.toString()) {mutableStateOf(settings.getDouble("baseline").toString())}
    var step by remember(settings.toString()) {mutableStateOf(settings.getDouble("angleStep").toString())}
    var error by remember {mutableStateOf("")}
    OutlinedTextField(ppi,{ppi=it},enabled=!busy,label={Text("物理单位 PPI（工具设置）")})
    OutlinedTextField(baseline,{baseline=it},enabled=!busy,label={Text("测角基线 °")})
    OutlinedTextField(step,{step=it},enabled=!busy,label={Text("角度约束步长 °，0 关闭")})
    TextButton(enabled=!busy,onClick={try {val p=JSONObject().put("ppi",requireNotNull(ppi.toDoubleOrNull())).put("baseline",requireNotNull(baseline.toDoubleOrNull())).put("angleStep",requireNotNull(step.toDoubleOrNull()));ArtMeasure.settings(settings,p);onConfigure(p);error=""}catch(e:Exception){android.util.Log.e("ArtStudio","Measure parameters rejected",e);error=e.message ?: "参数无效"}}){Text("保存测量参数")}
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    Text("毫米等单位按上方 PPI 换算，文档暂没有印刷分辨率。拖端点可改长度，拖线身可平移；Shift 约束角度、Alt 整线平移、Ctrl 设置基线。",style=MaterialTheme.typography.bodySmall)
    TextButton(enabled=!busy,onClick=onClear){Text("清除测量线")}
}
