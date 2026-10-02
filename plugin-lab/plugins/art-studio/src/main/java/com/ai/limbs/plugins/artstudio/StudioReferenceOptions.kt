package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StudioReferenceOptions(snapshot:JSONObject,busy:Boolean,multiple:Boolean,onMultiple:(Boolean)->Unit,
    onAdd:()->Unit,onFit:()->Unit,onEdit:(String,JSONObject)->Unit,onAction:(String,JSONObject)->Unit) {
    val state=snapshot.getJSONObject("state");val refs=ArtReferences.items(state)
    val ids=ArtReferences.ids(state);val selected=refs.filter { it.getString("id") in ids }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    fun params(p:JSONObject)=p.put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision"))
    fun style(key:String,value:Any) {
        onEdit("REFERENCE_STYLE",params(JSONObject().put("ids",JSONArray(ids))
            .put("style",JSONObject().put(key,value))))
    }
    Text("参考图像（独立于作品导出）",style=MaterialTheme.typography.labelSmall)
    TextButton(onClick=onAdd,enabled=!busy && refs.size<ArtReferences.MAX) { Text("添加参考图像") }
    val canAdd=!busy&&refs.size<ArtReferences.MAX
    var location by remember {mutableStateOf("")}
    var embedded by remember {mutableStateOf(false)}
    var keepLinks by remember {mutableStateOf(false)}
    fun action(name:String,p:JSONObject=JSONObject())=onAction(name,params(p))
    TextButton(onClick={action("paste_system")},enabled=canAdd) {Text("粘贴系统剪贴板图片")}
    TextButton(onClick={action("paste_studio")},enabled=canAdd) {Text("粘贴画室剪贴板图片")}
    TextButton(onClick={action("link_file")},enabled=canAdd) {Text("链接外部图片文件")}
    OutlinedTextField(location,{location=it},label={Text("HTTPS图片地址或绝对文件路径")},singleLine=true,enabled=canAdd)
    FilterChip(selected=embedded,onClick={embedded=!embedded},enabled=canAdd,label={Text("直接转为内嵌图片")})
    TextButton(onClick={action("link",JSONObject().put("location",location.trim()).put("embedded",embedded))},enabled=canAdd&&location.isNotBlank()) {Text("导入图片链接")}
    TextButton(onClick={action("capture",JSONObject().put("source","layer"))},enabled=canAdd) {Text("从当前层生成参考")}
    TextButton(onClick={action("capture",JSONObject().put("source","visible"))},enabled=canAdd) {Text("从可见画布生成参考")}
    FilterChip(selected=keepLinks,onClick={keepLinks=!keepLinks},enabled=!busy,label={Text("集合保留外部来源链接")})
    TextButton(onClick={action("collection_import",JSONObject().put("keepLinks",keepLinks))},enabled=canAdd) {Text("导入.ailrefs参考集合")}
    TextButton(onClick={val p=JSONObject().put("keepLinks",keepLinks);if(ids.isNotEmpty())p.put("ids",JSONArray(ids));action("collection_export",p)},enabled=!busy&&refs.isNotEmpty()) {
        Text(if(ids.isEmpty())"导出全部参考集合" else "导出选中参考集合")}
    Text("集合保存图片、排列与样式，默认转为内嵌的便携副本；不兼容Krita.krf。",style=MaterialTheme.typography.labelSmall)
    TextButton(onClick=onFit,enabled=!busy) { Text("画布与参考一起入镜") }
    FilterChip(selected=state.optBoolean("referencesVisible",true),onClick={
        onEdit("REFERENCE_SHOW",params(JSONObject().put("visible",!state.optBoolean("referencesVisible",true))))
    },enabled=!busy,label={Text("显示所有参考图像")})
    FilterChip(selected=multiple,onClick={onMultiple(!multiple)},enabled=!busy,label={Text("多选")})
    for(r in refs) {
        val id=r.getString("id")
        FilterChip(selected=id in ids,onClick={
            val next=if(multiple) { if(id in ids) ids-id else ids+id } else listOf(id)
            onEdit("REFERENCE_SELECT",params(JSONObject().put("ids",JSONArray(next))))
        },enabled=!busy,label={Text(r.getString("name")+(if(r.getBoolean("locked")) " 🔒" else ""))})
    }
    if(selected.isNotEmpty()) {
        val first=selected.first();val locked=selected.any { it.getBoolean("locked") }
        if(selected.size==1&&first.has("externalSource")) {
            Text("外部来源："+first.getString("externalSource"),style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={action("refresh",JSONObject().put("id",first.getString("id")))},enabled=!busy&&!locked) {Text("从来源刷新参考")}
        }
        if(selected.any {it.has("externalSource")})TextButton(onClick={action("embed",JSONObject().put("ids",JSONArray(ids)))},enabled=!busy&&!locked) {Text("选中参考转为内嵌")}
        Text("链接保存当前图片快照；只有点击刷新才重新读取，失败时不改图片。",style=MaterialTheme.typography.labelSmall)
        var opacity by remember(snapshot.getString("id"),snapshot.getInt("revision"),ids) {
            mutableFloatStateOf(first.getDouble("opacity").toFloat()) }
        var saturation by remember(snapshot.getString("id"),snapshot.getInt("revision"),ids) {
            mutableFloatStateOf(first.getDouble("saturation").toFloat()) }
        Text("不透明度 "+(opacity*100).toInt()+"%",style=MaterialTheme.typography.labelSmall)
        Slider(value=opacity,onValueChange={opacity=it},onValueChangeFinished={style("opacity",opacity.toDouble())},
            enabled=!busy&&!locked,modifier=Modifier.fillMaxWidth())
        Text("饱和度 "+(saturation*100).toInt()+"%",style=MaterialTheme.typography.labelSmall)
        Slider(value=saturation,onValueChange={saturation=it},onValueChangeFinished={style("saturation",saturation.toDouble())},
            enabled=!busy&&!locked,modifier=Modifier.fillMaxWidth())
        for((key,label) in listOf("keepAspect" to "保持比例","visible" to "显示选中参考","locked" to "锁定")) {
            val value=selected.all { it.getBoolean(key) }
            FilterChip(selected=value,onClick={style(key,!value)},enabled=!busy && (!locked||key=="locked"),
                label={Text(label)})
        }
        for((degrees,label) in listOf(90f to "顺时针90°",-90f to "逆时针90°")) {
            TextButton(onClick={
                val layer=ArtShapes.layer(ArtReferences.selectionState(state),ArtReferences.LAYER)
                val bounds=ArtShapes.bounds(layer,ids) ?: return@TextButton
                val matrix=android.graphics.Matrix().apply { setRotate(degrees,bounds.centerX(),bounds.centerY()) }
                onEdit("REFERENCE_TRANSFORM",params(JSONObject().put("ids",JSONArray(ids)).put("matrix",ArtShapes.encode(matrix))))
            },enabled=!busy&&!locked && selected.all { it.getBoolean("visible") }) { Text(label) }
        }
        TextButton(onClick={deleting=params(JSONObject().put("ids",JSONArray(ids)))},enabled=!busy&&!locked) {
            Text("删除选中的参考") }
    }
    Text("拖动图片移动；拖边框缩放，拖圆柄旋转。空白处拖框选择；Shift多选。参考始终跟随画布视图，可放在画布外。",
        style=MaterialTheme.typography.labelSmall)
    deleting?.let { request ->
        AlertDialog(onDismissRequest={deleting=null},title={Text("删除参考图像？")},
            text={Text("仅删除工程里的参考对象；原照片不会删除。可用撤销恢复。")},
            confirmButton={TextButton(onClick={deleting=null;onEdit("REFERENCE_DELETE",request)},enabled=!busy) { Text("删除") }},
            dismissButton={TextButton(onClick={deleting=null}) { Text("取消") }})
    }
}
