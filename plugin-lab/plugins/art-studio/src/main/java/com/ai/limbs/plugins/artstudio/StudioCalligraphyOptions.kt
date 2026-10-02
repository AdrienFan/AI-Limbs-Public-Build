package com.ai.limbs.plugins.artstudio

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

@Composable
internal fun StudioCalligraphyOptions(store:ArtStore,snapshot:JSONObject,selected:String,busy:Boolean,
    options:JSONObject,width:Float,color:String,opacity:Float,onChange:(JSONObject)->Unit,
    onStyle:(Float,String,Float)->Unit,onEdit:(String,JSONObject)->Unit) {
    val state=snapshot.getJSONObject("state")
    val layer=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==selected}
    val scope=rememberCoroutineScope()
    var profiles by remember {mutableStateOf(emptyList<JSONObject>())}
    var profileId by remember {mutableStateOf("")};var name by remember {mutableStateOf("")}
    var working by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")}
    val enabled=!busy&&!working;val follow=options.getBoolean("followPath")
    fun change(key:String,value:Any) {val next=JSONObject(options.toString());next.put(key,value);onChange(next)}
    suspend fun refresh() {
        val list=withContext(Dispatchers.IO) {store.calligraphyProfiles().getJSONArray("profiles")}
        profiles=(0 until list.length()).map {list.getJSONObject(it)}
    }
    fun run(action:suspend ()->Unit) {scope.launch {working=true;error=""
        try {action()} catch(e:Exception) {Log.e("ArtStudio","Calligraphy profile operation failed",e);error=e.message.orEmpty()} finally {working=false}
    }}
    LaunchedEffect(Unit) {working=true
        try {refresh()} catch(e:Exception) {Log.e("ArtStudio","Calligraphy profiles could not load",e);error=e.message.orEmpty()} finally {working=false}
    }
    @Composable fun option(label:String,key:String,range:ClosedFloatingPointRange<Float>,available:Boolean=true) {
        val value=options.getDouble(key).toFloat()
        Text(label+" "+String.format(Locale.ROOT,"%.2f",value),style=MaterialTheme.typography.labelSmall)
        Slider(value=value,onValueChange={change(key,it.toDouble())},enabled=enabled&&available,valueRange=range,
            modifier=Modifier.fillMaxWidth().semantics {contentDescription=label})
    }
    Text("矢量书法笔",style=MaterialTheme.typography.labelSmall)
    if(layer?.getString("kind")!="vector") {
        TextButton(onClick={onEdit("VECTOR_LAYER_CREATE",JSONObject().put("id",UUID.randomUUID().toString())
            .put("name","矢量图层").put("select",true).put("parentId",if(layer?.getString("kind")=="group")selected else layer?.optString("parentId").orEmpty()))},enabled=enabled) {Text("新建矢量层")}
    }
    option("笔尖角度","angle",0f..180f)
    option("固定度","fixation",0f..1f)
    Text("1固定笔尖方向；0随轨迹转向",style=MaterialTheme.typography.labelSmall)
    option("速度变细","thinning",-1f..1f)
    option("轨迹平滑","smoothing",0f..1f,!follow)
    FilterChip(selected=options.getBoolean("usePressure"),onClick={change("usePressure",!options.getBoolean("usePressure"))},enabled=enabled,label={Text("笔压控制宽度")})
    FilterChip(selected=options.getBoolean("useTilt"),onClick={change("useTilt",!options.getBoolean("useTilt"))},enabled=enabled,label={Text("数位笔倾斜方向")})
    Text("倾斜模式需要设备提供倾斜和方位轴；笔直立时保持本笔最近方向。",style=MaterialTheme.typography.labelSmall)
    for((id,label) in listOf("flat" to "平头","round" to "圆头"))FilterChip(selected=options.getString("cap")==id,onClick={change("cap",id)},enabled=enabled,label={Text(label)})
    option("Mass 质量","mass",0f..20f,!follow)
    option("Drag 阻力","drag",0f..1f,!follow)
    Text("Mass越大越迟缓；Drag越大抑制惯性。0／1保持直接输入；收笔保留惯性终点。",style=MaterialTheme.typography.labelSmall)
    FilterChip(selected=follow,onClick={change("followPath",!follow)},enabled=enabled,label={Text("跟随路径")})
    if(follow&&layer?.getString("kind")=="vector") {
        val paths=ArtShapes.items(layer).filter {it.getString("kind")=="path"&&it.getBoolean("visible")&&it.getDouble("opacity")>0}
        var expanded by remember(selected) {mutableStateOf(false)}
        val currentId=options.optString("followPathId")
        TextButton(onClick={expanded=!expanded},enabled=enabled) {Text(if(currentId.isBlank())"选择跟随路径" else "跟随："+currentId.take(8))}
        if(expanded)paths.forEach {path->TextButton(enabled=enabled,onClick={
            val next=JSONObject(options.toString()).put("followPathId",path.getString("id")).put("followSubpath",0)
            onChange(next);expanded=false
        }) {Text("路径 "+path.getString("id").take(8))}}
        val currentPath=paths.firstOrNull {it.getString("id")==currentId}
        if(currentPath!=null)ArtPathTopology.parts(currentPath).indices.forEach {index->FilterChip(selected=options.optInt("followSubpath",0)==index,
            onClick={change("followSubpath",index)},enabled=enabled,label={Text("子路径 "+index)})}
        val selectedIds=ArtShapes.selected(state,selected)
        TextButton(enabled=enabled&&selectedIds.size==1&&paths.any {it.getString("id")==selectedIds.single()},onClick={
            onChange(JSONObject(options.toString()).put("followPathId",selectedIds.single()).put("followSubpath",0))
        }) {Text("使用选中路径")}
        FilterChip(selected=options.getBoolean("followReverse"),onClick={change("followReverse",!options.getBoolean("followReverse"))},enabled=enabled,label={Text("反向书写")})
        Text("从子路径起点前进，反向从终点开始；拖动距离控制进度，原路径保留。跟随时平滑与Mass／Drag不参与。",style=MaterialTheme.typography.labelSmall)
        if(currentPath==null)Text("请先选择当前图层中的可见路径。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
    }
    Text("宽度、颜色和透明度使用上方笔刷参数；画后可编辑轮廓节点。",style=MaterialTheme.typography.labelSmall)
    Text("书法配置档",style=MaterialTheme.typography.titleSmall)
    var showProfiles by remember {mutableStateOf(false)}
    TextButton(enabled=enabled,onClick={showProfiles=!showProfiles}) {Text("选择配置档（"+profiles.size+"）")}
    if(showProfiles)profiles.forEach {profile->TextButton(enabled=enabled,onClick={
        val loaded=JSONObject(profile.getJSONObject("settings").toString())
        for(key in listOf("followPathId","followSubpath"))if(options.has(key))loaded.put(key,options.get(key))
        profileId=profile.getString("id");name=profile.getString("name");onChange(loaded)
        onStyle(loaded.getDouble("width").toFloat(),loaded.getString("color"),loaded.getDouble("opacity").toFloat());showProfiles=false
    }) {Text(profile.getString("name"))}}
    OutlinedTextField(name,{name=it},label={Text("配置档名称")},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth())
    fun save(update:Boolean) {
        val settings=ArtCalligraphy.settings(JSONObject(options.toString()).put("width",width.toDouble()).put("color",color).put("opacity",opacity.toDouble()))
        val request=JSONObject().put("name",name).put("settings",settings)
        if(update)request.put("id",profileId)
        run {val saved=withContext(Dispatchers.IO) {store.saveCalligraphyProfile(request)}
            profileId=saved.getString("id");name=saved.getString("name");refresh()}
    }
    Row {
        TextButton(enabled=enabled&&name.trim().length in 1..64,onClick={save(false)}) {Text("另存")}
        TextButton(enabled=enabled&&profileId.isNotBlank()&&name.trim().length in 1..64,onClick={save(true)}) {Text("更新选中")}
        TextButton(enabled=enabled&&profileId.isNotBlank(),onClick={val id=profileId;run {withContext(Dispatchers.IO) {store.deleteCalligraphyProfile(id)};profileId="";refresh()}}) {Text("删除")}
    }
    Text("配置档保存完整笔刷参数；工程路径与子路径引用不随档保存。删除不影响已画轮廓。",style=MaterialTheme.typography.labelSmall)
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
}
