package com.ai.limbs.plugins.artstudio

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioFigureOptions(store:ArtStore,tool:String,paintLayer:Boolean,p:JSONObject,color:String,busy:Boolean,onChange:(JSONObject)->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var width by remember(tool) {mutableStateOf(if(tool in ArtFigure.tools)p.getDouble("fixedWidth").toString() else "0")}
    var height by remember(tool) {mutableStateOf(if(tool in ArtFigure.tools)p.getDouble("fixedHeight").toString() else "0")}
    var ratio by remember(tool) {mutableStateOf(if(tool in ArtFigure.tools)p.getDouble("fixedRatio").toString() else "0")}
    var radius by remember(tool) {mutableStateOf(if(tool in ArtFigure.tools)p.getDouble("cornerRadius").toString() else "0")}
    var offsetX by remember(tool) {mutableStateOf("0")}
    var offsetY by remember(tool) {mutableStateOf("0")}
    var foreground by remember(tool,color) {mutableStateOf(color)}
    var background by remember(tool) {mutableStateOf("#00000000")}
    var resources by remember {mutableStateOf(emptyList<JSONObject>())}
    var menu by remember {mutableStateOf(false)}
    var working by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val enabled=!busy&&!working&&(tool !in ArtRasterPath.tools || paintLayer)
    val fill=p.getJSONObject("figureFill");val pattern=fill.optJSONObject("pattern")
    fun change(action:(JSONObject)->Unit) {val updated=JSONObject(p.toString());action(updated);onChange(updated)}
    fun choosePattern(kind:String,asset:String?=null) {
        val part=JSONObject().put("kind",kind).put("foreground",foreground).put("background",background)
            .put("tileSize",pattern?.optInt("tileSize",16) ?: 16).put("scale",pattern?.optDouble("scale",1.0) ?: 1.0)
            .put("angle",pattern?.optDouble("angle",0.0) ?: 0.0)
        pattern?.optJSONArray("offset")?.let {part.put("offset",org.json.JSONArray(it.toString()))}
        if(asset!=null)part.put("asset",asset)
        change {it.put("figureFill",JSONObject().put("mode","pattern").put("pattern",part))}
    }
    suspend fun refresh() {
        val list=withContext(Dispatchers.IO) {store.brushResources("texture").getJSONArray("resources")}
        resources=(0 until list.length()).map {list.getJSONObject(it)}
    }
    LaunchedEffect(tool) {try {refresh()} catch(e:Exception) {android.util.Log.e("ArtStudio","Pattern resources failed",e);error=e.toString()}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri ->
        if(uri!=null)scope.launch {
            working=true;error=""
            try {
                val item=withContext(Dispatchers.IO) {
                    val input=context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法读取图片")
                    val bytes=input.use {stream ->
                        val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);var total=0
                        while(true) {val n=stream.read(chunk);if(n<0)break;total+=n;require(total<=8*1024*1024) {"图片最多8MiB"};out.write(chunk,0,n)}
                        out.toByteArray()
                    }
                    store.importBrushResource(JSONObject().put("kind","texture").put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)))
                }
                choosePattern("image",item.getString("asset"));refresh()
            } catch(e:Exception) {android.util.Log.e("ArtStudio","Pattern import failed",e);error=e.toString()}
            finally {working=false}
        }
    }
    if(tool in ArtFigure.tools) {
    Text("尺寸与几何",style=MaterialTheme.typography.titleSmall)
    Text("宽、高、宽高比填0表示自由；同时固定宽高时，以宽高为准。修改后点应用。单位为图层局部像素。",style=MaterialTheme.typography.labelSmall)
    OutlinedTextField(width,{width=it},enabled=enabled,label={Text("固定宽度")},singleLine=true)
    OutlinedTextField(height,{height=it},enabled=enabled,label={Text("固定高度")},singleLine=true)
    OutlinedTextField(ratio,{ratio=it},enabled=enabled,label={Text("固定宽高比 W/H")},singleLine=true)
    if(tool=="rectangle")OutlinedTextField(radius,{radius=it},enabled=enabled,label={Text("圆角半径")},singleLine=true)
    val w=width.toDoubleOrNull();val h=height.toDoubleOrNull();val r=ratio.toDoubleOrNull();val corner=radius.toDoubleOrNull()
    val valid=w!=null&&h!=null&&r!=null&&corner!=null&&listOf(w,h,r,corner).all {it.isFinite()}&&
        (w==0.0||w in 0.001..32768.0)&&(h==0.0||h in 0.001..32768.0)&&(r==0.0||r in 0.001..1000.0)&&corner in 0.0..16384.0
    TextButton(enabled=enabled&&valid,onClick={change {it.put("fixedWidth",requireNotNull(w)).put("fixedHeight",requireNotNull(h))
        .put("fixedRatio",requireNotNull(r)).put("cornerRadius",if(tool=="rectangle")requireNotNull(corner) else 0)}}) {Text("应用尺寸和圆角")}
    FilterChip(selected=p.getBoolean("drawFromCenter"),enabled=enabled,onClick={change {it.put("drawFromCenter",!p.getBoolean("drawFromCenter"))}},label={Text("从中心绘制")})
    Text("Shift：自由尺寸时约束1:1，已有尺寸约束时临时解除；Ctrl：中心绘制。圆角按短边一半限制。",style=MaterialTheme.typography.labelSmall)
    }
    Text("描边",style=MaterialTheme.typography.titleSmall)
    ArtFigure.outlines.filterKeys {paintLayer || it!="brush"}.forEach {(id,label)->
        FilterChip(selected=p.getString("outline")==id || (!paintLayer&&id=="basic"&&p.getString("outline")=="brush"),enabled=enabled&&(id!="none"||fill.getString("mode")!="none"),
            onClick={change {it.put("outline",id)}},label={Text(label)})
    }
    if(tool in ArtFigure.tools || tool=="polygon") {
    Text("填充",style=MaterialTheme.typography.titleSmall)
    val paletteValid=listOf(foreground,background).all {it.matches(Regex("#[A-Fa-f0-9]{8}"))}
    ArtFigure.fills.forEach {(id,label)->FilterChip(selected=fill.getString("mode")==id,enabled=enabled&&paletteValid&&(paintLayer||id!="pattern")&&(id!="none"||p.getString("outline")!="none"),
        onClick={if(id=="pattern")choosePattern("checker") else change {it.put("figureFill",JSONObject().put("mode",id).apply {if(id=="solid")put("color",foreground)})}},label={Text(label)})}
    OutlinedTextField(foreground,{foreground=it.uppercase()},enabled=enabled,label={Text("填充／图案前景 #AARRGGBB")},singleLine=true)
    if(fill.getString("mode")=="pattern") {
        OutlinedTextField(background,{background=it.uppercase()},enabled=enabled,label={Text("图案背景 #AARRGGBB")},singleLine=true)
        ArtFigure.patterns.filterKeys {it!="image"}.forEach {(id,label)->FilterChip(selected=pattern?.getString("kind")==id,enabled=enabled&&paletteValid,
            onClick={choosePattern(id)},label={Text(label)})}
        if(pattern?.getString("kind")=="image")Text("图片平铺："+pattern.getString("asset").take(8),style=MaterialTheme.typography.labelSmall)
        Box {
            TextButton(enabled=enabled&&paletteValid,onClick={menu=true}) {Text("选择图案图片")}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                resources.forEach {item ->DropdownMenuItem(text={Text(item.getString("name"))},onClick={choosePattern("image",item.getString("asset"));menu=false})}
            }
        }
        TextButton(enabled=enabled&&paletteValid,onClick={picker.launch("image/*")}) {Text("导入图案图片")}
        Text("RGBA图片边长1–512像素，保留原色和透明度。",style=MaterialTheme.typography.labelSmall)
        for((key,label,range) in listOf(Triple("tileSize","图案单元（像素）",4f..128f),Triple("scale","图案缩放",0.1f..16f),Triple("angle","图案角度",-180f..180f))) {
            if(key=="tileSize"&&pattern?.getString("kind")=="image")continue
            val value=pattern?.optDouble(key,if(key=="tileSize")16.0 else if(key=="scale")1.0 else 0.0) ?: 0.0
            Text(label+"："+String.format(java.util.Locale.ROOT,"%.2f",value),style=MaterialTheme.typography.labelSmall)
            Slider(value.toFloat(),onValueChange={v ->change {it.getJSONObject("figureFill").getJSONObject("pattern").put(key,if(key=="tileSize")v.roundToInt().toDouble() else v.toDouble())}},valueRange=range,enabled=enabled)
        }
        OutlinedTextField(offsetX,{offsetX=it},enabled=enabled,label={Text("图案横向偏移")},singleLine=true)
        OutlinedTextField(offsetY,{offsetY=it},enabled=enabled,label={Text("图案纵向偏移")},singleLine=true)
        val x=offsetX.toDoubleOrNull();val y=offsetY.toDoubleOrNull()
        val validOffset=x!=null&&y!=null&&x.isFinite()&&y.isFinite()&&kotlin.math.abs(x)<=1000000&&kotlin.math.abs(y)<=1000000
        TextButton(enabled=enabled&&validOffset,onClick={change {it.getJSONObject("figureFill").getJSONObject("pattern")
            .put("offset",org.json.JSONArray().put(requireNotNull(x)).put(requireNotNull(y)))}}) {Text("应用图案偏移")}
    }
    if(fill.getString("mode")!="none")TextButton(enabled=enabled&&paletteValid,onClick={change {
        val f=it.getJSONObject("figureFill");if(f.getString("mode")=="solid")f.put("color",foreground)
        else f.getJSONObject("pattern").put("foreground",foreground).put("background",background)
    }}) {Text("应用填充颜色")}
    }
    if(!paintLayer)Text(if(tool in ArtRasterPath.tools)"这些栅格路径请使用绘画图层。" else "矢量形状保留尺寸和圆角，使用普通描边与纯色填充；图案和笔刷轮廓请用绘画层。",style=MaterialTheme.typography.labelSmall)
    else Text("笔刷轮廓使用当前共享配置，压力固定1；几何轮廓不运行稳定器、加权平滑或停驻喷绘。",style=MaterialTheme.typography.labelSmall)
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
}
