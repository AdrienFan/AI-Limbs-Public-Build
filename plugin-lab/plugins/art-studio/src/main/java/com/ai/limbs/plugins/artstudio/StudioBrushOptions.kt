package com.ai.limbs.plugins.artstudio

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Complete supported engine options; curve edits commit only after explicit validation. */
@Composable
internal fun StudioBrushOptions(store:ArtStore,tool:String,brush:JSONObject,width:Float,opacity:Float,busy:Boolean,
    onChange:(JSONObject)->Unit,onStyle:(Float,Float)->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var presets by remember(tool) {mutableStateOf(emptyList<JSONObject>())}
    var resources by remember {mutableStateOf(emptyList<JSONObject>())}
    var name by remember(tool) {mutableStateOf("")};var selected by remember(tool) {mutableStateOf("")}
    var error by remember {mutableStateOf("")};var working by remember {mutableStateOf(false)}
    var importKind by remember {mutableStateOf("tip")}
    suspend fun refresh() {
        val data=withContext(Dispatchers.IO) {store.brushPresets(tool,true) to store.brushResources()}
        val p=data.first.getJSONArray("presets");presets=(0 until p.length()).map {p.getJSONObject(it)}
        val r=data.second.getJSONArray("resources");resources=(0 until r.length()).map {r.getJSONObject(it)}
    }
    fun run(action:suspend ()->Unit) {scope.launch {working=true;error=""
        try {action()} catch(e:Exception) {error=e.message ?: "笔刷操作失败"} finally {working=false} } }
    LaunchedEffect(tool) {try {refresh()} catch(e:Exception) {error=e.message ?: "无法读取笔刷"}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri ->
        if(uri!=null)run {
            val kind=importKind
            val item=withContext(Dispatchers.IO) {
                val bytes=context.contentResolver.openInputStream(uri)?.use {input ->
                    val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);var total=0
                    while(true) {val n=input.read(chunk);if(n<0)break;total+=n;require(total<=8*1024*1024) {"图像不得超过8MiB"};out.write(chunk,0,n)}
                    out.toByteArray()
                } ?: throw IllegalArgumentException("无法读取图像")
                store.importBrushResource(JSONObject().put("kind",kind).put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)))
            }
            val next=JSONObject(brush.toString());val part=next.getJSONObject(kind)
            part.put(if(kind=="tip")"shape" else "kind","image").put("asset",item.getString("asset"))
            onChange(ArtBrush.settings(tool,next));refresh()
        }
    }
    fun change(block:(JSONObject)->Unit) {
        val next=JSONObject(brush.toString());block(next)
        try {onChange(ArtBrush.settings(tool,next));error=""} catch(e:Exception) {error=e.message ?: "参数无效"}
    }
    val enabled=!busy && !working
    Text("笔刷预设",style=MaterialTheme.typography.titleSmall)
    BrushChoice("预设",selected,presets.associate {it.getString("id") to it.getString("name")},enabled) {id ->
        val p=presets.first {it.getString("id")==id};selected=id;name=p.getString("name")
        onChange(JSONObject(p.getJSONObject("brush").toString()));onStyle(p.getDouble("width").toFloat(),p.getDouble("opacity").toFloat())
    }
    OutlinedTextField(name,{name=it},label={Text("预设名称")},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
    Row {
        TextButton(enabled=enabled && name.isNotBlank(),onClick={run {
            withContext(Dispatchers.IO) {store.saveBrushPreset(JSONObject().put("name",name).put("tool",tool).put("brush",brush)
                .put("width",width.toDouble()).put("opacity",opacity.toDouble()))};refresh()
        }}) {Text("另存预设")}
        TextButton(enabled=enabled && selected.isNotBlank() && !selected.startsWith("builtin:"),onClick={run {
            withContext(Dispatchers.IO) {store.deleteBrushPreset(selected)};selected="";refresh()
        }}) {Text("删除预设")}
        TextButton(enabled=enabled,onClick={selected="";onChange(ArtBrush.defaults(tool))}) {Text("恢复默认")}
    }
    Text("笔尖与纹理",style=MaterialTheme.typography.titleSmall)
    val tip=brush.getJSONObject("tip");val texture=brush.getJSONObject("texture")
    BrushChoice("笔尖",tip.getString("shape"),linkedMapOf("round" to "圆形","ellipse" to "椭圆","square" to "方形"),enabled) {shape ->
        change {it.getJSONObject("tip").put("shape",shape).remove("asset")}
    }
    BrushSlider("笔尖宽高比",tip.getDouble("ratio"),0.05f..1f,enabled) {v ->change {it.getJSONObject("tip").put("ratio",v)}}
    BrushSlider("角度",tip.getDouble("angle"),-360f..360f,enabled) {v ->change {it.getJSONObject("tip").put("angle",v)}}
    BrushSlider("硬度",tip.getDouble("hardness"),0f..1f,enabled) {v ->change {it.getJSONObject("tip").put("hardness",v)}}
    BrushChoice("纹理",texture.getString("kind"),linkedMapOf("none" to "无","grain" to "纸粒","canvas" to "帆布","checker" to "棋盘"),enabled) {kind ->
        change {it.getJSONObject("texture").put("kind",kind).remove("asset")}
    }
    BrushSlider("纹理强度",texture.getDouble("strength"),0f..1f,enabled) {v ->change {it.getJSONObject("texture").put("strength",v)}}
    BrushSlider("纹理缩放",texture.getDouble("scale"),0.1f..16f,enabled) {v ->change {it.getJSONObject("texture").put("scale",v)}}
    BrushToggle("反转纹理",texture.getBoolean("invert"),enabled) {v ->change {it.getJSONObject("texture").put("invert",v)}}
    for(kind in listOf("tip","texture")) {
        val items=resources.filter {it.getString("kind")==kind};val part=brush.getJSONObject(kind)
        BrushChoice(if(kind=="tip")"图像笔尖" else "图像纹理",part.optString("asset"),items.associate {it.getString("asset") to it.getString("name")},enabled) {id ->
            change {it.getJSONObject(kind).put(if(kind=="tip")"shape" else "kind","image").put("asset",id)}
        }
        TextButton(enabled=enabled,onClick={importKind=kind;picker.launch("image/*")}) {Text(if(kind=="tip")"导入笔尖图像" else "导入纹理图像")}
    }
    Text("图像边长1–512像素。笔尖黑色着墨、白色透明；纹理白色保留、黑色减弱。",style=MaterialTheme.typography.bodySmall)
    Text("笔触引擎",style=MaterialTheme.typography.titleSmall)
    for((key,label,range) in listOf(Triple("spacing","间距（笔径倍数）",0.02f..2f),Triple("flow","流量",0f..1f),
        Triple("scatter","散布",0f..2f),Triple("jitter","角度抖动",0f..360f),Triple("airbrushRate","持续喷绘（次/秒，0关闭）",0f..120f)))
        BrushSlider(label,brush.getDouble(key),range,enabled) {v ->change {it.put(key,v)}}
    BrushSlider("每次印章数量",brush.getDouble("count"),1f..64f,enabled) {v ->change {it.put("count",kotlin.math.round(v).toInt())}}
    val smooth=brush.getJSONObject("smoothing")
    Text("轨迹处理",style=MaterialTheme.typography.titleSmall)
    BrushChoice("模式",smooth.getString("mode"),ArtBrush.modes,enabled) {v ->change {it.getJSONObject("smoothing").put("mode",v)}}
    if(smooth.getString("mode") in setOf("weighted","stabilizer")) {
        BrushSlider("加权窗口",smooth.getDouble("window"),2f..64f,enabled) {v ->change {it.getJSONObject("smoothing").put("window",kotlin.math.round(v).toInt())}}
        BrushSlider("平滑强度",smooth.getDouble("strength"),0f..1f,enabled) {v ->change {it.getJSONObject("smoothing").put("strength",v)}}
        BrushToggle("平滑压力",smooth.getBoolean("smoothPressure"),enabled) {v ->change {it.getJSONObject("smoothing").put("smoothPressure",v)}}
        BrushToggle("抬笔补至终点",smooth.getBoolean("finish"),enabled) {v ->change {it.getJSONObject("smoothing").put("finish",v)}}
    }
    if(smooth.getString("mode")=="stabilizer")BrushSlider("稳定器拖尾距离（像素）",smooth.getDouble("delay"),0f..128f,enabled) {v ->change {it.getJSONObject("smoothing").put("delay",v)}}
    if(smooth.getString("mode")=="pixel_perfect")Text("像素中心对齐、无抗锯齿、移除折角多余像素；1px方笔尖最适合像素线稿。",style=MaterialTheme.typography.bodySmall)
    Text("动态参数曲线",style=MaterialTheme.typography.titleSmall)
    Text("压力和倾斜取决于触笔；无倾斜输入时为0，方向角来自Android笔方向。",style=MaterialTheme.typography.bodySmall)
    for((channel,label) in ArtBrush.channels) {
        val rule=brush.getJSONObject("dynamics").getJSONObject(channel)
        BrushToggle(label,rule.getBoolean("enabled"),enabled) {v ->change {it.getJSONObject("dynamics").getJSONObject(channel).put("enabled",v)}}
        if(rule.getBoolean("enabled")) {
            BrushChoice("输入",rule.getString("sensor"),ArtBrush.sensors,enabled) {v ->change {it.getJSONObject("dynamics").getJSONObject(channel).put("sensor",v)}}
            BrushCurve(rule.getJSONArray("curve"),enabled) {curve ->change {it.getJSONObject("dynamics").getJSONObject(channel).put("curve",curve)}}
        }
    }
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
}

