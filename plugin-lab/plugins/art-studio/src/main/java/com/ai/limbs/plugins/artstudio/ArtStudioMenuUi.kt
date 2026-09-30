package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

internal fun showStudioRemainingMenu(context: Context, anchor: View, title: String,
    menuContext: JSONObject, busy: Boolean, onAction: (JSONObject, JSONObject) -> Unit) {
    val catalog = ArtStudioMenuCatalog.describe(menuContext).getJSONArray("menus")
    val definition = (0 until catalog.length()).map { catalog.getJSONObject(it) }
        .first { it.getString("title") == title }
    val captured = JSONObject(menuContext.toString())
    val lookup = mutableMapOf<Int, JSONObject>()
    var sequence = 400
    fun populate(menu: Menu, items: JSONArray) {
        var group = 0
        for (n in 0 until items.length()) {
            val item = items.getJSONObject(n)
            if (item.optBoolean("separator")) { group++; continue }
            val id = sequence++
            val children = item.optJSONArray("children")
            if (children != null) {
                val sub = menu.addSubMenu(group, id, id, item.getString("title"))
                // Keep an unavailable category inspectable so its grey child entries remain visible.
                sub.item.isEnabled = !busy
                populate(sub, children)
            } else {
                lookup[id] = item
                menu.add(group, id, id, item.getString("title")).apply {
                    isEnabled = item.getBoolean("enabled") && !busy
                    contentDescription = item.getString("title") +
                        if (item.getBoolean("enabled")) "" else "，" + item.getString("unavailableReason")
                    if (item.getString("id").removePrefix("docker.") in ArtDockPanels.ids) {
                        isCheckable = true
                        isChecked = captured.getJSONObject("dockPanels").getJSONObject("visible")
                            .getBoolean(item.getString("id").removePrefix("docker."))
                    }
                    if (item.getString("id") in setOf("toggle_display_selection", "view_toggledockers")) {
                        isCheckable = true
                        val settings = captured.getJSONObject("settings")
                        isChecked = if (item.getString("id") == "toggle_display_selection")
                            settings.getBoolean("selectionVisible") else !settings.getBoolean("panelsHidden")
                    }
                }
            }
        }
        menu.setGroupDividerEnabled(true)
    }
    PopupMenu(context, anchor).apply {
        populate(menu, definition.getJSONArray("children"))
        setOnMenuItemClickListener { item ->
            lookup[item.itemId]?.let { onAction(it, captured) }
            true
        }
        setOnDismissListener {
            anchor.isSelected = false
            anchor.setBackgroundColor(Color.TRANSPARENT)
            (anchor as? android.widget.TextView)?.setTextColor(Color.rgb(218,218,218))
        }
        show()
    }
}

@Composable
internal fun StudioMenuParameters(item: JSONObject, initial: JSONObject,
    onDismiss: () -> Unit, onExecute: (JSONObject) -> Unit) {
    val specs = item.optJSONArray("parameters") ?: JSONArray()
    val values = remember(item) { mutableStateMapOf<String,String>().apply {
        for (n in 0 until specs.length()) {
            val field = specs.getJSONObject(n); val key = field.getString("name")
            put(key, if (initial.has(key)) initial.get(key).toString() else field.opt("default")?.toString() ?: "")
        }
    } }
    var error by remember(item) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onDismiss, title={ Text(item.getString("title")) }, text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if (item.getString("id") in setOf("flatten_image","flatten_layer","merge_layer","cut_layer_clipboard","reset_configurations"))
                Text(if(item.getString("id")=="reset_configurations") "重置画室自己的基础配置，保留工程与历史。"
                    else "此操作会改变图层结构。原图层与像素保留在工程历史中，可通过撤销或足迹恢复。合并画布会移除隐藏图层。")
            item.optString("notice").takeIf { it.isNotBlank() }?.let { Text(it) }
            if (specs.length()==0) Text("确认执行此菜单操作？")
            for(n in 0 until specs.length()) {
                val field=specs.getJSONObject(n); val key=field.getString("name")
                val choices=field.optJSONArray("choices")
                if(field.getString("type")=="boolean") {
                    Row { Checkbox(checked=values[key]=="true",onCheckedChange={values[key]=it.toString()})
                        Text(field.getString("description")) }
                } else if(choices!=null) {
                    Text(field.getString("description"))
                    for(c in 0 until choices.length()) {
                        val choice=choices.getString(c)
                        TextButton(onClick={values[key]=choice}) { Text((if(values[key]==choice) "✓ " else "")+choice) }
                    }
                } else OutlinedTextField(value=values.getValue(key),onValueChange={values[key]=it;error=null},
                    label={Text(field.getString("description"))},singleLine=true,modifier=Modifier.fillMaxWidth())
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    }, confirmButton={ TextButton(onClick={
        try {
            val p=JSONObject()
            for(n in 0 until specs.length()) {
                val field=specs.getJSONObject(n); val key=field.getString("name"); val value=values.getValue(key)
                p.put(key,when(field.getString("type")) {
                    "integer" -> value.toInt()
                    "number" -> value.toDouble().also { require(it.isFinite()) }
                    "boolean" -> value.toBooleanStrict()
                    else -> value.trim().also { require(field.optBoolean("allowBlank") || it.isNotBlank()) { "请填写 ${field.getString("description")}" } }
                })
            }
            onExecute(p)
        } catch(e:IllegalArgumentException) { error=e.message ?: "参数格式无效" }
    }) {Text("确定")} }, dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable
internal fun StudioSaveLocationOptions(storage: JSONObject?, directoryKey: String,
    chooseLocation: Boolean, onChoose: (Boolean) -> Unit) {
    if(storage!=null && storage.getBoolean("custom")) {
        Text("默认目录：" + storage.getString(directoryKey))
        Row {
            Checkbox(checked=chooseLocation,onCheckedChange=onChoose)
            Text("本次选择其他保存位置")
        }
    }
}

@Composable
internal fun StudioMenuResult(result: JSONObject, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(result.optString("title","画室菜单"))}, text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(result.has("luminance")) {
                Text("非透明像素：${result.getLong("nonTransparentPixels")}")
                for((key,label,color) in listOf(Triple("red","红色",Color.RED),Triple("green","绿色",Color.GREEN),
                    Triple("blue","蓝色",Color.BLUE),Triple("luminance","亮度",Color.LTGRAY))) {
                    Text(label)
                    AndroidView(factory={context -> StudioHistogram(context)},update={it.bind(result.getJSONArray(key),color)},
                        modifier=Modifier.fillMaxWidth().height(62.dp))
                }
                Text("横轴为 0–255 通道值，纵轴为非透明像素数量；读取当前图层自身像素。")
            } else Text(result.optString("text").ifBlank { result.toString(2) })
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("关闭")}})
}

private class StudioHistogram(context:Context):View(context) {
    private var bins=IntArray(256)
    private val paint=Paint()
    fun bind(values:JSONArray,color:Int) { bins=IntArray(256){values.getInt(it)};paint.color=color;invalidate() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val max=bins.maxOrNull() ?: 0
        if(max==0) return
        for(n in bins.indices) canvas.drawRect(n*width/256f,height*(1-bins[n].toFloat()/max),
            (n+1)*width/256f,height.toFloat(),paint)
    }
}
