package com.ai.limbs.plugins.artstudio

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

internal fun transformControls(p:JSONObject,mode:String,grid:Int=3):JSONObject {
    val next=JSONObject(p.toString()).put("mode",mode)
    for(k in listOf("points","sourcePoints","dabs","columns","rows"))next.remove(k)
    val r=next.getJSONObject("sourceBounds");val x=r.getDouble("x");val y=r.getDouble("y");val w=r.getDouble("width");val h=r.getDouble("height")
    val corners=JSONArray(listOf(listOf(x,y),listOf(x+w,y),listOf(x+w,y+h),listOf(x,y+h)))
    when(mode) {
        "perspective","distort","cage" -> next.put("points",JSONArray(corners.toString()))
        "warp" -> next.put("points",JSONArray(corners.toString()).put(JSONArray(listOf(x+w/2,y+h/2))))
        "mesh" -> {require(grid in 2..9);next.put("columns",grid).put("rows",grid).put("points",JSONArray((0 until grid).flatMap {j->(0 until grid).map {i->listOf(x+w*i/(grid-1),y+h*j/(grid-1))}}))}
        "liquify" -> next.put("dabs",JSONArray())
    }
    if(mode=="warp"||mode=="cage")next.put("sourcePoints",JSONArray(next.getJSONArray("points").toString()))
    return next
}

