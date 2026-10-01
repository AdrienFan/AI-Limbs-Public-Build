package com.ai.limbs.plugins.artstudio

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioAssistantOptions(snapshot: JSONObject,busy: Boolean,adding: Boolean,type: String,
    onAdding: (Boolean)->Unit,onType: (String)->Unit,onEdit: (String,JSONObject)->Unit,onBrush: ()->Unit) {
    val context=LocalContext.current
    val state=snapshot.getJSONObject("state");val all=ArtAssistants.items(state)
    val selected=all.firstOrNull { it.getString("id")==ArtAssistants.selected(state) }
    fun request()=JSONObject().put("documentId",snapshot.getString("id"))
        .put("expectedRevision",snapshot.getInt("revision"))
    fun setting(key: String,value: Any) { onEdit("ASSISTANT_SETTINGS",request().put("settings",JSONObject().put(key,value))) }
    fun change(key: String,value: Any) {
        if(selected!=null)onEdit("ASSISTANT_UPDATE",request().put("id",selected.getString("id"))
            .put("changes",JSONObject().put(key,value)))
    }
    Text("尺规随工程保存，不导出到作品。",style=MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        ArtAssistants.types.forEach { (id,label) ->
            FilterChip(selected=type==id,onClick={onType(id)},enabled=!busy,label={Text(label)})
        }
    }
    FilterChip(selected=adding,onClick={onAdding(!adding)},enabled=!busy && (adding || all.size<ArtAssistants.MAX),
        label={Text(if(adding)"创建模式：依次点击控制点" else "新建尺规")})
    Text(if(adding) "当前类型需要 ${ArtAssistants.count(type)} 个点。点击画布依次放置；Esc 或切换工具取消未完成创建。"
        else "拖圆点修改控制点，拖尺规本体整体移动；锁定后只能选择。",style=MaterialTheme.typography.bodySmall)
    if(type in setOf("ellipse","concentric_ellipse"))
        Text("前两点确定主轴；第三点在两端之间的侧方，椭圆会经过第三点。",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick=onBrush,enabled=!busy) { Text("切回自由画笔") }
    val settings=ArtAssistants.settings(state)
    for((key,label) in listOf("visible" to "显示辅助线","snapping" to "画笔吸附","onlySelected" to "只吸附选中尺规")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f))
            Switch(checked=settings.getBoolean(key),onCheckedChange={setting(key,it)},enabled=!busy)
        }
    }
    var threshold by remember(snapshot.getString("id"),snapshot.getInt("revision")) {
        mutableFloatStateOf(settings.getDouble("thresholdDp").toFloat())
    }
    Text("起笔吸附范围：${threshold.roundToInt()} dp")
    Slider(value=threshold,onValueChange={threshold=it},onValueChangeFinished={setting("thresholdDp",threshold.toDouble())},
        valueRange=4f..64f,enabled=!busy)
    Text("按起笔位置与最初方向选择尺规，一笔锁定一个目标。平行尺和消失点可从任意非退化位置起笔。",style=MaterialTheme.typography.bodySmall)
    for(a in all) FilterChip(selected=selected?.getString("id")==a.getString("id"),enabled=!busy,
        onClick={onAdding(false);onEdit("ASSISTANT_SELECT",request().put("id",a.getString("id")))},
        label={Text(a.getString("name") + if(a.getBoolean("locked"))" 🔒" else "")})
    if(selected!=null) {
        val editable=!busy && !selected.getBoolean("locked")
        var coordinates by remember(snapshot.getString("id"),snapshot.getInt("revision"),selected.getString("id")) {
            mutableStateOf(ArtAssistants.points(selected).map { it.x.toString() to it.y.toString() })
        }
        coordinates.forEachIndexed { index,(x,y) ->
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(x,{text->coordinates=coordinates.mapIndexed { i,p -> if(i==index)text to p.second else p }},
                    modifier=Modifier.weight(1f),label={Text("点${index+1} X")},singleLine=true,enabled=editable)
                OutlinedTextField(y,{text->coordinates=coordinates.mapIndexed { i,p -> if(i==index)p.first to text else p }},
                    modifier=Modifier.weight(1f),label={Text("点${index+1} Y")},singleLine=true,enabled=editable)
            }
        }
        TextButton(enabled=editable,onClick={
            val p=coordinates.map { it.first.toDoubleOrNull() to it.second.toDoubleOrNull() }
            if(p.any { it.first==null || it.second==null || !it.first!!.isFinite() || !it.second!!.isFinite() })
                Toast.makeText(context,"请输入有效坐标",Toast.LENGTH_SHORT).show()
            else change("points",JSONArray(p.map { JSONArray().put(it.first!!).put(it.second!!) }))
        }) { Text("应用控制点") }
        for((key,label) in listOf("visible" to "显示此尺规","enabled" to "允许此尺规吸附","locked" to "锁定")) {
            FilterChip(selected=selected.getBoolean(key),enabled=if(key=="locked")!busy else editable,
                onClick={change(key,!selected.getBoolean(key))},label={Text(label)})
        }
        val countKey=if(selected.getString("type")=="ruler")"subdivisions" else if(selected.getString("type")=="vanishing_point")"rays" else null
        if(countKey!=null) {
            var count by remember(snapshot.getInt("revision"),selected.getString("id"),countKey) {
                mutableFloatStateOf(selected.getInt(countKey).toFloat())
            }
            Text(if(countKey=="rays") "预览线数：${count.roundToInt()}" else "刻度分段：${count.roundToInt()}")
            Slider(value=count,onValueChange={count=it},onValueChangeFinished={change(countKey,count.roundToInt())},
                valueRange=if(countKey=="rays")4f..64f else 0f..100f,enabled=editable)
        }
        var deleting by remember { mutableStateOf<JSONObject?>(null) }
        TextButton(enabled=editable,onClick={deleting=request().put("id",selected.getString("id"))}) { Text("删除尺规") }
        deleting?.let { captured ->
            AlertDialog(onDismissRequest={deleting=null},title={Text("删除辅助尺规？")},
                text={Text("可撤销；已经画好的笔迹保持不变。")},
                confirmButton={TextButton(onClick={deleting=null;onEdit("ASSISTANT_DELETE",captured)}){Text("删除")}},
                dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})
        }
    }
    Text("本轮吸附：自由画笔、铅笔、软笔、喷枪、橡皮擦、栅格书法笔。矢量、动态与多重画笔吸附待实现。",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("复杂助手待实现",style=MaterialTheme.typography.labelLarge)
    ArtAssistants.pending.forEach { TextButton(onClick={},enabled=false){Text(it+"（待实现）")} }
}
