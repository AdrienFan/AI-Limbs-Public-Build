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
private fun SelectionToolModes(settings:JSONObject,enabled:Boolean,onSet:(String,Any)->Unit,labels:Boolean=false) {
    Text("选区模式")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtBezierSelection.modes.forEach {(id,label)->FilterChip(selected=settings.getString("mode")==id,
            enabled=enabled,onClick={onSet("mode",id)},label={Text(label)})}
    }
    Text("Shift 添加，Alt 减去，Ctrl 替换，Shift+Alt 相交；模式在开始操作时固定。")
    Text("参考来源")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        (listOf("current" to "当前层","visible" to "全部可见层")+if(labels)listOf("labels" to "颜色标签图层") else emptyList()).forEach {(id,label)->
            FilterChip(selected=settings.getString("reference")==id,enabled=enabled,onClick={onSet("reference",id)},label={Text(label)})}
    }
    if(labels && settings.getString("reference")=="labels") {
        val selected=ArtLayerLabels.parse(settings.getJSONArray("colorLabels"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ArtLayerLabels.names.forEach {(id,label)->FilterChip(selected=id in selected,enabled=enabled,
                onClick={val next=selected.toMutableSet();if(id in next) {if(next.size>1)next.remove(id)} else next.add(id)
                    onSet("colorLabels",org.json.JSONArray(next.sorted()))},label={Text(label,color=Color(ArtLayerLabels.colors.getValue(id)))})}
        }
        Text("先在图层属性中设置颜色标签。匹配的可见内容层合成作参考；匹配的带标签组包含可见子层。无匹配会提示。")
    }
    FilterChip(selected=settings.getBoolean("limitToSelection"),enabled=enabled,
        onClick={onSet("limitToSelection",!settings.getBoolean("limitToSelection"))},label={Text("仅在现有选区范围查找")})
    Text("参考保留图层变换，不包含背景、参考图像、尺规和蒙版编辑线索。当前层按原始透明度读取；选择实际内容层，图层组请用可见层参考。")
}
@Composable
internal fun StudioColorSelectionOptions(settings: JSONObject,connected: Boolean,busy: Boolean,onSettings: (JSONObject)->Unit) {
    fun set(key: String,value: Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
    Text(if(connected)"点击相近颜色的连续区域，只选中与取样点连通的部分。" else "点击取样颜色，选中参考范围中全部相近颜色，包括不相连的区域。")
    SelectionToolModes(settings,!busy,::set,labels=true)
    Text("颜色容差：${settings.getInt("tolerance")}%")
    Slider(settings.getInt("tolerance").toFloat(),{set("tolerance",it.roundToInt())},enabled=!busy,valueRange=if(settings.getInt("opacitySpread")<100)1f..100f else 0f..100f)
    Text("颜色覆盖硬度：${settings.getInt("opacitySpread")}%")
    Slider(settings.getInt("opacitySpread").toFloat(),{set("opacitySpread",it.roundToInt())},enabled=!busy&&settings.getInt("tolerance")>0,valueRange=0f..100f)
    Text("100%为容差内全选；降低硬度，让颜色差异产生逐渐降低的覆盖率。容差0时使用100%硬度精确匹配。")
    Text("抗锯齿：${(settings.getDouble("antialias")*100).roundToInt()}%")
    Slider(settings.getDouble("antialias").toFloat(),{set("antialias",it.toDouble())},enabled=!busy&&settings.getInt("feather")==0,valueRange=0f..1f)
    Text("羽化半径：${settings.getInt("feather")} px")
    Slider(settings.getInt("feather").toFloat(),{set("feather",it.roundToInt())},enabled=!busy,valueRange=0f..32f)
    Text("扩展／收缩：${settings.getInt("expand")} px")
    Slider(settings.getInt("expand").toFloat(),{set("expand",it.roundToInt())},enabled=!busy,valueRange=-64f..64f)
    FilterChip(selected=settings.getBoolean("stopAtDarkest"),enabled=!busy,
        onClick={set("stopAtDarkest",!settings.getBoolean("stopAtDarkest"))},label={Text("扩展到最暗像素时停止")})
    Text("仅影响正向扩展：可进入更暗或更不透明的像素，不继续越过它走向亮处；不改变原始颜色搜索。先扩展，再羽化；羽化大于0时不重复施加抗锯齿。")
    if(connected) {
        FilterChip(selected=settings.getBoolean("boundaryMode"),enabled=!busy,
            onClick={set("boundaryMode",!settings.getBoolean("boundaryMode"))},label={Text("以指定边界色围住的区域")})
        if(settings.getBoolean("boundaryMode")) {
            var boundary by remember(settings.getString("boundaryColor")) {mutableStateOf(settings.getString("boundaryColor"))}
            OutlinedTextField(boundary,{value->boundary=value.uppercase().take(9)
                if(boundary.matches(Regex("#[A-F0-9]{8}")))set("boundaryColor",boundary)},
                enabled=!busy,label={Text("边界色 #AARRGGBB")},isError=!boundary.matches(Regex("#[A-F0-9]{8}")))
        }
        Text("缺口处理半径：${settings.getInt("gapClose")} px")
        Slider(settings.getInt("gapClose").toFloat(),{set("gapClose",it.roundToInt())},enabled=!busy,valueRange=0f..8f,steps=7)
        Text("用侵蚀断开窄通道，再在原始颜色范围中恢复边缘。取样点必须位于处理后仍存在的区域。")
    }
    Text("8位软选区保留孔洞和分离区域；笔刷、擦除、复制、填色和滤镜使用同一覆盖率。单次范围最多4194304像素、32768扫描段；较大图片可先画矩形选区并勾选范围限制。")
}
@Composable
internal fun StudioMagneticSelectionOptions(settings: JSONObject,busy: Boolean,hasDraft: Boolean,prepared: Boolean,
    onSettings: (JSONObject)->Unit,onCommand: (String)->Unit) {
    fun set(key: String,value: Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
    val enabled=!busy && !hasDraft
    Text("点击放置锚点，或按住拖动沿轮廓追加锚点；路径自动吸附真实图像边缘。点击首个锚点、Enter 或完成按钮闭合。")
    Text(if(prepared)"边缘参考已准备" else "正在准备边缘参考；准备完成后即可放置锚点",color=if(prepared)Color.Unspecified else Color.Gray)
    Row {
        TextButton(enabled=!busy && hasDraft,onClick={onCommand("finish")}) {Text("完成")}
        TextButton(enabled=!busy && hasDraft,onClick={onCommand("back")}) {Text("撤销锚点")}
        TextButton(enabled=hasDraft,onClick={onCommand("cancel")}) {Text("取消")}
    }
    Text("已放置的锚点可拖动，拖出参考范围可删除。Esc／右键／双指取消；完成时只记录一次选区。")
    SelectionToolModes(settings,enabled,::set)
    StudioSoftSelectionControls(settings,enabled,::set)
    if(hasDraft)Text("这条路径使用开始时的参考和参数；完成或取消后再修改参数。",color=Color.Gray)
    for((key,label,range) in listOf(Triple("filterRadius","边缘采样半径",1f..4f),
        Triple("searchRadius","搜索半径",2f..64f),Triple("threshold","边缘阈值",0f..255f),Triple("anchorGap","拖动锚点间距（屏幕像素）",8f..128f))) {
        Text("$label：${settings.getInt(key)}")
        Slider(settings.getInt(key).toFloat(),{set(key,it.roundToInt())},enabled=enabled,valueRange=range)
    }
    Text("边缘吸附强度：${settings.getDouble("strength").toInt()}")
    Slider(settings.getDouble("strength").toFloat(),{set("strength",it.toDouble())},enabled=enabled,valueRange=1f..20f)
    Text("轮廓简化误差：${String.format("%.2f",settings.getDouble("precision"))} px")
    Slider(settings.getDouble("precision").toFloat(),{set("precision",it.toDouble())},enabled=enabled,valueRange=0.25f..4f)
    Text("最多128锚点、2048最终顶点；单段最多262144搜索像素，参考最多4194304像素。长距离请沿边缘增加中间锚点；过大参考可先用矩形选区限定范围。")
    ArtMagneticSelection.pending.forEach {Text("$it（待实现）",color=Color.Gray)}
}
