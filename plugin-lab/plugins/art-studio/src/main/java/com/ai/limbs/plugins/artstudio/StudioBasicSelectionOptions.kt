package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioBasicSelectionOptions(settings:JSONObject,busy:Boolean,draft:Boolean,hasSelection:Boolean,
    onSettings:(JSONObject)->Unit,onCommand:(String)->Unit,onAdjust:()->Unit) {
    fun set(key:String,value:Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
    val enabled=!busy&&!draft
    Text("创建选区时的组合")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtSoftSelection.modes.forEach {(id,label)->FilterChip(settings.getString("mode")==id,{set("mode",id)},enabled=enabled,label={Text(label)})}
    }
    Text("抗锯齿：${(settings.getDouble("antialias")*100).roundToInt()}%")
    Slider(settings.getDouble("antialias").toFloat(),{set("antialias",it.toDouble())},enabled=enabled,valueRange=0f..1f)
    Text("扩展／收缩：${settings.getInt("expand")} px")
    Slider(settings.getInt("expand").toFloat(),{set("expand",it.roundToInt())},enabled=enabled,valueRange=-64f..64f)
    Text("羽化半径：${settings.getInt("feather")} px")
    Slider(settings.getInt("feather").toFloat(),{set("feather",it.roundToInt())},enabled=enabled,valueRange=0f..32f)
    Row {
        TextButton(enabled=!busy&&draft,onClick={onCommand("finish")}) {Text("完成")}
        TextButton(enabled=!busy&&draft,onClick={onCommand("back")}) {Text("撤回顶点")}
        TextButton(enabled=draft,onClick={onCommand("cancel")}) {Text("取消")}
    }
    TextButton(enabled=enabled&&hasSelection,onClick=onAdjust) {Text("将扩展／收缩与羽化应用到当前选区")}
    Text("矩形和椭圆拖动创建；套索松手闭合；多边形逐点点击，点击首点、双击末点或完成按钮闭合。Enter 完成，Esc 取消，退格撤回顶点。")
    Text("Shift 添加，Alt 减去，Shift+Alt 相交，Ctrl 替换；参数在首点固定。先扩展／收缩，再羽化，再组合。再次羽化当前选区会累积；抗锯齿仅在创建时应用。")
    Text("软边使用0–255覆盖率。单次处理范围最多4194304像素、32768非零扫描段；超限会提示，请分区操作。")
}
