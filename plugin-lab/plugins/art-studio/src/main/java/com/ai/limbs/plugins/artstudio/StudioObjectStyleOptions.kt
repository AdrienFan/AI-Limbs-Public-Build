package com.ai.limbs.plugins.artstudio

import android.graphics.RectF
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StudioObjectStyleOptions(shapes:List<JSONObject>,busy:Boolean,onChange:(JSONObject)->Unit) {
    if(shapes.isEmpty())return
    val ids=shapes.map {it.getString("id")};val shape=shapes.first();val style=ArtObjectStyle.settings(shape)
    val enabled=!busy&&shapes.none {it.getBoolean("locked")||!it.getBoolean("visible")}
    var expanded by remember(ids) {mutableStateOf(false)}
    TextButton(enabled=!busy,onClick={expanded=!expanded}) {Text(if(expanded)"收起高级样式" else "高级对象样式")}
    if(!expanded)return
    Text("以首个对象显示参数，修改应用到所选对象。",style=MaterialTheme.typography.labelSmall)
    for((key,options) in listOf("strokeCap" to ArtObjectStyle.caps,"strokeJoin" to ArtObjectStyle.joins,"fillRule" to linkedMapOf("nonzero" to "非零填充","evenodd" to "奇偶填充")))
        options.forEach {(id,label)->FilterChip(selected=style.getString(key)==id,enabled=enabled,onClick={onChange(JSONObject().put(key,id))},label={Text(label)})}
    var miter by remember(ids) {mutableStateOf(style.getDouble("miterLimit").toString())}
    var dash by remember(ids) {mutableStateOf((0 until style.getJSONArray("dashArray").length()).joinToString(",") {style.getJSONArray("dashArray").getDouble(it).toString()})}
    var offset by remember(ids) {mutableStateOf(style.getDouble("dashOffset").toString())}
    OutlinedTextField(miter,{miter=it},enabled=enabled,label={Text("尖角限值 1–100")},singleLine=true)
    OutlinedTextField(dash,{dash=it},enabled=enabled,label={Text("虚线长度：交替实段/空段，逗号分隔；留空为实线")},singleLine=true)
    OutlinedTextField(offset,{offset=it},enabled=enabled,label={Text("虚线偏移（对象局部像素）")},singleLine=true)
    val m=miter.toDoubleOrNull();val d=if(dash.isBlank())emptyList() else dash.split(',').map {it.trim().toDoubleOrNull()};val o=offset.toDoubleOrNull()
    val valid=m!=null&&m.isFinite()&&m in 1.0..100.0&&o!=null&&o.isFinite()&&kotlin.math.abs(o)<=1000000&&
        (d.isEmpty()||d.size in 2..16&&d.size%2==0)&&d.all {it!=null&&it.isFinite()&&it in 0.1..4096.0}
    TextButton(enabled=enabled&&valid,onClick={onChange(JSONObject().put("miterLimit",requireNotNull(m)).put("dashArray",JSONArray(d.map {requireNotNull(it)})).put("dashOffset",requireNotNull(o)))}) {Text("应用描边参数")}
    for((key,label) in listOf("fillOpacity" to "填充透明度","strokeOpacity" to "描边透明度")) {
        var value by remember(ids,key) {mutableFloatStateOf(style.getDouble(key).toFloat())}
        Text(label+"："+(value*100).toInt()+"%",style=MaterialTheme.typography.labelSmall)
        Slider(value,onValueChange={value=it},onValueChangeFinished={onChange(JSONObject().put(key,value.toDouble()))},enabled=enabled)
    }
    var target by remember(ids) {mutableStateOf("fillGradient")};var type by remember(ids) {mutableStateOf("linear")}
    var first by remember(ids) {mutableStateOf(shape.getString("fill"))};var last by remember(ids) {mutableStateOf("#FFFFFFFF")}
    val bounds=RectF().also {ArtShapes.path(shape).computeBounds(it,true)}
    var x0 by remember(ids) {mutableStateOf(bounds.left.toString())};var y0 by remember(ids) {mutableStateOf(bounds.top.toString())}
    var x1 by remember(ids) {mutableStateOf((if(bounds.right!=bounds.left)bounds.right else bounds.left+100).toString())}
    var y1 by remember(ids) {mutableStateOf(bounds.bottom.toString())}
    Text("渐变（对象局部像素）",style=MaterialTheme.typography.titleSmall)
    for((id,label) in listOf("fillGradient" to "填充渐变","strokeGradient" to "描边渐变"))FilterChip(selected=target==id,enabled=enabled,onClick={target=id},label={Text(label)})
    for((id,label) in listOf("linear" to "线性","radial" to "径向"))FilterChip(selected=type==id,enabled=enabled,onClick={type=id},label={Text(label)})
    OutlinedTextField(first,{first=it.uppercase()},enabled=enabled,label={Text("起始色 #AARRGGBB")},singleLine=true)
    OutlinedTextField(last,{last=it.uppercase()},enabled=enabled,label={Text("终点色 #AARRGGBB")},singleLine=true)
    for((value,update,label) in listOf(Triple(x0,{v:String->x0=v},"起点 X"),Triple(y0,{v:String->y0=v},"起点 Y"),Triple(x1,{v:String->x1=v},"终点 X"),Triple(y1,{v:String->y1=v},"终点 Y")))
        OutlinedTextField(value,update,enabled=enabled,label={Text(label)},singleLine=true)
    val coords=listOf(x0,y0,x1,y1).map {it.toDoubleOrNull()}
    val validCoordinates=coords.all {it!=null&&it.isFinite()&&kotlin.math.abs(it)<=1000000}
    val gradientValid=validCoordinates&&listOf(first,last).all {it.matches(Regex("#[A-Fa-f0-9]{8}"))}&&
        kotlin.math.hypot(requireNotNull(coords[2])-requireNotNull(coords[0]),requireNotNull(coords[3])-requireNotNull(coords[1]))>0.001
    TextButton(enabled=enabled&&gradientValid,onClick={onChange(JSONObject().put(target,JSONObject().put("type",type)
        .put("start",JSONArray().put(requireNotNull(coords[0])).put(requireNotNull(coords[1]))).put("end",JSONArray().put(requireNotNull(coords[2])).put(requireNotNull(coords[3])))
        .put("stops",JSONArray().put(JSONArray().put(0).put(first)).put(JSONArray().put(1).put(last)))))}) {Text("应用渐变")}
    TextButton(enabled=enabled,onClick={onChange(JSONObject().put(target,JSONObject.NULL))}) {Text("取消此渐变，使用纯色")}
}
