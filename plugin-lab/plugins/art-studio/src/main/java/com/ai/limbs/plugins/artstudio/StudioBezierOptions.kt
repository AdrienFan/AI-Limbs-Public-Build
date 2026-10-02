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
    command:(String)->Unit,onEdit:(String,JSONObject)->Unit,selectedNodes:List<Int>,multiple:Boolean,box:Boolean,
    onSelection:(List<Int>)->Unit,onMultiple:(Boolean)->Unit,onBox:(Boolean)->Unit) {
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
    val objects=ArtShapes.items(layer).filter {it.getString("id") in ids}
    fun identity()=JSONObject().put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision")).put("layerId",layerId).put("ids",JSONArray(ids))
    if(objects.isNotEmpty()) {
        val writable=!busy&&!ArtMenuOperations.isLocked(state,layer)&&objects.none {it.getBoolean("locked")||!it.getBoolean("visible")}
        TextButton(enabled=writable&&objects.any {it.getString("kind")!="path"},onClick={onSelection(emptyList());onEdit("SHAPE_PATH_CONVERT",identity())}) {Text("选中形状转路径")}
        TextButton(enabled=writable&&objects.size>=2&&objects.all {it.getString("kind")=="path"},onClick={onSelection(emptyList());onEdit("SHAPE_PATH_COMBINE",identity())}) {Text("合成子路径对象")}
        StudioObjectStyleOptions(objects,busy) {patch->onEdit("SHAPE_STYLE",identity().put("style",JSONObject().put("objectStyle",patch)))}
    }
    if(shape==null) {Text("点选一条路径编辑节点；多个路径先合成一个子路径对象。",style=MaterialTheme.typography.labelSmall);return}

    val nodes=ArtPathGeometry.nodes(shape);val index=node.coerceIn(0,nodes.lastIndex)
    val parts=ArtPathTopology.parts(shape);val reference=ArtPathTopology.ref(parts,index);val part=parts[reference.part]
    val isClosed=part.closed
    val chosen=selectedNodes.filter {it in nodes.indices}.ifEmpty {listOf(index)}
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
    FilterChip(selected=multiple,onClick={onMultiple(!multiple)},enabled=!busy,label={Text("点选叠加")})
    FilterChip(selected=box,onClick={onBox(!box)},enabled=!busy,label={Text("框选节点")})
    Text("叠加选择后关闭叠加/框选，拖动任一选中节点即可一起移动。Shift可临时增减点选。",style=MaterialTheme.typography.labelSmall)
    TextButton(enabled=!busy,onClick={onSelection(nodes.indices.toList())}) {Text("全选节点")}
    TextButton(enabled=!busy,onClick={onSelection(emptyList())}) {Text("清空节点选择")}
    Text("子路径 "+(reference.part+1)+"/"+parts.size+"，节点 "+(index+1)+"/"+nodes.size+"，选中 "+selectedNodes.size,style=MaterialTheme.typography.labelSmall)
    TextButton(onClick={onNode((index-1+nodes.size)%nodes.size)},enabled=!busy) { Text("上一节点") }
    TextButton(onClick={onNode((index+1)%nodes.size)},enabled=!busy) { Text("下一节点") }
    for((id,label) in listOf("corner" to "角点","smooth" to "平滑","symmetric" to "对称"))
        FilterChip(selected=nodes[index].type==id,
            onClick={apply(JSONObject().put("action","node_types").put("nodes",JSONArray(chosen)).put("type",id))},
            enabled=allowed,label={Text(label,style=MaterialTheme.typography.labelSmall)})
    TextButton(onClick={coordinate("")},enabled=allowed) { Text("节点坐标…") }
    TextButton(onClick={coordinate("in")},enabled=allowed&&nodes[index].incoming!=null) { Text("入柄坐标…") }
    TextButton(onClick={coordinate("out")},enabled=allowed&&nodes[index].outgoing!=null) { Text("出柄坐标…") }
    val segment=minOf(reference.node,ArtPathGeometry.segmentCount(part.nodes,isClosed)-1)
    TextButton(onClick={
        apply(JSONObject().put("action","insert_node").put("subpath",reference.part).put("segment",segment).put("t",0.5));onSelection(emptyList());onNode(ArtPathTopology.flatIndex(parts,ArtPathTopology.Ref(reference.part,0))+segment+1)
    },enabled=allowed) { Text("段中插点") }
    TextButton(onClick={
        apply(JSONObject().put("action","delete_nodes").put("nodes",JSONArray(chosen)));onSelection(emptyList());onNode(0)
    },enabled=allowed&&parts.indices.all {i->parts[i].nodes.size-chosen.count {ArtPathTopology.ref(parts,it).part==i}>=(if(parts[i].closed)1 else 2)}) { Text("删除节点") }
    TextButton(onClick={apply(JSONObject().put("action","segment_type").put("subpath",reference.part).put("segment",segment).put("type","line"))},
        enabled=allowed&&!(isClosed&&part.nodes.size==1)) { Text("段转直线") }
    TextButton(onClick={apply(JSONObject().put("action","segment_type").put("subpath",reference.part).put("segment",segment).put("type","curve"))},
        enabled=allowed) { Text("段转曲线") }
    TextButton(onClick={apply(JSONObject().put("action","closed").put("subpath",reference.part).put("value",!isClosed))},
        enabled=allowed&&(isClosed&&part.nodes.size>=2||!isClosed)) { Text(if(isClosed) "打开路径" else "闭合路径") }
    fun address(i:Int):JSONArray {val r=ArtPathTopology.ref(parts,i);return JSONArray().put(r.part).put(r.node)}
    fun topology(action:String) {
        val p=params().put("action",action)
        if(action.startsWith("break"))p.put("at",address(index))
        else p.put("first",address(chosen[0])).put("second",address(chosen[1]))
        onSelection(emptyList());onNode(0);onEdit("SHAPE_PATH_TOPOLOGY",p)
    }
    TextButton(enabled=allowed&&(isClosed||reference.node in 1 until part.nodes.lastIndex),onClick={topology("break_node")}) {Text("在当前节点断开")}
    TextButton(enabled=allowed&&(isClosed&&part.nodes.size>=2||!isClosed&&reference.node in 1 until part.nodes.size-2),onClick={topology("break_segment")}) {Text("断开当前后段")}
    val endpoints=chosen.size==2&&chosen.all {i->val r=ArtPathTopology.ref(parts,i);!parts[r.part].closed&&(r.node==0||r.node==parts[r.part].nodes.lastIndex)}
    TextButton(enabled=allowed&&endpoints,onClick={topology("join")}) {Text("连接两个端点")}
    TextButton(enabled=allowed&&endpoints,onClick={topology("merge")}) {Text("合并两个端点")}
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
