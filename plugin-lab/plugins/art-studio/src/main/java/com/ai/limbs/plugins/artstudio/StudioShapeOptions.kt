package com.ai.limbs.plugins.artstudio

import android.graphics.Matrix
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun StudioShapeOptions(snapshot:JSONObject,selectedLayer:String,busy:Boolean,
    color:String,width:Float,multiple:Boolean,onMultiple:(Boolean)->Unit,
    onEdit:(String,JSONObject)->Unit,onPathEdit:()->Unit) {
    val state=snapshot.getJSONObject("state")
    val layer=ArtMenuOperations.layers(state).firstOrNull { it.getString("id")==selectedLayer }
    var captured by remember { mutableStateOf<JSONObject?>(null) }
    var dx by remember { mutableStateOf("0") };var dy by remember { mutableStateOf("0") }
    var sx by remember { mutableStateOf("1") };var sy by remember { mutableStateOf("1") }
    var angle by remember { mutableStateOf("0") }
    if(layer?.getString("kind")!="vector") {
        Text("请使用矢量图层。",style=MaterialTheme.typography.labelSmall)
        TextButton(onClick={
            onEdit("VECTOR_LAYER_CREATE",JSONObject().put("id",UUID.randomUUID().toString())
                .put("name","矢量图层").put("select",true).put("parentId",
                    if(layer?.getString("kind")=="group") selectedLayer else layer?.optString("parentId").orEmpty()))
        },enabled=!busy) { Text("新建矢量层") }
        return
    }
    val ids=ArtShapes.selected(state,selectedLayer)
    val visible=ArtShapes.visible(state,layer)
    val canEdit=ids.isNotEmpty()&&visible&&!ArtMenuOperations.isLocked(state,layer)&&
        ArtShapes.items(layer).filter { it.getString("id") in ids }.none { it.getBoolean("locked") }
    val canFill = ArtShapes.items(layer).any { it.getString("id") in ids && ArtShapes.canFill(it) }
    fun parameters(selected:List<String> = ids):JSONObject = JSONObject()
        .put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision"))
        .put("layerId",selectedLayer).put("ids",JSONArray(selected))
    fun style(value:JSONObject) { onEdit("SHAPE_STYLE",parameters().put("style",value)) }
    Text("已选 "+ids.size+" 个形状",style=MaterialTheme.typography.labelSmall)
    FilterChip(selected=multiple,onClick={onMultiple(!multiple)},enabled=!busy,
        label={Text("多选")})
    Text("点选或拖框；方块缩放，圆点旋转。",style=MaterialTheme.typography.labelSmall)
    if(!canEdit&&ids.isNotEmpty()) Text("锁定或隐藏的对象不能修改。",style=MaterialTheme.typography.labelSmall)
    TextButton(onClick={onEdit("SHAPE_SELECT",parameters(ArtShapes.items(layer)
        .filter { it.getBoolean("visible") }.map { it.getString("id") }))},enabled=!busy&&visible) { Text("全选形状") }
    TextButton(onClick={onEdit("SHAPE_SELECT",parameters(emptyList()))},enabled=!busy&&visible) { Text("取消选择") }
    val singlePath=ids.size==1&&ArtShapes.items(layer).any { it.getString("id")==ids[0]&&it.getString("kind")=="path" }
    TextButton(onClick=onPathEdit,enabled=!busy&&visible&&singlePath) { Text("编辑路径节点") }
    TextButton(onClick={
        val p=parameters()
        val bounds=ArtShapes.bounds(layer,ids)!!
        p.put("pivotX",bounds.centerX().toDouble()).put("pivotY",bounds.centerY().toDouble())
        captured=p;dx="0";dy="0";sx="1";sy="1";angle="0"
    },enabled=!busy&&canEdit) { Text("数值变换…") }
    TextButton(onClick={style(JSONObject().put("fill",color))},enabled=!busy&&canEdit&&canFill) { Text("填充前景色") }
    TextButton(onClick={style(JSONObject().put("fill","#00000000"))},enabled=!busy&&canEdit&&canFill) { Text("取消填充") }
    TextButton(onClick={style(JSONObject().put("stroke",color).put("strokeWidth",width.toDouble()))},
        enabled=!busy&&canEdit) { Text("应用描边") }
    val rectangles=ArtShapes.items(layer).filter {it.getString("id") in ids}
    if(rectangles.isNotEmpty()&&rectangles.all {it.getString("kind")=="rectangle"}) {
        var corner by remember(selectedLayer,ids) {mutableStateOf(rectangles.first().optDouble("cornerRadius",0.0).toString())}
        val value=corner.toDoubleOrNull()
        OutlinedTextField(corner,{corner=it},label={Text("矩形圆角半径")},enabled=!busy&&canEdit,singleLine=true)
        TextButton(enabled=!busy&&canEdit&&value!=null&&value.isFinite()&&value in 0.0..16384.0,
            onClick={style(JSONObject().put("cornerRadius",requireNotNull(value)))}) {Text("应用圆角")}
    }
    val objects=ArtShapes.items(layer).filter {it.getString("id") in ids}
    TextButton(enabled=!busy&&canEdit&&objects.any {it.getString("kind")!="path"},onClick={onEdit("SHAPE_PATH_CONVERT",parameters())}) {Text("形状转路径")}
    TextButton(enabled=!busy&&canEdit&&objects.size>=2&&objects.all {it.getString("kind")=="path"},onClick={onEdit("SHAPE_PATH_COMBINE",parameters())}) {Text("合成子路径对象")}
    StudioObjectStyleOptions(objects,busy||!canEdit) {patch->style(JSONObject().put("objectStyle",patch))}
    TextButton(onClick={onEdit("SHAPE_DELETE",parameters())},enabled=!busy&&canEdit) { Text("删除形状") }
    captured?.let { original ->
        val x=dx.toFloatOrNull();val y=dy.toFloatOrNull()
        val scaleX=sx.toFloatOrNull();val scaleY=sy.toFloatOrNull();val rotation=angle.toFloatOrNull()
        val valid=x!=null&&y!=null&&scaleX!=null&&scaleY!=null&&rotation!=null&&
            x.isFinite()&&y.isFinite()&&rotation.isFinite()&&
            kotlin.math.abs(x)<=1000000f&&kotlin.math.abs(y)<=1000000f&&
            scaleX in 0.01f..100f&&scaleY in 0.01f..100f
        AlertDialog(onDismissRequest={if(!busy) captured=null},title={Text("形状变换")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("相对位移与缩放；角度围绕所选形状中心。")
                OutlinedTextField(dx,{dx=it},label={Text("横向位移")},enabled=!busy)
                OutlinedTextField(dy,{dy=it},label={Text("纵向位移")},enabled=!busy)
                OutlinedTextField(sx,{sx=it},label={Text("横向缩放倍数")},enabled=!busy)
                OutlinedTextField(sy,{sy=it},label={Text("纵向缩放倍数")},enabled=!busy)
                OutlinedTextField(angle,{angle=it},label={Text("旋转角度")},enabled=!busy)
            }},
            confirmButton={TextButton(enabled=valid&&!busy,onClick={
                val cx=original.getDouble("pivotX").toFloat();val cy=original.getDouble("pivotY").toFloat()
                val matrix=Matrix().apply {
                    setScale(requireNotNull(scaleX),requireNotNull(scaleY),cx,cy)
                    postRotate(requireNotNull(rotation),cx,cy)
                    postTranslate(requireNotNull(x),requireNotNull(y))
                }
                val p=JSONObject(original.toString())
                p.remove("pivotX");p.remove("pivotY")
                p.put("matrix",ArtShapes.encode(matrix))
                captured=null;onEdit("SHAPE_TRANSFORM",p)
            }) { Text("应用") }},
            dismissButton={TextButton(onClick={captured=null},enabled=!busy) { Text("取消") }})
    }
}