@Composable internal fun StudioTransformOptions(request:JSONObject,busy:Boolean,onDraft:(JSONObject)->Unit,onApply:()->Unit,onCancel:()->Unit) {
    val mode=request.getString("mode");val scope=request.getString("scope")
    val labels=mapOf("affine" to "自由变换","perspective" to "透视","distort" to "四角扭曲","warp" to "控制点扭曲","cage" to "笼形","liquify" to "液化","mesh" to "网格")
    for(m in ArtTransform.modes)FilterChip(mode==m,{onDraft(transformControls(request,m))},enabled=!busy,label={Text(labels.getValue(m))})
    FilterChip(scope=="layer",{onDraft(JSONObject(request.toString()).put("scope","layer").put("bake",false))},enabled=!busy,label={Text("整层")})
    FilterChip(scope=="selection",{onDraft(JSONObject(request.toString()).put("scope","selection"))},enabled=!busy,label={Text("当前选区像素")})
    for(filter in ArtTransform.filters)FilterChip(request.optString("interpolation","bilinear")==filter,{onDraft(JSONObject(request.toString()).put("interpolation",filter))},enabled=!busy,label={Text(filter)})
    val keys=if(mode=="affine")listOf("dx","dy","scaleX","scaleY","shearX","shearY","rotation","pivotX","pivotY") else if(mode=="liquify")listOf("radius","strength","twirlAngle","gridResolution") else listOf("gridResolution")
    val captions=mapOf("dx" to "水平位移 px","dy" to "垂直位移 px","scaleX" to "水平缩放","scaleY" to "垂直缩放","shearX" to "水平剪切系数","shearY" to "垂直剪切系数","rotation" to "旋转 °","pivotX" to "枢轴 X","pivotY" to "枢轴 Y","radius" to "液化半径 px","strength" to "液化强度 0–1","twirlAngle" to "旋转液化角度 °","gridResolution" to "网格精度 2–64")
    var fields by remember(request.toString()) {mutableStateOf((keys.map {k->k to request.optDouble(k,when(k){"scaleX","scaleY"->1.0;"radius"->64.0;"strength"->0.2;"twirlAngle"->30.0;"gridResolution"->16.0;else->0.0}).toString()}+
        listOf("x","y","width","height").map {k->"source.$k" to request.getJSONObject("sourceBounds").getDouble(k).toString()}).toMap())}
    var error by remember {mutableStateOf("")}
    for(k in keys)OutlinedTextField(fields.getValue(k),{fields=fields+(k to it)},enabled=!busy,label={Text(captions.getValue(k))})
    if(scope=="layer" && mode!="affine") {
        Text("整层将栅格化为 8 位绘画层；组会合并。取样框外像素丢弃，撤销可恢复原图层。")
        for(k in listOf("x","y","width","height"))OutlinedTextField(fields.getValue("source.$k"),{fields=fields+("source.$k" to it)},enabled=!busy,label={Text("取样框 $k px")})
        FilterChip(request.optBoolean("bake",false),{onDraft(JSONObject(request.toString()).put("bake",!request.optBoolean("bake",false)))},enabled=!busy,label={Text("确认栅格化及取样范围")})
    }
    TextButton(enabled=!busy,onClick={
        try {
            val next=JSONObject(request.toString());for(k in keys){val n=requireNotNull(fields.getValue(k).toDoubleOrNull());require(n.isFinite());if(k=="gridResolution"){require(n%1==0.0);next.put(k,n.toInt())}else next.put(k,n)}
            if(scope=="layer"&&mode!="affine") {val box=next.getJSONObject("sourceBounds");for(k in listOf("x","y","width","height"))box.put(k,requireNotNull(fields.getValue("source.$k").toDoubleOrNull()));ArtMove.bounds(JSONObject(box.toString()).put("shape","rect"))}
            onDraft(next);error=""
        } catch(e:Exception){android.util.Log.e("ArtStudio","Transform parameters rejected",e);error="参数无效：${e.message}"}
    }){Text("更新参数草稿")}
    if(mode=="mesh")for(size in listOf(2,3,5))TextButton(enabled=!busy,onClick={onDraft(transformControls(request,mode,size))}){Text("重建 $size × $size 网格")}
    if(mode in setOf("warp","cage","distort","perspective"))TextButton(enabled=!busy,onClick={onDraft(transformControls(request,mode))}){Text("按当前取样框重建控制点")}
    if(mode=="liquify") {
        for(kind in listOf("push","expand","contract","twirl"))FilterChip(request.optString("liquifyKind","push")==kind,{onDraft(JSONObject(request.toString()).put("liquifyKind",kind))},enabled=!busy,label={Text(kind)})
        Text("在画布拖动添加液化作用；${request.optJSONArray("dabs")?.length() ?: 0} 个作用点。")
        TextButton(enabled=!busy,onClick={onDraft(JSONObject(request.toString()).put("dabs",JSONArray()))}){Text("清空液化草稿")}
    }
    var json by remember(request.toString()) {mutableStateOf(request.toString(1))}
    OutlinedTextField(json,{json=it},enabled=!busy,label={Text("高级控制点 / 参数 JSON")},maxLines=6)
    TextButton(enabled=!busy,onClick={try {val next=JSONObject(json);require(next.getString("documentId")==request.getString("documentId")&&next.getInt("expectedRevision")==request.getInt("expectedRevision")&&next.getString("layerId")==request.getString("layerId"));require(next.getString("mode") in ArtTransform.modes && next.getString("scope") in setOf("selection","layer"));ArtMove.bounds(JSONObject(next.getJSONObject("sourceBounds").toString()).put("shape","rect"));if(next.getString("mode")!="liquify")ArtTransform.plan(ArtMove.bounds(JSONObject(next.getJSONObject("sourceBounds").toString()).put("shape","rect")),next);onDraft(next);error=""}catch(e:Exception){android.util.Log.e("ArtStudio","Transform draft JSON rejected",e);error=e.message ?: "JSON 无效"}}){Text("载入参数草稿")}
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    Text("拖动控制点调整形状；自由变换可拖动平移。所有操作在确认前只修改草稿。网格为双线性细分，笼形限定凸多边形。",style=MaterialTheme.typography.bodySmall)
    TextButton(enabled=!busy && (scope=="selection"||mode=="affine"||request.optBoolean("bake",false)),onClick=onApply){Text("确认变换")}
    TextButton(enabled=!busy,onClick=onCancel){Text("取消 / 重置草稿")}
}
