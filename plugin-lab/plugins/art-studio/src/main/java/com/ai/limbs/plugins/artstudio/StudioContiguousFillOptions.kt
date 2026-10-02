package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioContiguousFillOptions(store:ArtStore,p:JSONObject,color:String,busy:Boolean,draft:Boolean,
    onChange:(JSONObject)->Unit,onCancel:()->Unit) {
    var importing by remember {mutableStateOf(false)}
    val enabled=!busy&&!draft&&!importing
    fun set(key:String,value:Any) {onChange(JSONObject(p.toString()).put(key,value))}
    Text("填充范围")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtContiguousFill.modes.forEach {(id,label)->FilterChip(p.getString("fillMode")==id,{
            val out=JSONObject(p.toString()).put("fillMode",id)
            if(id=="similar")out.put("dragMode","off").put("gapClose",0)
            onChange(out)
        },enabled=enabled,label={Text(label)})}
    }
    if(p.getString("fillMode")=="boundary") {
        var boundary by remember(p.getString("boundaryColor")) {mutableStateOf(p.getString("boundaryColor"))}
        OutlinedTextField(boundary,{boundary=it.uppercase().take(9)
            if(boundary.matches(Regex("#[A-F0-9]{8}")))set("boundaryColor",boundary)},enabled=enabled,
            label={Text("边界色 #AARRGGBB")},isError=!boundary.matches(Regex("#[A-F0-9]{8}")))
        Text("填到指定边界色为止；轮廓需闭合，或使用下方封闭缺口。")
    }
    Text("拖动填充")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtContiguousFill.drags.forEach {(id,label)->FilterChip(p.getString("dragMode")==id,{set("dragMode",id)},
            enabled=enabled&&(id=="off"||p.getString("fillMode")!="similar"),label={Text(label)})}
    }
    Text("同色拖动只处理与首点相同的参考色；任意区域模式处理经过的不同颜色区域。沿路径逐像素取样，松手一次提交、一次撤销。")
    if(draft)TextButton(onClick=onCancel) {Text("取消本次拖动")}
    Text("参考图层")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("current" to "当前层","visible" to "全部可见层","labels" to "颜色标签层").forEach {(id,label)->
            FilterChip(p.getString("reference")==id,{set("reference",id)},enabled=enabled,label={Text(label)})}
    }
    if(p.getString("reference")=="labels") {
        val selected=ArtLayerLabels.parse(p.getJSONArray("colorLabels"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {ArtLayerLabels.names.forEach {(id,label)->
            FilterChip(id in selected,{val next=selected.toMutableSet();if(id in next) {if(next.size>1)next.remove(id)} else next.add(id)
                set("colorLabels",JSONArray(next.sorted()))},enabled=enabled,label={Text(label)})}}
        Text("在图层属性中设置标签；匹配的可见内容层或带标签组作参考，填充始终写当前层。")
    }
    FilterChip(p.getBoolean("useSelectionAsBoundary"),{set("useSelectionAsBoundary",!p.getBoolean("useSelectionAsBoundary"))},
        enabled=enabled,label={Text("将现有选区作为搜索边界")})
    Text("已有选区始终限制写入；关闭搜索边界时可沿选区外图像判断连通，但仍只在选区内落色。")
    Text("颜色容差：${p.getInt("tolerance")}%")
    Slider(p.getInt("tolerance").toFloat(),{set("tolerance",it.roundToInt())},enabled=enabled,
        valueRange=if(p.getInt("opacitySpread")<100)1f..100f else 0f..100f)
    Text("软覆盖硬度：${p.getInt("opacitySpread")}%")
    Slider(p.getInt("opacitySpread").toFloat(),{set("opacitySpread",it.roundToInt())},enabled=enabled&&p.getInt("tolerance")>0,valueRange=0f..100f)
    Text("降低硬度让颜色差异产生渐变覆盖；容差0使用100%硬度。")
    Text("封闭缺口：${p.getInt("gapClose")} px")
    Slider(p.getInt("gapClose").toFloat(),{set("gapClose",it.roundToInt())},enabled=enabled&&p.getString("fillMode")!="similar",valueRange=0f..32f,steps=31)
    Text("阻断窄通道后恢复原边缘；过大的半径可能消掉很窄的待填区域，请减小半径。")
    StudioSoftSelectionControls(p,enabled,::set)
    FilterChip(p.getBoolean("stopAtDarkest"),{set("stopAtDarkest",!p.getBoolean("stopAtDarkest"))},enabled=enabled,label={Text("扩展到最暗像素时停止")})
    FilterChip(p.getBoolean("erase"),{set("erase",!p.getBoolean("erase"))},enabled=enabled,label={Text("擦除区域")})
    StudioFillPatternOptions(store,p,color,enabled,onChange,{importing=it})
    Text("参数在首点固定。单次范围最多4194304像素、512折线点、8192像素取样点；复杂多色拖动请分段。目标使用可见未锁定、无分组或变换的绘画／图像层。")
}
