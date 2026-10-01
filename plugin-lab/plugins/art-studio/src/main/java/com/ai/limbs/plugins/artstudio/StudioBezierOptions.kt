package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun StudioBezierOptions(snapshot:JSONObject,layerId:String,busy:Boolean,editing:Boolean,node:Int,
    nodeType:String,closed:Boolean,fill:Boolean,onMode:(Boolean)->Unit,onNode:(Int)->Unit,
    onType:(String)->Unit,onClosed:(Boolean)->Unit,onFill:(Boolean)->Unit,
    command:(String)->Unit,onEdit:(String,JSONObject)->Unit) {
    val state=snapshot.getJSONObject("state")
    val layer=ArtMenuOperations.layers(state).firstOrNull { it.getString("id")==layerId }
    var dialog by remember { mutableStateOf<JSONObject?>(null) }
    var x by remember { mutableStateOf("") };var y by remember { mutableStateOf("") }
    Text("贝塞尔路径",style=MaterialTheme.typography.labelSmall)
    FilterChip(selected=!editing,onClick={onMode(false)},enabled=!busy,label={Text("绘制")})
    FilterChip(selected=editing,onClick={onMode(true)},enabled=!busy,label={Text("编辑节点")})
    if(layer?.getString("kind")!="vector") {
        Text("需矢量图层",style=MaterialTheme.typography.labelSmall)
        TextButton(onClick={onEdit("VECTOR_LAYER_CREATE",JSONObject().put("id",UUID.randomUUID().toString())
            .put("name","矢量图层").put("select",true).put("parentId",
                if(layer?.getString("kind")=="group") layerId else layer?.optString("parentId").orEmpty()))},
            enabled=!busy) { Text("新建矢量层",style=MaterialTheme.typography.labelSmall) }
        return
    }
    if(!editing) {
        Text("点按放点，拖动出柄；点起点闭合。",style=MaterialTheme.typography.labelSmall)
        for((id,label) in listOf("corner" to "独立柄","smooth" to "平滑柄","symmetric" to "对称柄"))
            FilterChip(selected=nodeType==id,onClick={onType(id)},enabled=!busy,
                label={Text(label,style=MaterialTheme.typography.labelSmall)})
        FilterChip(selected=closed,onClick={onClosed(!closed)},enabled=!busy,label={Text("闭合")})
        FilterChip(selected=fill,onClick={onFill(!fill)},enabled=!busy,label={Text("闭合填色")})
        TextButton(onClick={command("finish")},enabled=!busy) { Text("完成路径") }
        TextButton(onClick={command("close")},enabled=!busy) { Text("闭合完成") }
        TextButton(onClick={command("back")},enabled=!busy) { Text("撤回节点") }
        TextButton(onClick={command("cancel")},enabled=!busy) { Text("取消绘制") }
        return
    }
    val ids=ArtShapes.selected(state,layerId)
    val shape=if(ids.size==1) ArtShapes.items(layer).firstOrNull { it.getString("id")==ids[0]&&it.getString("kind")=="path" } else null
    if(shape==null) { Text("点选一条路径，再拖节点或控制柄。",style=MaterialTheme.typography.labelSmall);return }
    val nodes=ArtPathGeometry.nodes(shape);val index=node.coerceIn(0,nodes.lastIndex)
    val isClosed=shape.getBoolean("closed")
    val allowed=!busy&&ArtShapes.visible(state,layer)&&shape.getBoolean("visible")&&shape.getDouble("opacity")>0.0&&
        !ArtMenuOperations.isLocked(state,layer)&&!shape.getBoolean("locked")
    fun params()=JSONObject().put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision"))
        .put("layerId",layerId).put("id",shape.getString("id"))
    fun apply(e:JSONObject) { onEdit("SHAPE_PATH_EDIT",params().put("edits",JSONArray().put(e))) }
    fun coordinate(side:String) {
        val point=if(side=="in") nodes[index].incoming else if(side=="out") nodes[index].outgoing else nodes[index].point
        requireNotNull(point)
        dialog=params().put("node",index).put("side",side)
        x=point.x.toString();y=point.y.toString()
    }
    Text("节点 "+(index+1)+"/"+nodes.size,style=MaterialTheme.typography.labelSmall)
    TextButton(onClick={onNode((index-1+nodes.size)%nodes.size)},enabled=!busy) { Text("上一节点") }
    TextButton(onClick={onNode((index+1)%nodes.size)},enabled=!busy) { Text("下一节点") }
    for((id,label) in listOf("corner" to "角点","smooth" to "平滑","symmetric" to "对称"))
        FilterChip(selected=nodes[index].type==id,
            onClick={apply(JSONObject().put("action","node_type").put("node",index).put("type",id))},
            enabled=allowed,label={Text(label,style=MaterialTheme.typography.labelSmall)})
    TextButton(onClick={coordinate("")},enabled=allowed) { Text("节点坐标…") }
    TextButton(onClick={coordinate("in")},enabled=allowed&&nodes[index].incoming!=null) { Text("入柄坐标…") }
    TextButton(onClick={coordinate("out")},enabled=allowed&&nodes[index].outgoing!=null) { Text("出柄坐标…") }
    val segment=minOf(index,ArtPathGeometry.segmentCount(nodes,isClosed)-1)
    TextButton(onClick={
        apply(JSONObject().put("action","insert_node").put("segment",segment).put("t",0.5));onNode(segment+1)
    },enabled=allowed) { Text("段中插点") }
    TextButton(onClick={
        apply(JSONObject().put("action","delete_node").put("node",index));onNode((index-1).coerceAtLeast(0))
    },enabled=allowed&&nodes.size>(if(isClosed) 1 else 2)) { Text("删除节点") }
    TextButton(onClick={apply(JSONObject().put("action","segment_type").put("segment",segment).put("type","line"))},
        enabled=allowed&&!(isClosed&&nodes.size==1)) { Text("段转直线") }
    TextButton(onClick={apply(JSONObject().put("action","segment_type").put("segment",segment).put("type","curve"))},
        enabled=allowed) { Text("段转曲线") }
    TextButton(onClick={apply(JSONObject().put("action","closed").put("value",!isClosed))},
        enabled=allowed&&(isClosed&&nodes.size>=2||!isClosed)) { Text(if(isClosed) "打开路径" else "闭合路径") }
    dialog?.let { original ->
        val px=x.toDoubleOrNull();val py=y.toDoubleOrNull()
        val valid=px!=null&&py!=null&&px.isFinite()&&py.isFinite()&&
            kotlin.math.abs(px)<=1000000.0&&kotlin.math.abs(py)<=1000000.0
        AlertDialog(onDismissRequest={if(!busy) dialog=null},title={Text("路径坐标")},
            text={Column {
                Text("对象局部坐标")
                OutlinedTextField(x,{x=it},label={Text("X")},enabled=!busy)
                OutlinedTextField(y,{y=it},label={Text("Y")},enabled=!busy)
            }},
            confirmButton={TextButton(enabled=valid&&!busy,onClick={
                val side=original.getString("side")
                val action=JSONObject().put("action",if(side.isBlank()) "move_node" else "move_handle")
                    .put("node",original.getInt("node")).put("x",requireNotNull(px)).put("y",requireNotNull(py))
                if(side.isNotBlank()) action.put("side",side)
                val p=JSONObject(original.toString());p.remove("node");p.remove("side")
                p.put("edits",JSONArray().put(action));dialog=null;onEdit("SHAPE_PATH_EDIT",p)
            }) { Text("应用") }},
            dismissButton={TextButton(onClick={dialog=null},enabled=!busy) { Text("取消") }})
    }
}
