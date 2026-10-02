package com.ai.limbs.plugins.artstudio

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioContiguousFillOptions(store:ArtStore,p:JSONObject,color:String,busy:Boolean,draft:Boolean,
    onChange:(JSONObject)->Unit,onCancel:()->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var resources by remember {mutableStateOf(emptyList<JSONObject>())}
    var working by remember {mutableStateOf(false)};var menu by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val enabled=!busy&&!draft&&!working
    fun set(key:String,value:Any) {onChange(JSONObject(p.toString()).put(key,value))}
    fun pattern(key:String,value:Any) {val out=JSONObject(p.toString());out.getJSONObject("pattern").put(key,value);onChange(out)}
    fun choose(kind:String,asset:String?=null) {
        val out=JSONObject(p.toString());val tile=out.getJSONObject("pattern");tile.put("kind",kind).put("foreground",color)
        tile.remove("asset");if(asset!=null)tile.put("asset",asset)
        out.put("fillType","pattern");onChange(out)
    }
    suspend fun refresh() {val list=withContext(Dispatchers.IO) {store.brushResources("texture").getJSONArray("resources")}
        resources=(0 until list.length()).map {list.getJSONObject(it)}}
    LaunchedEffect(Unit) {try {refresh()} catch(e:Exception) {android.util.Log.e("ArtStudio","Fill patterns failed",e);error=e.message ?: e.toString()}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->if(uri!=null)scope.launch {
        working=true;error=""
        try {
            val item=withContext(Dispatchers.IO) {
                val input=context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法读取图案图片")
                val bytes=input.use {stream->val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);var total=0
                    while(true) {val n=stream.read(chunk);if(n<0)break;total+=n;require(total<=8*1024*1024) {"图案最多8MiB"};out.write(chunk,0,n)};out.toByteArray()}
                store.importBrushResource(JSONObject().put("kind","texture").put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)))
            }
            choose("image",item.getString("asset"));refresh()
        } catch(e:Exception) {android.util.Log.e("ArtStudio","Fill pattern import failed",e);error=e.message ?: e.toString()}
        finally {working=false}
    }}
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
    Text("填充内容（使用工具栏透明度）")
    Row {listOf("solid" to "工具栏颜色","pattern" to "图案").forEach {(id,label)->
        FilterChip(p.getString("fillType")==id,{if(id=="pattern")choose("checker") else set("fillType",id)},enabled=enabled,label={Text(label)})}}
    if(p.getString("fillType")=="pattern") {
        val tile=p.getJSONObject("pattern")
        Row(Modifier.horizontalScroll(rememberScrollState())) {ArtFigure.patterns.filterKeys {it!="image"}.forEach {(id,label)->
            FilterChip(tile.getString("kind")==id,{choose(id)},enabled=enabled,label={Text(label)})}}
        var foreground by remember(tile.getString("foreground")) {mutableStateOf(tile.getString("foreground"))}
        var background by remember(tile.getString("background")) {mutableStateOf(tile.getString("background"))}
        OutlinedTextField(foreground,{foreground=it.uppercase()},enabled=enabled,label={Text("图案前景 #AARRGGBB")})
        OutlinedTextField(background,{background=it.uppercase()},enabled=enabled,label={Text("图案背景 #AARRGGBB")})
        TextButton(enabled=enabled&&listOf(foreground,background).all {it.matches(Regex("#[A-F0-9]{8}"))},onClick={
            val out=JSONObject(p.toString());out.getJSONObject("pattern").put("foreground",foreground).put("background",background);onChange(out)
        }) {Text("应用图案颜色")}
        TextButton(enabled=enabled,onClick={menu=true}) {Text("选择已有图案图片")}
        DropdownMenu(menu,{menu=false}) {resources.forEach {item->DropdownMenuItem(text={Text(item.getString("name"))},
            onClick={choose("image",item.getString("asset"));menu=false},enabled=enabled)}}
        TextButton(enabled=enabled,onClick={picker.launch("image/*")}) {Text("导入图案图片（1–512px）")}
        if(tile.getString("kind")=="image")Text("图片：${tile.getString("asset").take(8)}；保留原色和透明度。")
        for((key,label,range) in listOf(Triple("tileSize","单元大小",4f..128f),Triple("scale","缩放",0.1f..16f),Triple("angle","旋转角",-360f..360f))) {
            if(key=="tileSize"&&tile.getString("kind")=="image")continue
            Text("$label：${tile.getDouble(key)}")
            Slider(tile.getDouble(key).toFloat(),{pattern(key,if(key=="tileSize")it.roundToInt() else it.toDouble())},enabled=enabled,valueRange=range)
        }
        var dx by remember(tile.getJSONArray("offset").toString()) {mutableStateOf(tile.getJSONArray("offset").getDouble(0).toString())}
        var dy by remember(tile.getJSONArray("offset").toString()) {mutableStateOf(tile.getJSONArray("offset").getDouble(1).toString())}
        OutlinedTextField(dx,{dx=it},enabled=enabled,label={Text("图案水平偏移 px")});OutlinedTextField(dy,{dy=it},enabled=enabled,label={Text("图案垂直偏移 px")})
        val x=dx.toDoubleOrNull();val y=dy.toDoubleOrNull();val valid=x!=null&&y!=null&&x.isFinite()&&y.isFinite()&&kotlin.math.abs(x)<=1000000&&kotlin.math.abs(y)<=1000000
        TextButton(enabled=enabled&&valid,onClick={pattern("offset",JSONArray().put(requireNotNull(x)).put(requireNotNull(y)))}) {Text("应用图案偏移")}
        Text("各区域共用同一平铺原点；图案的透明部分也用于擦除蒙版。")
    }
    Text("参数在首点固定。单次范围最多4194304像素、512折线点、8192像素取样点；复杂多色拖动请分段。目标使用可见未锁定、无分组或变换的绘画／图像层。")
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
}
