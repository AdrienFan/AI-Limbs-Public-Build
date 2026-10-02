package com.ai.limbs.plugins.artstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun StudioColorizeOptions(snapshot: JSONObject,selected: String,busy: Boolean,width: Float,erase: Boolean,
    color: String,onWidth: (Float)->Unit,onErase: (Boolean)->Unit,onColor: (String)->Unit,
    onCreate: (JSONObject)->Unit,onUpdate: (JSONObject)->Unit,
    onEdit: (String,JSONObject)->Unit) {
    val state=snapshot.getJSONObject("state")
    val layer=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==selected}
    fun params()=JSONObject().put("documentId",snapshot.getString("id")).put("expectedRevision",snapshot.getInt("revision")).put("maskId",selected)
    var confirm by remember(selected) {mutableStateOf("")}
    var confirmationParams by remember(selected) {mutableStateOf<JSONObject?>(null)}
    Text("在线稿上首次点击建立蒙版，再用不同前景色画线索；点击更新生成填色。线索和结果分别保存。")
    if(layer==null || layer.getString("kind")!="colorize") {
        TextButton(enabled=!busy && layer?.getString("kind") in setOf("paint","image"),onClick={
            onCreate(JSONObject().put("documentId",snapshot.getString("id"))
                .put("expectedRevision",snapshot.getInt("revision")).put("sourceLayerId",selected))
        }) {Text("从当前线稿建立上色蒙版")}
    } else {
        val data=layer.getJSONObject("colorize");val settings=ArtColorize.normalizeSettings(data.getJSONObject("settings"),JSONObject())
        val editable=!busy && !layer.getBoolean("locked")
        Text(if(ArtColorize.dirty(state,layer))"线索或源线稿已变化，需更新结果" else "填色结果已更新")
        val sourceName=ArtMenuOperations.layers(state).firstOrNull {it.getString("id")==data.getString("sourceLayerId")}?.getString("name") ?: "已删除"
        Text("线索 ${ArtColorize.items(layer).size} 笔；线稿：$sourceName")
        if(!ArtColorize.canUpdate(state,layer))Text("当前源或蒙版已隐藏、移动、分组，或源已删除；请恢复有效的根线稿后更新。缓存结果可转换为绘画层。")
        TextButton(onClick={onUpdate(params())},enabled=editable && ArtColorize.canUpdate(state,layer)) {Text(if(busy)"处理中…" else "更新填色结果")}
        for(key in listOf("editKeys","showOutput","limitBounds")) {
            val label=when(key) {"editKeys"->"编辑颜色线索";"showOutput"->"显示填色结果";else->"限制到线稿和线索边界"}
            FilterChip(selected=settings.getBoolean(key),enabled=editable,onClick={
                onEdit("COLORIZE_SETTINGS",params().put("settings",JSONObject().put(key,!settings.getBoolean(key))))
            },label={Text(label)})
        }
        Text("线索笔径：${width.roundToInt()} px")
        Slider(width,onWidth,enabled=editable,valueRange=1f..256f)
        FilterChip(selected=erase,enabled=editable,onClick={onErase(!erase)},label={Text("擦除颜色线索")})
        Text("前景色：$color；点击已有色块可重新使用。")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ArtColorize.palette(layer).forEach {c ->
                val value=c.getString("color")
                FilterChip(selected=value==color,enabled=editable,onClick={onColor(value);onErase(false)},
                    label={Text(value+if(c.getBoolean("transparent"))" 透明" else "",color=Color(android.graphics.Color.parseColor(value)))})
            }
        }
        val current=ArtColorize.palette(layer).firstOrNull {it.getString("color")==color}
        if(current!=null) {
            TextButton(enabled=editable,onClick={
                onEdit("COLORIZE_PALETTE",params().put("color",color).put("action","transparent")
                    .put("transparent",!current.getBoolean("transparent")))
            }) {Text(if(current.getBoolean("transparent"))"取消透明标记" else "标记该色为透明区域")}
            TextButton(enabled=editable,onClick={
                confirmationParams=params().put("color",color).put("action","remove");confirm="color"
            }) {Text("删除该色全部线索")}
        }
        Text("暗线屏障阈值：${settings.getInt("threshold")}")
        var threshold by remember(selected,settings.getInt("threshold")) {mutableStateOf(settings.getInt("threshold").toFloat())}
        Slider(threshold,{threshold=it},enabled=editable,valueRange=1f..254f,onValueChangeFinished={
            onEdit("COLORIZE_SETTINGS",params().put("settings",JSONObject().put("threshold",threshold.roundToInt())))
        })
        Text("缺口闭合半径：${settings.getInt("gapClose")} px")
        var gap by remember(selected,settings.getInt("gapClose")) {mutableStateOf(settings.getInt("gapClose").toFloat())}
        Slider(gap,{gap=it},enabled=editable,valueRange=0f..8f,steps=7,onValueChangeFinished={
            onEdit("COLORIZE_SETTINGS",params().put("settings",JSONObject().put("gapClose",gap.roundToInt())))
        })
        FilterChip(selected=settings.getBoolean("useEdgeDetection"),enabled=editable,onClick={
            onEdit("COLORIZE_SETTINGS",params().put("settings",JSONObject().put("useEdgeDetection",!settings.getBoolean("useEdgeDetection"))))
        },label={Text("实心阴影／边缘检测")})
        Text("边缘尺寸建议接近最细线宽；0不做边缘滤波。关闭时沿用暗线强度。")
        StudioColorizeNumber(selected,"edgeDetectionSize","边缘检测尺寸",settings.getDouble("edgeDetectionSize"),0f..100f,"px",
            editable&&settings.getBoolean("useEdgeDetection")) {key,value->onEdit("COLORIZE_SETTINGS",params().put("settings",JSONObject().put(key,value)))}
        StudioColorizeNumber(selected,"fuzzyRadius","缺口模糊半径",settings.getDouble("fuzzyRadius"),0f..500f,"px",editable) {key,value->
            onEdit("COLORIZE_SETTINGS",params().put("settings",JSONObject().put(key,value)))}
        Text("缺口提示约为模糊半径的两倍；增大会合拢短缺口，也可能吞掉窄区域。与上方整数闭合半径可叠加。")
        StudioColorizeNumber(selected,"cleanUpAmount","清理强度",settings.getDouble("cleanUpAmount"),0f..1f,"",editable) {key,value->
            onEdit("COLORIZE_SETTINGS",params().put("settings",JSONObject().put(key,value)))}
        Text("0关闭清理，1最强；合并与较大异色区域竞争的小溢出块，不改线索。背景需明确画透明线索，孤立单色区域不会被猜测删除。修改参数后点击更新填色。")
        Row {
            TextButton(enabled=editable && ArtColorize.items(layer).isNotEmpty(),onClick={
                confirmationParams=params();confirm="clear"
            }) {Text("清空线索")}
            TextButton(enabled=editable && layer.getString("asset").isNotBlank(),onClick={
                confirmationParams=params();confirm="convert"
            }) {Text("转换为绘画图层")}
        }
        Text("透明标记让对应填色区域保持透明；它不会擦除原线稿。未有线索的封闭区域保持透明。线索只在编辑视图显示，导出不包含它们。")
    }
    ArtColorize.pending.forEach {Text("$it（待实现）",color=Color.Gray)}
    if(confirm.isNotBlank())AlertDialog(onDismissRequest={confirm="";confirmationParams=null},
        title={Text("确认操作")},text={Text(when(confirm) {
            "clear"->"清空所有颜色线索；现有填色保留到下一次更新，可撤销。"
            "color"->"删除该颜色的全部线索，之后需要更新，可撤销。"
            else->"把当前缓存结果转为普通绘画图层；转换后不再保留可编辑颜色线索，可撤销。请先更新以使用最新结果。"
        })},confirmButton={TextButton(enabled=!busy,onClick={
            val action=when(confirm) {"clear"->"COLORIZE_CLEAR";"color"->"COLORIZE_PALETTE";else->"COLORIZE_CONVERT"}
            val p=requireNotNull(confirmationParams);confirm="";confirmationParams=null;onEdit(action,p)
        }) {Text("确认")}},dismissButton={TextButton(onClick={confirm="";confirmationParams=null}) {Text("取消")}})
}

@Composable
private fun StudioColorizeNumber(maskId:String,key:String,label:String,value:Double,range:ClosedFloatingPointRange<Float>,unit:String,
    enabled:Boolean,onCommit:(String,Double)->Unit) {
    var draft by remember(maskId,key,value) {mutableStateOf(value.toString())}
    val parsed=draft.toDoubleOrNull()?.takeIf {it.isFinite()&&it>=range.start&&it<=range.endInclusive}
    OutlinedTextField(value=draft,onValueChange={draft=it},enabled=enabled,singleLine=true,
        label={Text("$label ($unit ${range.start}–${range.endInclusive})")},isError=parsed==null)
    Slider(parsed?.toFloat() ?: value.toFloat(),{draft=it.toString()},enabled=enabled,valueRange=range,
        onValueChangeFinished={draft.toDoubleOrNull()?.let {onCommit(key,it)}})
    TextButton(enabled=enabled&&parsed!=null&&parsed!=value,onClick={onCommit(key,requireNotNull(parsed))}) {Text("应用$label")}
}
