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
internal fun StudioFillPatternOptions(store:ArtStore,p:JSONObject,color:String,available:Boolean,onChange:(JSONObject)->Unit,onWorking:(Boolean)->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var resources by remember {mutableStateOf(emptyList<JSONObject>())}
    var working by remember {mutableStateOf(false)};var menu by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val enabled=available&&!working
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
        working=true;onWorking(true);error=""
        try {
            val item=withContext(Dispatchers.IO) {
                val input=context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法读取图案图片")
                val bytes=input.use {stream->val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);var total=0
                    while(true) {val n=stream.read(chunk);if(n<0)break;total+=n;require(total<=8*1024*1024) {"图案最多8MiB"};out.write(chunk,0,n)};out.toByteArray()}
                store.importBrushResource(JSONObject().put("kind","texture").put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)))
            }
            choose("image",item.getString("asset"));refresh()
        } catch(e:Exception) {android.util.Log.e("ArtStudio","Fill pattern import failed",e);error=e.message ?: e.toString()}
        finally {working=false;onWorking(false)}
    }}
    Text("填充内容")
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
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
}
