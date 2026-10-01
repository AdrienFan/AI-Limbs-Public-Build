package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioEncloseFillOptions(settings: JSONObject,busy: Boolean,color: String,
    onColor: ()->Unit,onSettings: (JSONObject)->Unit) {
    fun set(key: String,value: Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
    Text("圈住多个区域后松手，一次填色。围合不替换现有选区；选区只限制最后写入，可一次撤销。")
    Text("围合方式")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtEncloseFill.shapes.forEach {(id,label)->FilterChip(selected=settings.getString("shape")==id,
            enabled=!busy,onClick={set("shape",id)},label={Text(label)})}
    }
    Text("填充条件")
    ArtEncloseFill.modes.forEach {(id,label)->FilterChip(selected=settings.getString("mode")==id,
        enabled=!busy,onClick={
            val next=JSONObject(settings.toString()).put("mode",id)
            if(id=="all")next.put("gapClose",0)
            onSettings(next)
        },label={Text(label)})}
    for((key,label) in listOf("includeContour" to "包含碰到围合边缘的区域","invert" to "反选填充区域","erase" to "擦除模式")) {
        FilterChip(selected=settings.getBoolean(key),enabled=!busy,onClick={set(key,!settings.getBoolean(key))},label={Text(label)})
    }
    Text("参考来源")
    Row {
        listOf("current" to "当前图层","visible" to "全部可见图层").forEach {(id,label)->
            FilterChip(selected=settings.getString("reference")==id,enabled=!busy,onClick={set("reference",id)},label={Text(label)})
        }
    }
    Text("可见参考包括图层透明度和混合方式；不包括文档背景、参考图像、尺规和蒙版编辑线索。结果只写当前绘画或图像层。")
    TextButton(enabled=!busy,onClick=onColor) {Text("填充颜色：$color")}
    if(settings.getString("mode") !in setOf("all","transparent","not_transparent")) {
        var regionColor by remember(settings.getString("regionColor")) {mutableStateOf(settings.getString("regionColor"))}
        OutlinedTextField(regionColor,{value->
            regionColor=value.uppercase().take(9)
            if(regionColor.matches(Regex("#[A-F0-9]{8}")))set("regionColor",regionColor)
        },enabled=!busy,label={Text("条件颜色 #AARRGGBB")},isError=!regionColor.matches(Regex("#[A-F0-9]{8}")))
    }
    Text("颜色／透明容差：${settings.getInt("tolerance")}%")
    Slider(settings.getInt("tolerance").toFloat(),{set("tolerance",it.roundToInt())},enabled=!busy,valueRange=0f..100f)
    if(settings.getString("shape")=="brush") {
        Text("围合笔径：${settings.getInt("width")} px")
        Slider(settings.getInt("width").toFloat(),{set("width",it.roundToInt())},enabled=!busy,valueRange=1f..256f)
    }
    Text("不透明度：${(settings.getDouble("opacity")*100).roundToInt()}%")
    Slider(settings.getDouble("opacity").toFloat(),{set("opacity",it.toDouble())},enabled=!busy,valueRange=0f..1f)
    Text("扩展／收缩：${settings.getInt("expand")} px")
    Slider(settings.getInt("expand").toFloat(),{set("expand",it.roundToInt())},enabled=!busy,valueRange=-16f..16f,steps=31)
    Text("羽化半径：${settings.getInt("feather")} px")
    Slider(settings.getInt("feather").toFloat(),{set("feather",it.roundToInt())},enabled=!busy,valueRange=0f..8f,steps=7)
    Text("缺口闭合半径：${settings.getInt("gapClose")} px")
    Slider(settings.getInt("gapClose").toFloat(),{set("gapClose",it.roundToInt())},
        enabled=!busy && settings.getString("mode")!="all",valueRange=0f..8f,steps=7)
    Text("缺口处理用于颜色／透明条件：断开窄通道，再恢复选中区域边缘。全部区域模式暂不支持这一参数。圈线需完整包住想填的区域；包含边缘区域关闭时，伸到圈外的背景不会填。")
    Text("单次围合矩形范围最多4194304像素，并检查内存预算。过大的范围请分次围合。Esc、取消手势或双指操作不会写入。")
    ArtEncloseFill.pending.forEach {Text("$it（待实现）",color=Color.Gray)}
}
