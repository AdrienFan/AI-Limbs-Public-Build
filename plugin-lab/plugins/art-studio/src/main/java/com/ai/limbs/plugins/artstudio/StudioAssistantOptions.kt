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
import kotlin.math.*

@Composable
internal fun StudioAssistantOptions(snapshot: JSONObject,busy: Boolean,adding: Boolean,type: String,
    onAdding: (Boolean)->Unit,onType: (String)->Unit,onEdit: (String,JSONObject)->Unit,onBrush: ()->Unit) {
    val context=LocalContext.current
    val state=snapshot.getJSONObject("state");val all=ArtAssistants.items(state)
    val selected=all.firstOrNull { it.getString("id")==ArtAssistants.selected(state) }
    fun request()=JSONObject().put("documentId",snapshot.getString("id"))
        .put("expectedRevision",snapshot.getInt("revision"))
    fun setting(key: String,value: Any) { onEdit("ASSISTANT_SETTINGS",request().put("settings",JSONObject().put(key,value))) }
    fun changes(value:JSONObject) {
        if(selected!=null)onEdit("ASSISTANT_UPDATE",request().put("id",selected.getString("id")).put("changes",value))
    }
    fun change(key:String,value:Any)=changes(JSONObject().put(key,value))
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
    Text(ArtAssistants.pointHelp.getValue(type),style=MaterialTheme.typography.bodySmall)
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
        val selectedType=selected.getString("type")
        Text(ArtAssistants.pointHelp.getValue(selectedType),style=MaterialTheme.typography.bodySmall)
        val local=selected.optBoolean("localEnabled",false)
        FilterChip(selected=local,enabled=editable,label={Text("局部作用区")},onClick={
            val update=JSONObject().put("localEnabled",!local)
            if(!local&&!selected.has("localBounds")) {
                val points=ArtAssistants.points(selected)
                val x=(points.minOf {it.x}-20).coerceAtLeast(-1_000_000.0)
                val y=(points.minOf {it.y}-20).coerceAtLeast(-1_000_000.0)
                val right=(points.maxOf {it.x}+20).coerceAtMost(1_000_000.0)
                val bottom=(points.maxOf {it.y}+20).coerceAtMost(1_000_000.0)
                update.put("localBounds",JSONObject().put("x",x).put("y",y).put("width",right-x).put("height",bottom-y))
            }
            changes(update)
        })
        if(local) {
            val keys=listOf("x","y","width","height")
            var bounds by remember(snapshot.getInt("revision"),selected.getString("id")) {
                mutableStateOf(keys.map {selected.getJSONObject("localBounds").getDouble(it).toString()})
            }
            for(row in 0..1) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                for(column in 0..1) {val i=row*2+column
                    OutlinedTextField(bounds[i],{text->bounds=bounds.mapIndexed {j,v->if(i==j)text else v}},
                        modifier=Modifier.weight(1f),label={Text(listOf("区域 X","区域 Y","宽（px）","高（px）")[i])},singleLine=true,enabled=editable)
                }
            }
            TextButton(enabled=editable,onClick={
                val values=bounds.map {it.toDoubleOrNull()}
                if(values.any {it==null||!it.isFinite()} || values[2]!!<0.01||values[3]!!<0.01 ||
                    abs(values[0]!!)>1_000_000||abs(values[1]!!)>1_000_000||
                    abs(values[0]!!+values[2]!!)>1_000_000||abs(values[1]!!+values[3]!!)>1_000_000)
                    Toast.makeText(context,"请输入有效的局部矩形范围",Toast.LENGTH_SHORT).show()
                else change("localBounds",JSONObject().apply {keys.forEachIndexed {i,k->put(k,values[i]!!)}})
            }) {Text("应用作用区")}
            Text("可拖动矩形两角调整范围；仅区域内起笔参与吸附，一笔锁定后可延伸到区域外。",style=MaterialTheme.typography.bodySmall)
        }
        if(selectedType=="ruler") {
            var length by remember(snapshot.getInt("revision"),selected.getString("id")) {mutableStateOf(selected.optDouble("fixedLength",0.0).toString())}
            var unit by remember(snapshot.getInt("revision"),selected.getString("id")) {mutableStateOf(selected.optString("lengthUnit","px"))}
            var dpi by remember(snapshot.getInt("revision"),selected.getString("id")) {mutableStateOf(selected.optDouble("unitDpi",96.0).toString())}
            OutlinedTextField(length,{length=it},label={Text("固定长度（0为自由）")},singleLine=true,enabled=editable)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                ArtAssistantGeometry.units.forEach {(id,label)->FilterChip(selected=unit==id,onClick={unit=id},enabled=editable,label={Text(label)})}
            }
            OutlinedTextField(dpi,{dpi=it},label={Text("单位换算 DPI（1–2400）")},singleLine=true,enabled=editable)
            TextButton(enabled=editable,onClick={
                val n=length.toDoubleOrNull();val d=dpi.toDoubleOrNull()
                if(n==null||!n.isFinite()||n<0||d==null||!d.isFinite()||d !in 1.0..2400.0 ||
                    (n>0&&ArtAssistantGeometry.pixels(n,unit,d) !in 0.01..1_000_000.0))
                    Toast.makeText(context,"请输入有效长度与 DPI",Toast.LENGTH_SHORT).show()
                else changes(JSONObject().put("fixedLength",n).put("lengthUnit",unit).put("unitDpi",d))
            }) {Text("应用固定长度")}
            Text("物理单位按此尺规保存的 DPI 换算为文档像素；不会设置作品打印分辨率。固定长度保持起点和方向，调整终点距离。",style=MaterialTheme.typography.bodySmall)
        }
        if(selectedType=="two_vanishing_points")FilterChip(selected=selected.optBoolean("useVertical",true),enabled=editable,
            onClick={change("useVertical",!selected.optBoolean("useVertical",true))},label={Text("允许垂直方向")})
        val countKey=when(selectedType) {
            "ruler","perspective_grid"->"subdivisions"
            "vanishing_point","two_vanishing_points","fisheye","curvilinear_perspective"->"rays"
            else->null
        }
        if(countKey!=null) {
            var count by remember(snapshot.getInt("revision"),selected.getString("id"),countKey) {
                mutableFloatStateOf(selected.getInt(countKey).toFloat())
            }
            Text(if(countKey=="rays") "预览线数：${count.roundToInt()}" else "刻度／网格分段：${count.roundToInt()}")
            Slider(value=count,onValueChange={count=it},onValueChangeFinished={change(countKey,count.roundToInt())},
                valueRange=if(countKey=="rays")4f..(if(selectedType in setOf("fisheye","curvilinear_perspective"))16f else 64f) else if(selectedType=="perspective_grid")1f..100f else 0f..100f,enabled=editable)
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
    Text("吸附支持自由画笔、铅笔、软笔、喷枪、橡皮擦、栅格书法笔、多重画笔和动态画笔。直线仅吸附直尺、平行尺、消失点、透视网格及双消失点的直线方向。",style=MaterialTheme.typography.bodySmall)
}