@Composable private fun BrushChoice(label:String,value:String,choices:Map<String,String>,enabled:Boolean,onChange:(String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {
        TextButton(enabled=enabled,onClick={open=true}) {Text(label+"："+(choices[value] ?: if(value.isBlank())"选择" else "图像/自定义"))}
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            choices.forEach {(id,name)->DropdownMenuItem(text={Text(name)},onClick={open=false;onChange(id)})}
        }
    }
}
@Composable private fun BrushSlider(label:String,value:Double,range:ClosedFloatingPointRange<Float>,enabled:Boolean,onChange:(Double)->Unit) {
    Text(label+"："+String.format(java.util.Locale.ROOT,"%.2f",value))
    Slider(value=value.toFloat(),onValueChange={onChange(it.toDouble())},valueRange=range,enabled=enabled)
}
@Composable private fun BrushToggle(label:String,value:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row {Checkbox(value,onChange,enabled=enabled);Text(label,modifier=Modifier.padding(top=12.dp))}
}
@Composable private fun BrushCurve(curve:JSONArray,enabled:Boolean,onChange:(JSONArray)->Unit) {
    var text by remember(curve.toString()) {mutableStateOf(curve.toString())};var error by remember {mutableStateOf("")}
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        drawLine(Color.Gray,Offset(0f,size.height),Offset(size.width,size.height));drawLine(Color.Gray,Offset.Zero,Offset(0f,size.height))
        for(i in 1 until curve.length()) {
            val a=curve.getJSONArray(i-1);val b=curve.getJSONArray(i)
            drawLine(color,Offset(a.getDouble(0).toFloat()*size.width,(1-a.getDouble(1).toFloat())*size.height),
                Offset(b.getDouble(0).toFloat()*size.width,(1-b.getDouble(1).toFloat())*size.height),3f)
        }
    }
    OutlinedTextField(text,{text=it},label={Text("曲线点 [[输入,输出],…]")},enabled=enabled,modifier=Modifier.fillMaxWidth())
    TextButton(enabled=enabled,onClick={try {val points=JSONArray(text);ArtBrush.validateCurve(points);onChange(points);error=""}
        catch(e:Exception) {error="须为2–16点，输入严格递增并含0和1，所有值0–1"}}) {Text("应用曲线")}
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
}
