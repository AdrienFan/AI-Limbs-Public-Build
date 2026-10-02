package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.roundToInt

@Composable
internal fun StudioEncloseFillOptions(store:ArtStore,settings: JSONObject,busy: Boolean,draft:Boolean,color: String,
    onColor: ()->Unit,onSettings: (JSONObject)->Unit,onCommand:(String)->Unit) {
    var importing by remember {mutableStateOf(false)}
    val enabled=!busy&&!draft&&!importing
    fun set(key: String,value: Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
    Text("套索、矩形、椭圆和画笔松手填色；贝塞尔闭合后填色。围合不替换现有选区；选区只限制最后写入，可一次撤销。")
    Text("围合方式")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtEncloseFill.shapes.forEach {(id,label)->FilterChip(selected=settings.getString("shape")==id,
            enabled=enabled,onClick={set("shape",id)},label={Text(label)})}
    }
    if(settings.getString("shape")=="bezier") {
        Text("点按放置角点，拖动拉出对称控制柄；点回首点或按完成闭合填色。完成前不写入工程。")
        Row {
            TextButton(enabled=!busy&&draft,onClick={onCommand("finish")}) {Text("完成围合")}
            TextButton(enabled=!busy&&draft,onClick={onCommand("back")}) {Text("退回节点")}
        }
    }
    if(draft)TextButton(enabled=!busy,onClick={onCommand("cancel")}) {Text("取消当前围合")}
    Text("填充条件")
    ArtEncloseFill.modes.forEach {(id,label)->FilterChip(selected=settings.getString("mode")==id,
        enabled=enabled,onClick={
            val next=JSONObject(settings.toString()).put("mode",id)
            if(id=="all")next.put("gapClose",0)
            onSettings(next)
        },label={Text(label)})}
    for((key,label) in listOf("includeContour" to "包含碰到围合边缘的区域","invert" to "反选填充区域","erase" to "擦除模式")) {
        FilterChip(selected=settings.getBoolean(key),enabled=enabled,onClick={val next=JSONObject(settings.toString()).put(key,!settings.getBoolean(key))
            if(key=="erase" && next.getBoolean("erase"))next.put("blend","normal")
            onSettings(next)},label={Text(label)})
    }
    Text("参考来源")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("current" to "当前图层","visible" to "全部可见图层","labels" to "标签图层").forEach {(id,label)->
            FilterChip(selected=settings.getString("reference")==id,enabled=enabled,onClick={set("reference",id)},label={Text(label)})
        }
    }
    if(settings.getString("reference")=="labels") {
        val selected=ArtLayerLabels.parse(settings.getJSONArray("colorLabels"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {ArtLayerLabels.names.forEach {(id,label)->
            FilterChip(selected=id in selected,enabled=enabled,onClick={
                val next=selected.toMutableSet();if(id in next) {if(next.size>1)next.remove(id)} else next.add(id)
                set("colorLabels",JSONArray(next.sorted()))
            },label={Text(label)})}}
        Text("图层属性中设置标签；匹配的可见内容层及带标签组用于参考，仍只写当前图层。")
    }
    Text("可见参考包括图层透明度和混合方式；不包括文档背景、参考图像、尺规和蒙版编辑线索。结果只写当前绘画或图像层。")
    TextButton(enabled=enabled,onClick=onColor) {Text("填充颜色：$color")}
    if(settings.getString("mode") !in setOf("all","transparent","not_transparent")) {
        var regionColor by remember(settings.getString("regionColor")) {mutableStateOf(settings.getString("regionColor"))}
        OutlinedTextField(regionColor,{value->
            regionColor=value.uppercase().take(9)
            if(regionColor.matches(Regex("#[A-F0-9]{8}")))set("regionColor",regionColor)
        },enabled=enabled,label={Text("条件颜色 #AARRGGBB")},isError=!regionColor.matches(Regex("#[A-F0-9]{8}")))
    }
    Text("颜色／透明容差：${settings.getInt("tolerance")}%")
    Slider(settings.getInt("tolerance").toFloat(),{set("tolerance",it.roundToInt())},enabled=enabled,valueRange=if(settings.getInt("opacitySpread")<100)1f..100f else 0f..100f)
    Text("软覆盖硬度：${settings.getInt("opacitySpread")}%")
    Slider(settings.getInt("opacitySpread").toFloat(),{set("opacitySpread",it.roundToInt())},enabled=enabled&&settings.getInt("tolerance")>0,valueRange=0f..100f)
    Text("降低硬度使颜色差异产生软覆盖；容差0时使用100%硬度。反选也保留软覆盖。")
    if(settings.getString("shape")=="brush") {
        Text("围合笔径：${settings.getInt("width")} px")
        Slider(settings.getInt("width").toFloat(),{set("width",it.roundToInt())},enabled=enabled,valueRange=1f..256f)
    }
    Text("不透明度：${(settings.getDouble("opacity")*100).roundToInt()}%")
    Slider(settings.getDouble("opacity").toFloat(),{set("opacity",it.toDouble())},enabled=enabled,valueRange=0f..1f)
    StudioSoftSelectionControls(settings,enabled,::set)
    FilterChip(selected=settings.getBoolean("stopAtDarkest"),enabled=enabled,
        onClick={set("stopAtDarkest",!settings.getBoolean("stopAtDarkest"))},label={Text("扩展到最暗像素时停止")})
    StudioFillPatternOptions(store,settings,color,enabled,onSettings,{importing=it})
    Text("混合方式（在当前图层内执行）")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtPixelBlend.names.forEach {(id,label)->FilterChip(selected=settings.getString("blend")==id,
            enabled=enabled&&!settings.getBoolean("erase"),onClick={set("blend",id)},label={Text(label)})}
    }
    Text("擦除使用目标层DST_OUT；颜色混合保存在历史中，预览与导出使用同一渲染入口。")
    Text("缺口闭合半径：${settings.getInt("gapClose")} px")
    Slider(settings.getInt("gapClose").toFloat(),{set("gapClose",it.roundToInt())},
        enabled=enabled && settings.getString("mode")!="all",valueRange=0f..8f,steps=7)
    Text("缺口处理用于颜色／透明条件：断开窄通道，再恢复选中区域边缘。全部区域模式暂不支持这一参数。圈线需完整包住想填的区域；包含边缘区域关闭时，伸到圈外的背景不会填。")
    Text("单次围合与软边处理范围合计最多4194304像素，并检查内存预算。过大的范围请分次围合。Esc、取消手势或双指操作不会写入。")
    ArtEncloseFill.pending.forEach {Text("$it（待实现）",color=Color.Gray)}
}
