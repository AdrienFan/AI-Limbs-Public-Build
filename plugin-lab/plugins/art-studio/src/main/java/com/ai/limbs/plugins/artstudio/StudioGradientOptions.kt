package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StudioGradientOptions(p:JSONObject,color:String,busy:Boolean,draft:Boolean,
    onChange:(JSONObject)->Unit,onCancel:()->Unit) {
    val enabled=!busy&&!draft
    fun set(key:String,value:Any) {onChange(JSONObject(p.toString()).put(key,value))}
    Text("渐变形状")
    Row(Modifier.horizontalScroll(rememberScrollState())) {ArtGradient.modes.forEach {(id,label)->
        FilterChip(p.getString("gradientMode")==id,{
            val next=JSONObject(p.toString()).put("gradientMode",id)
            if(id in setOf("spiral","reverse_spiral"))next.put("gradientRepeat","forward")
            onChange(next)
        },enabled=enabled,label={Text(label)})}}
    Text("拖出方向与范围；双线性以首点为中线向两侧变化，方形沿拖动方向旋转。轮廓模式需先建立选区，拖动只触发应用。")
    if(draft)TextButton(enabled=!busy,onClick=onCancel) {Text("取消当前渐变")}
    Text("色标")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(enabled=enabled,onClick={val next=JSONObject(p.toString());next.remove("gradientStops");next.remove("gradientEndColor");onChange(next)}) {Text("前景 → 透明")}
        TextButton(enabled=enabled,onClick={val next=JSONObject(p.toString());next.remove("gradientStops");next.put("gradientEndColor","#FFFFFFFF");onChange(next)}) {Text("前景 → 终点色")}
        TextButton(enabled=enabled,onClick={val next=JSONObject(p.toString());next.remove("gradientEndColor");next.put("gradientStops",JSONArray()
            .put(JSONArray().put(0).put(color)).put(JSONArray().put(0.5).put("#FFFFC878")).put(JSONArray().put(1).put("#FF245364")));onChange(next)}) {Text("多色标")}
    }
    if(p.has("gradientStops")) {
        val stops=p.getJSONArray("gradientStops")
        for(i in 0 until stops.length()) {
            val stop=stops.getJSONArray(i)
            var position by remember(i,stop.toString()) {mutableStateOf(stop.getDouble(0).toString())}
            var ink by remember(i,stop.toString()) {mutableStateOf(stop.getString(1))}
            val x=position.toDoubleOrNull();val valid=x!=null&&x.isFinite()&&
                (if(i==0)x==0.0 else if(i==stops.length()-1)x==1.0 else x>stops.getJSONArray(i-1).getDouble(0)&&x<stops.getJSONArray(i+1).getDouble(0))
            OutlinedTextField(position,{position=it},enabled=enabled&&i!=0&&i!=stops.length()-1,label={Text("色标${i+1}位置 0–1")},isError=!valid)
            OutlinedTextField(ink,{ink=it.uppercase().take(9)},enabled=enabled,label={Text("色标${i+1} #AARRGGBB")},isError=!ink.matches(Regex("#[A-F0-9]{8}")))
            Row {
                TextButton(enabled=enabled&&valid&&ink.matches(Regex("#[A-F0-9]{8}")),onClick={
                    val next=JSONObject(p.toString());next.getJSONArray("gradientStops").put(i,JSONArray().put(requireNotNull(x)).put(ink));onChange(next)
                }) {Text("应用色标")}
                if(i!=0&&i!=stops.length()-1)TextButton(enabled=enabled,onClick={val next=JSONObject(p.toString());next.getJSONArray("gradientStops").remove(i);onChange(next)}) {Text("删除")}
            }
        }
        TextButton(enabled=enabled&&stops.length()<ArtGradient.MAX_STOPS,onClick={
            val interval=(0 until stops.length()-1).maxByOrNull {stops.getJSONArray(it+1).getDouble(0)-stops.getJSONArray(it).getDouble(0)}!!
            val added=JSONArray()
            for(i in 0 until stops.length()) {added.put(JSONArray(stops.getJSONArray(i).toString()))
                if(i==interval)added.put(JSONArray().put((stops.getJSONArray(i).getDouble(0)+stops.getJSONArray(i+1).getDouble(0))/2).put(color))}
            set("gradientStops",added)
        }) {Text("添加色标（最多16个）")}
    } else if(p.has("gradientEndColor")) {
        var ink by remember(p.getString("gradientEndColor")) {mutableStateOf(p.getString("gradientEndColor"))}
        OutlinedTextField(ink,{ink=it.uppercase().take(9)},enabled=enabled,label={Text("终点色 #AARRGGBB")})
        TextButton(enabled=enabled&&ink.matches(Regex("#[A-F0-9]{8}")),onClick={set("gradientEndColor",ink)}) {Text("应用终点色")}
    }
    Text("重复方式")
    Row(Modifier.horizontalScroll(rememberScrollState())) {ArtGradient.repeats.forEach {(id,label)->
        FilterChip(p.getString("gradientRepeat")==id,{set("gradientRepeat",id)},enabled=enabled,label={Text(label)})}}
    FilterChip(p.getBoolean("gradientReverse"),{set("gradientReverse",!p.getBoolean("gradientReverse"))},enabled=enabled,label={Text("反向整个色标序列")})
    Text("颜色插值（保留预乘透明度）")
    Row {ArtGradient.interpolations.forEach {(id,label)->FilterChip(p.getString("gradientInterpolation")==id,
        {set("gradientInterpolation",id)},enabled=enabled,label={Text(label)})}}
    FilterChip(p.getBoolean("gradientDither"),{set("gradientDither",!p.getBoolean("gradientDither"))},enabled=enabled,label={Text("RGB量化抖动")})
    var seed by remember(p.getInt("gradientSeed")) {mutableStateOf(p.getInt("gradientSeed").toString())}
    OutlinedTextField(seed,{seed=it},enabled=enabled&&p.getBoolean("gradientDither"),label={Text("抖动种子（非负整数）")})
    val n=seed.toIntOrNull()
    TextButton(enabled=enabled&&n!=null&&n>=0,onClick={set("gradientSeed",requireNotNull(n))}) {Text("应用种子")}
    Text("接缝抗锯齿：${(p.getDouble("gradientAntialias")*100).toInt()}%")
    Slider(p.getDouble("gradientAntialias").toFloat(),{set("gradientAntialias",it.toDouble())},enabled=enabled&&p.getString("gradientMode")!="shape",valueRange=0f..1f)
    Text("抗锯齿通过四个子像素颜色取样平滑重复/角度接缝，透明度使用工具栏参数。轮廓使用像素距离场，已有软选区只乘一次。")
    Text("首点固定参数、选区、图层及版本；拖动预览最大256px，每100ms更新，松手一次保存。完整渐变范围最多4194304局部像素，过大时先用选区限定。")
}
