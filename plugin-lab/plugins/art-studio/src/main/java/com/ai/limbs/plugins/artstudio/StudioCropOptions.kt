package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject

@Composable
internal fun StudioCropOptions(settings: JSONObject, draft: JSONObject?, busy: Boolean,
    onSettings: (JSONObject)->Unit, onSet: (JSONObject)->Unit, command: (String)->Unit) {
    var x by remember {mutableStateOf("0")};var y by remember {mutableStateOf("0")}
    var width by remember {mutableStateOf("512")};var height by remember {mutableStateOf("512")}
    var ratio by remember {mutableStateOf("1.0")}
    LaunchedEffect(draft?.toString()) {
        if(draft!=null) {x=draft.getInt("x").toString();y=draft.getInt("y").toString();width=draft.getInt("width").toString();height=draft.getInt("height").toString()}
    }
    fun change(key:String,value:Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
    Text("拖出框后拖动内部或八个控制点；松手保留，确认才裁剪。Enter 确认／Esc 取消。")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("canvas" to "画布范围", "layer" to "当前图层／组").forEach {(id,label)->
            FilterChip(selected=settings.getString("target")==id,onClick={command("cancel");change("target",id)},label={Text(label)},enabled=!busy)
        }
    }
    Text("帧裁剪尚缺动画帧数据与时间轴。",style=MaterialTheme.typography.bodySmall)
    if(settings.getString("target")=="layer")Text("图层采用可编辑裁剪边界，保留变换和源数据；后续绘制也受边界约束。",style=MaterialTheme.typography.bodySmall)
    else Text("只改变画布范围，保留框外源内容；扩展可重新显示源内容。",style=MaterialTheme.typography.bodySmall)
    Row {
        FilterChip(selected=settings.getBoolean("allowGrow"),onClick={change("allowGrow",!settings.getBoolean("allowGrow"))},label={Text("允许向外扩展")},enabled=!busy)
        FilterChip(selected=settings.getBoolean("fromCenter"),onClick={change("fromCenter",!settings.getBoolean("fromCenter"))},label={Text("中心绘制／调整")},enabled=!busy)
    }
    OutlinedTextField(x,{x=it},label={Text("X 文档像素（可负数）")},singleLine=true,enabled=!busy)
    OutlinedTextField(y,{y=it},label={Text("Y 文档像素（可负数）")},singleLine=true,enabled=!busy)
    OutlinedTextField(width,{width=it},label={Text("宽 1–16384")},singleLine=true,enabled=!busy)
    OutlinedTextField(height,{height=it},label={Text("高 1–16384")},singleLine=true,enabled=!busy)
    Row {
        FilterChip(selected=settings.getBoolean("lockWidth"),onClick={
            val value=width.toIntOrNull()
            if(value!=null && value in 1..16384)onSettings(JSONObject(settings.toString()).put("lockRatio",false).put("fixedWidth",value).put("lockWidth",!settings.getBoolean("lockWidth")))
        },label={Text("锁宽")},enabled=!busy)
        FilterChip(selected=settings.getBoolean("lockHeight"),onClick={
            val value=height.toIntOrNull()
            if(value!=null && value in 1..16384)onSettings(JSONObject(settings.toString()).put("lockRatio",false).put("fixedHeight",value).put("lockHeight",!settings.getBoolean("lockHeight")))
        },label={Text("锁高")},enabled=!busy)
    }
    OutlinedTextField(ratio,{ratio=it},label={Text("比例 = 宽 / 高（1/128–128）")},singleLine=true,enabled=!busy)
    FilterChip(selected=settings.getBoolean("lockRatio"),onClick={
        val value=ratio.toDoubleOrNull()
        if(value!=null && value.isFinite() && value in 1.0/128..128.0)
            onSettings(JSONObject(settings.toString()).put("lockWidth",false).put("lockHeight",false).put("ratio",value).put("lockRatio",!settings.getBoolean("lockRatio")))
    },label={Text("锁定比例")},enabled=!busy)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("none" to "无线", "thirds" to "三分", "fifths" to "五分", "golden" to "黄金分割", "diagonal" to "对角", "cross" to "中心十字").forEach {(id,label)->
            FilterChip(selected=settings.getString("guides")==id,onClick={change("guides",id)},label={Text(label)},enabled=!busy)
        }
    }
    TextButton(onClick={
        val p=JSONObject().put("x",x.toInt()).put("y",y.toInt()).put("width",width.toInt()).put("height",height.toInt())
        onSet(p)
    },enabled=!busy && x.toIntOrNull()!=null && y.toIntOrNull()!=null && (width.toIntOrNull()?.let {it in 1..16384} == true) && (height.toIntOrNull()?.let {it in 1..16384} == true)) {Text("应用坐标／尺寸")}
    Row {
        TextButton(onClick={command("finish")},enabled=!busy && draft!=null) {Text("确认裁剪")}
        TextButton(onClick={command("cancel")},enabled=!busy && draft!=null) {Text("取消裁剪框")}
    }
}
