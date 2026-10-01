package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StudioBezierSelectionOptions(snapshot: JSONObject,busy: Boolean,editing: Boolean,
    component: Int,node: Int,mode: String,autoSmooth: Boolean,onEditing: (Boolean)->Unit,
    onComponent: (Int)->Unit,onNode: (Int)->Unit,onMode: (String)->Unit,onSmooth: (Boolean)->Unit,
    onCommand: (String)->Unit,onEdit: (JSONObject)->Unit) {
    val selection=snapshot.getJSONObject("state").optJSONObject("selection")
    val parts=selection?.let {ArtBezierSelection.parts(it)} ?: emptyList()
    val curves=parts.indices.filter {parts[it].getJSONObject("selection").optString("shape","rect")=="bezier"}
    val chosen=parts.getOrNull(component)?.getJSONObject("selection")?.takeIf {it.optString("shape","rect")=="bezier"}
    fun edit(action: JSONObject) {
        onEdit(JSONObject().put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision"))
            .put("componentIndex",component).put("edits",JSONArray().put(action)))
    }
    Text("点击放节点，按住拖动拉出切线。点回起点、双击末节点或按完成，闭合为选区；不会绘制作品笔画。")
    Row {
        FilterChip(selected=!editing,enabled=!busy,onClick={onEditing(false)},label={Text("绘制新选区")})
        FilterChip(selected=editing,enabled=!busy && curves.isNotEmpty(),onClick={
            onEditing(true)
        },label={Text("编辑现有选区")})
    }
    if(!editing) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ArtBezierSelection.modes.forEach {(id,label)->FilterChip(selected=mode==id,enabled=!busy,
                onClick={onMode(id)},label={Text(label)})}
        }
        FilterChip(selected=autoSmooth,enabled=!busy,onClick={onSmooth(!autoSmooth)},label={Text("自动平滑未拖动的节点")})
        Row {
            TextButton(enabled=!busy,onClick={onCommand("back")}) {Text("退回上一节点")}
            TextButton(enabled=!busy,onClick={onCommand("finish")}) {Text("完成选区")}
            TextButton(enabled=!busy,onClick={onCommand("cancel")}) {Text("取消")}
        }
        Text("至少两个节点；只有两个节点时需拉出弯曲控制柄。Shift 添加、Alt 减去、Ctrl 替换、Shift+Alt 相交，以第一节点按下时为准。Enter 完成、Backspace 退回、Esc 取消；右键退回上一节点。")
        Text("没有现有选区时，减去／相交产生空选区；它会阻止像素写入，而不是取消选区后放开整张画布。")
    } else if(chosen!=null) {
        Text("选区分量")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            curves.forEach {i->FilterChip(selected=i==component,enabled=!busy,
                onClick={onComponent(i);onNode(0)},label={Text("${i+1} · ${parts[i].getString("mode")}")})}
        }
        val nodes=ArtBezierSelection.nodes(chosen);val index=node.coerceIn(0,nodes.lastIndex)
        Text("节点 ${index+1} / ${nodes.size}；拖节点移动，拖橙色控制柄调整曲线。")
        Row {
            TextButton(enabled=!busy && index>0,onClick={onNode(index-1)}) {Text("上一节点")}
            TextButton(enabled=!busy && index<nodes.lastIndex,onClick={onNode(index+1)}) {Text("下一节点")}
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("corner" to "尖角","smooth" to "平滑","symmetric" to "对称").forEach {(type,label)->
                FilterChip(selected=nodes[index].type==type,enabled=!busy,onClick={
                    edit(JSONObject().put("action","node_type").put("node",index).put("type",type))
                },label={Text(label)})
            }
        }
        Row {
            TextButton(enabled=!busy && nodes.size<ArtBezierSelection.MAX_NODES,onClick={
                edit(JSONObject().put("action","insert_node").put("segment",index).put("t",0.5))
            }) {Text("下一段插入节点")}
            TextButton(enabled=!busy && nodes.size>2,onClick={
                edit(JSONObject().put("action","delete_node").put("node",index));onNode((index-1).coerceAtLeast(0))
            }) {Text("删除节点")}
        }
        Row {
            TextButton(enabled=!busy,onClick={edit(JSONObject().put("action","segment_type").put("segment",index).put("type","line"))}) {Text("下一段变直线")}
            TextButton(enabled=!busy,onClick={edit(JSONObject().put("action","segment_type").put("segment",index).put("type","curve"))}) {Text("下一段变曲线")}
        }
    }
    Text("移动和缩放保留曲线。复合最多32个分量、累计8192节点；超过时请替换选区。选区不随作品导出。")
    ArtBezierSelection.pending.forEach {Text("$it（待实现）",color=Color.Gray)}
}
