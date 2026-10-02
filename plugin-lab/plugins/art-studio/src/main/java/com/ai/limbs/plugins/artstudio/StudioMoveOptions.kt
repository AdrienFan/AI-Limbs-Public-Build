package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject

@Composable
internal fun StudioMoveOptions(settings:JSONObject,busy:Boolean,ready:Boolean,
    configure:(JSONObject)->Unit,move:(Double,Double)->Unit,nudge:(String,Boolean)->Unit) {
    var x by remember {mutableStateOf("0")};var y by remember {mutableStateOf("0")}
    var step by remember(settings.toString()) {mutableStateOf(settings.getDouble("step").toString())}
    var ppi by remember(settings.toString()) {mutableStateOf(settings.getDouble("ppi").toString())}
    var multiplier by remember(settings.toString()) {mutableStateOf(settings.getDouble("largeMultiplier").toString())}
    var threshold by remember(settings.toString()) {mutableStateOf(settings.getInt("alphaThreshold").toString())}
    val enabled=ready && !busy
    fun change(key:String,value:Any)=configure(JSONObject().put(key,value))
    Text("拖动显示位移，松手应用；方向键微移，Shift＋方向键放大步进，Esc 取消拖动。拖动时 Shift 锁轴／Alt 精细移动。")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("current" to "当前图层", "content" to "内容拾取图层", "group" to "内容拾取所属组").forEach {(id,label)->
            FilterChip(selected=settings.getString("layerMode")==id,onClick={change("layerMode",id)},label={Text(label)},enabled=enabled)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("auto" to "有选区搬像素", "layer" to "始终整层", "selection" to "仅选区像素").forEach {(id,label)->
            FilterChip(selected=settings.getString("moveScope")==id,onClick={change("moveScope",id)},label={Text(label)},enabled=enabled)
        }
    }
    Text("选区模式使用当前层，支持绘画／图像及其组内变换；其它类型须选整层。按透明度与父组可见性拾取真实内容；所属组取最近父组。",style=MaterialTheme.typography.bodySmall)
    FilterChip(selected=settings.getBoolean("ignoreLocked"),onClick={change("ignoreLocked",!settings.getBoolean("ignoreLocked"))},label={Text("拾取跳过锁定层")},enabled=enabled)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ArtMove.units.forEach {unit->FilterChip(selected=settings.getString("unit")==unit,onClick={
            val pixels=ArtMove.toPixels(settings.getDouble("step"),settings.getString("unit"),settings.getDouble("ppi"))
            configure(JSONObject().put("unit",unit).put("step",ArtMove.fromPixels(pixels,unit,settings.getDouble("ppi"))))
        },label={Text(unit)},enabled=enabled)}
    }
    Text("PPI仅用于本工具物理单位换算，默认72；当前工程没有印刷分辨率元数据，不改变导出像素尺寸。",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(ppi,{ppi=it},label={Text("工具 PPI 1–2400")},singleLine=true,enabled=enabled)
    OutlinedTextField(step,{step=it},label={Text("方向键基础步进（${settings.getString("unit")}）")},singleLine=true,enabled=enabled)
    OutlinedTextField(multiplier,{multiplier=it},label={Text("Shift 步进倍数 1–100")},singleLine=true,enabled=enabled)
    OutlinedTextField(threshold,{threshold=it},label={Text("内容拾取覆盖阈值 1–255")},singleLine=true,enabled=enabled)
    TextButton(onClick={configure(JSONObject().put("ppi",ppi.toDouble()).put("step",step.toDouble()).put("largeMultiplier",multiplier.toDouble()).put("alphaThreshold",threshold.toInt()))},
        enabled=enabled && ppi.toDoubleOrNull()?.isFinite()==true && step.toDoubleOrNull()?.isFinite()==true && multiplier.toDoubleOrNull()?.isFinite()==true && threshold.toIntOrNull()!=null) {Text("保存步进与拾取参数")}
    OutlinedTextField(x,{x=it},label={Text("ΔX ${settings.getString("unit")}")},singleLine=true,enabled=enabled)
    OutlinedTextField(y,{y=it},label={Text("ΔY ${settings.getString("unit")}")},singleLine=true,enabled=enabled)
    TextButton(onClick={move(x.toDouble(),y.toDouble())},enabled=enabled && x.toDoubleOrNull()?.isFinite()==true && y.toDoubleOrNull()?.isFinite()==true) {Text("移动当前层／选区")}
    Text("选区位移按文档像素四舍五入；键盘步进至少1文档像素，不随画面缩放变化。",style=MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("left" to "←", "up" to "↑", "down" to "↓", "right" to "→").forEach {(id,label)->
            TextButton(onClick={nudge(id,false)},enabled=enabled) {Text(label)}
            TextButton(onClick={nudge(id,true)},enabled=enabled) {Text(label+" ×")}
        }
    }
}
