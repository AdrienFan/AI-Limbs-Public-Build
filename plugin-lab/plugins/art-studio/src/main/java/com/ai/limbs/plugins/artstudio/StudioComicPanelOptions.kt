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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.util.Log

@Composable
internal fun StudioComicPanelOptions(store:ArtStore,snapshot: JSONObject,layerId: String,busy: Boolean,settings: JSONObject,
    onSettings: (JSONObject)->Unit,onLayer: (JSONObject)->Unit,onFrame: (JSONObject)->Unit,onStyle: (JSONObject)->Unit) {
    val state=snapshot.getJSONObject("state")
    val layer=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==layerId}
    val ready=layer?.getString("kind")=="vector" && ArtShapes.visible(state,layer) && !ArtMenuOperations.isLocked(state,layer)
    val selected=if(layer?.getString("kind")=="vector")ArtShapes.selected(state,layerId) else emptyList()
    var margin by remember {mutableStateOf(24f)}
    var borderWidth by remember {mutableStateOf(2f)}
    var border by remember {mutableStateOf("#FF161616")}
    fun set(key: String,value: Any) {onSettings(JSONObject(settings.toString()).put(key,value))}
    fun base()=JSONObject().put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision")).put("layerId",layerId)
    val scope=rememberCoroutineScope()
    var profiles by remember {mutableStateOf(JSONObject().put("revision",0).put("profiles",org.json.JSONArray()))}
    var profileName by remember {mutableStateOf("")};var working by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")}
    fun profileOperation(request:JSONObject) {scope.launch {working=true;error=""
        try {profiles=withContext(Dispatchers.IO) {store.comicProfiles(request)}}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {Log.e("ArtStudio","Comic profile failed",e);error=e.message.orEmpty()} finally {working=false}
    }}
    LaunchedEffect(Unit) {
        try {profiles=withContext(Dispatchers.IO) {store.comicProfiles(JSONObject().put("action","list"))}}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {Log.e("ArtStudio","Comic profiles could not load",e);error=e.message.orEmpty()}
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("px","mm","cm","in","pt").forEach {unit->FilterChip(selected=settings.getString("unit")==unit,enabled=!busy,
            onClick={val next=JSONObject(settings.toString())
                ArtComicPanels.presets.keys.forEach {key->next.put(key,ArtMove.fromPixels(ArtMove.toPixels(settings.getDouble(key),settings.getString("unit"),settings.getDouble("ppi")),unit,settings.getDouble("ppi")))}
                onSettings(next.put("unit",unit))},label={Text(unit)})}
    }
    Text("单位换算PPI："+settings.getDouble("ppi").roundToInt())
    Slider(settings.getDouble("ppi").toFloat(),{value->
        val next=JSONObject(settings.toString()).put("ppi",value.toDouble())
        ArtComicPanels.presets.keys.forEach {key->next.put(key,ArtMove.fromPixels(ArtMove.toPixels(settings.getDouble(key),settings.getString("unit"),settings.getDouble("ppi")),settings.getString("unit"),value.toDouble()))}
        onSettings(next)
    },enabled=!busy,valueRange=1f..2400f)
    OutlinedTextField(profileName,{profileName=it.take(64)},enabled=!busy&&!working,label={Text("分格预设名称")})
    TextButton(enabled=!busy&&!working&&profileName.isNotBlank(),onClick={
        profileOperation(JSONObject().put("action","save").put("expectedSettingsRevision",profiles.getLong("revision")).put("name",profileName).put("settings",settings))
    }) {Text("保存宽度预设")}
    val savedProfiles=profiles.getJSONArray("profiles")
    for(i in 0 until savedProfiles.length()) {
        val item=savedProfiles.getJSONObject(i)
        Row {
            TextButton(enabled=!busy&&!working,onClick={onSettings(ArtComicPanels.options(item.getJSONObject("settings")))}) {Text("载入："+item.getString("name"))}
            TextButton(enabled=!busy&&!working,onClick={profileOperation(JSONObject().put("action","delete").put("expectedSettingsRevision",profiles.getLong("revision")).put("id",item.getString("id")))}) {Text("删除")}
        }
    }
    if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    Text("先创建矢量层和分格框。切分时从框外拖到框外；合并时从一格内部拖到邻格内部，每次只跨一条间隙。")
    TextButton(enabled=!busy,onClick={onLayer(base().put("name","漫画分格").put("select",true))}) {Text("新建分格矢量层")}
    val maxMargin=(minOf(state.getInt("width"),state.getInt("height"))/4f)
    val actualMargin=margin.coerceAtMost(maxMargin)
    Text("整页分格框边距：${actualMargin.roundToInt()} px")
    Slider(actualMargin,{margin=it},enabled=!busy,valueRange=0f..maxMargin)
    val validColor=border.matches(Regex("#[A-Fa-f0-9]{8}"))
    OutlinedTextField(border,{border=it.uppercase().take(9)},enabled=!busy,label={Text("边框颜色 #AARRGGBB")},isError=!validColor)
    Text("边框粗细：${borderWidth.roundToInt()} px（对象局部，随图层或对象缩放）")
    Slider(borderWidth,{borderWidth=it},enabled=!busy,valueRange=1f..32f)
    TextButton(enabled=!busy && ready && validColor,onClick={
        onFrame(base().put("x",actualMargin.toDouble()).put("y",actualMargin.toDouble())
            .put("width",state.getInt("width")-actualMargin*2.0).put("height",state.getInt("height")-actualMargin*2.0)
            .put("style",JSONObject().put("stroke",border).put("strokeWidth",borderWidth.toDouble())))
    }) {Text("添加整页分格框")}
    TextButton(enabled=!busy && ready && selected.isNotEmpty() && validColor,onClick={
        onStyle(base().put("ids",org.json.JSONArray(selected))
            .put("style",JSONObject().put("stroke",border).put("strokeWidth",borderWidth.toDouble())))
    }) {Text("应用边框到已选分格")}
    if(!ready)Text("当前需要可见且未锁定的矢量图层；可先新建分格矢量层。",color=Color.Gray)
    Row {
        listOf("cut" to "切分","merge" to "合并").forEach {(id,label)->
            FilterChip(selected=settings.getString("mode")==id,enabled=!busy,onClick={set("mode",id)},label={Text(label)})}
    }
    FilterChip(selected=settings.getBoolean("selectedOnly"),enabled=!busy,
        onClick={set("selectedOnly",!settings.getBoolean("selectedOnly"))},label={Text("只处理已选对象")})
    Text("切分与合并会选中新产生的分格。形状选择工具可移动、缩放、旋转及修改样式；分格本身作为矢量边框导出，不分割底下的画作像素。")
    if(settings.getString("mode")=="cut") {
        FilterChip(selected=settings.getBoolean("automatic"),enabled=!busy,
            onClick={set("automatic",!settings.getBoolean("automatic"))},label={Text("按切线角度自动选间隙")})
        ArtComicPanels.presets.forEach {(id,label)->
            Text(label+"："+String.format("%.2f",settings.getDouble(id))+" "+settings.getString("unit"))
            Slider(settings.getDouble(id).toFloat(),{set(id,it.toDouble())},enabled=!busy,valueRange=0f..ArtMove.fromPixels(512.0,settings.getString("unit"),settings.getDouble("ppi")).toFloat())
        }
        if(settings.getBoolean("automatic")) {
            for((key,label) in listOf("horizontal" to "横向","vertical" to "竖向","diagonal" to "斜向")) {
                // Braces keep the following Chinese text out of the Kotlin identifier.
                Text("${label}使用")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ArtComicPanels.presets.forEach {(id,name)->FilterChip(selected=settings.getString(key)==id,
                        enabled=!busy,onClick={set(key,id)},label={Text(name)})}
                }
            }
            Text("横竖方向判定角度：${settings.getDouble("angle").roundToInt()}°")
            Slider(settings.getDouble("angle").toFloat(),{set("angle",it.toDouble())},enabled=!busy,valueRange=0f..45f)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ArtComicPanels.presets.forEach {(id,label)->FilterChip(selected=settings.getString("preset")==id,
                    enabled=!busy,onClick={set("preset",id)},label={Text(label)})}
            }
        }
        Text("间隙为轮廓之间的文档像素宽度；边框描边会占去一部分可见空白。0像素切分会保留两格相接的边框。")
    } else Text("支持相对直边、非平行边以及同一对象孔洞两侧合并；只连接两边共同投影区间，间隙最多512px。桥接区域与其他轮廓重叠时拒绝。继承图层中较靠下对象的样式。")
    Text("支持闭合曲线、凹轮廓、孔洞与多子路径。切线须穿过所有相交轮廓；结果仍为可编辑矢量路径，曲线按0.25文档像素误差转为折线，不保留原控制柄。每个结果最多2048段、64轮廓。Esc、右键和双指取消拖线。")
    ArtComicPanels.pending.forEach {Text("$it（待实现）",color=Color.Gray)}
}
