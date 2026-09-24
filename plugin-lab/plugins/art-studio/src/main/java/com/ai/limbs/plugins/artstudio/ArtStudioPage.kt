package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot

internal class ArtStudioPage(private val host: InProcessPluginUiHost) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View {
        val pluginContext = host.createPluginContext(context)
        val bridge = StudioMenuBridge()
        val root = FrameLayout(pluginContext)
        val content = ComposeView(pluginContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { Studio(host, bridge) } }
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val density = pluginContext.resources.displayMetrics.density
        val button = TextView(pluginContext).apply {
            text = "⋮"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "画室菜单：新建、保存、另存为"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(52, 52, 58))
                cornerRadius = 12f * density
            }
            setOnClickListener {
                val popup = PopupMenu(pluginContext, this)
                val labels = listOf(
                    "new" to "新建画布",
                    "open" to "打开工程",
                    "save" to "保存工程",
                    "png" to "另存为 PNG 图片…",
                    "jpeg" to "另存为 JPEG 图片…",
                    "archive" to "另存工程副本 .ailart…",
                    "image" to "导入图片…",
                    "import" to "导入工程…",
                    "rename" to "重命名工程"
                )
                labels.forEachIndexed { index, (action, label) ->
                    popup.menu.add(0, index + 1, index, label).isEnabled =
                        !bridge.busy && (bridge.hasDocument || action in setOf("new", "open", "import"))
                }
                popup.setOnMenuItemClickListener { item ->
                    bridge.action?.invoke(labels[item.itemId - 1].first)
                    true
                }
                popup.show()
            }
        }
        root.addView(button, FrameLayout.LayoutParams((52f * density).toInt(),
            (48f * density).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (8f * density).toInt()
            rightMargin = (8f * density).toInt()
        })
        return root
    }
}

private class StudioMenuBridge {
    var hasDocument: Boolean = false
    var busy: Boolean = false
    var action: ((String) -> Unit)? = null
}

@Composable
private fun Studio(host: InProcessPluginUiHost, menuBridge: StudioMenuBridge) {
    val context = LocalContext.current
    val store = remember(host.dataDir) { ArtStore(host.dataDir) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var tool by remember { mutableStateOf("ink") }
    var color by remember { mutableStateOf("#FF161616") }
    var width by remember { mutableFloatStateOf(6f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var newCanvas by remember { mutableStateOf(false) }
    var canvasWidth by remember { mutableStateOf("1024") }
    var canvasHeight by remember { mutableStateOf("1024") }
    var transparent by remember { mutableStateOf(false) }
    var openDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var layerName by remember { mutableStateOf("") }
    var projectDialog by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var colorDialog by remember { mutableStateOf(false) }
    var colorText by remember { mutableStateOf(color) }
    var transformDialog by remember { mutableStateOf(false) }
    var transformX by remember { mutableStateOf("0") }
    var transformY by remember { mutableStateOf("0") }
    var transformScale by remember { mutableStateOf("1") }
    var transformAngle by remember { mutableStateOf("0") }
    var panel by remember { mutableStateOf("layers") }
    var exportPath by remember { mutableStateOf("") }
    var archivePath by remember { mutableStateOf("") }
    var awaitingExport by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingOperations by remember { mutableIntStateOf(0) }
    var renderSerial by remember { mutableIntStateOf(0) }
    var revision by remember { mutableStateOf("") }
    var documents by remember { mutableStateOf(JSONArray()) }
    val mutex = remember { Mutex() }
    val selected = snapshot?.optJSONObject("state")?.optString("selectedLayerId") ?: ""

    fun refresh() {
        val serial = ++renderSerial
        scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val state = store.current()
                        Triple(state, ArtRenderer.render(store, state), store.revision())
                    }
                }
                if (serial == renderSerial) {
                    snapshot = pair.first
                    image?.recycle()
                    image = pair.second
                    revision = pair.third
                } else pair.second.recycle()
            } catch (error: Exception) {
                if (serial == renderSerial) { snapshot = null; image = null }
            }
        }
    }

    fun perform(action: () -> JSONObject) {
        val serial = ++renderSerial
        pendingOperations++
        busy = true
        scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        action()
                        val state = store.current()
                        Triple(state, ArtRenderer.render(store, state), store.revision())
                    }
                }
                if (serial == renderSerial) {
                    snapshot = pair.first
                    image?.recycle()
                    image = pair.second
                    revision = pair.third
                } else pair.second.recycle()
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Edit failed", error)
                Toast.makeText(context, error.message ?: "画室操作失败", Toast.LENGTH_LONG).show()
            } finally { pendingOperations--; busy = pendingOperations > 0 || awaitingExport }
        }
    }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) perform {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(8 * 1024 * 1024 + 1) }
                ?: error("无法读取图片")
            require(bytes.size <= 8 * 1024 * 1024) { "图片大小上限为 8 MB" }
            store.importImage("AWEI", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
    }
    val importProject = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) perform {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(64 * 1024 * 1024 + 1) }
                ?: error("无法读取工程文件")
            store.importArchive(bytes)
        }
    }
    val exportProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) { awaitingExport = false; busy = pendingOperations > 0 }
        else {
            val source = archivePath
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            java.io.File(source).inputStream().use { it.copyTo(output) }
                        } ?: error("无法写入工程文件")
                    }
                    Toast.makeText(context, "工程备份已保存", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(context, error.message ?: "备份失败", Toast.LENGTH_LONG).show()
                } finally { awaitingExport = false; busy = pendingOperations > 0 }
            }
        }
    }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri == null) { awaitingExport = false; busy = pendingOperations > 0 }
        else {
            val source = exportPath
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            java.io.File(source).inputStream().use { it.copyTo(output) }
                        } ?: error("无法写入所选文件")
                    }
                    Toast.makeText(context, "PNG 图片已保存", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(context, error.message ?: "保存图片失败", Toast.LENGTH_LONG).show()
                } finally { awaitingExport = false; busy = pendingOperations > 0 }
            }
        }
    }
    val exportJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri == null) { awaitingExport = false; busy = pendingOperations > 0 }
        else {
            val source = exportPath
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            java.io.File(source).inputStream().use { it.copyTo(output) }
                        } ?: error("无法写入所选文件")
                    }
                    Toast.makeText(context, "JPEG 图片已保存", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(context, error.message ?: "保存图片失败", Toast.LENGTH_LONG).show()
                } finally { awaitingExport = false; busy = pendingOperations > 0 }
            }
        }
    }
    LaunchedEffect(store) {
        refresh()
        while (true) {
            delay(1200)
            if (!busy && withContext(Dispatchers.IO) { store.revision() } != revision) refresh()
        }
    }
    LaunchedEffect(openDialog) {
        if (openDialog) documents = withContext(Dispatchers.IO) { store.list() }
    }

    val current = snapshot
    val state = current?.getJSONObject("state")
    val layers = state?.getJSONArray("layers")
    val selectedLayer = (0 until (layers?.length() ?: 0)).map { layers!!.getJSONObject(it) }
        .firstOrNull { it.getString("id") == selected }
    fun edit(type: String, params: JSONObject = JSONObject()) {
        perform { store.apply("AWEI", type, params) }
    }
    fun publish(format: String) {
        if (awaitingExport || busy) return
        awaitingExport = true
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    mutex.withLock { ArtRenderer.export(host.dataDir, store, store.current(), format, "") }
                }
                exportPath = result.getString("path")
                if (format == "png") exportPng.launch(result.getString("name"))
                else exportJpeg.launch(result.getString("name"))
            } catch (e: Exception) {
                awaitingExport = false
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            } finally { busy = pendingOperations > 0 || awaitingExport }
        }
    }
    fun saveProject() {
        if (current == null || busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { mutex.withLock { store.save() } }
                refresh()
                Toast.makeText(context, "工程已保存，可继续编辑；图片请从菜单另存为", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, error.message ?: "保存工程失败", Toast.LENGTH_LONG).show()
            } finally { busy = pendingOperations > 0 || awaitingExport }
        }
    }
    fun saveProjectCopy() {
        if (current == null || busy) return
        awaitingExport = true
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { mutex.withLock { store.save() } }
                archivePath = result.getString("path")
                val name = state?.optString("name", "画室") ?: "画室"
                exportProject.launch("${name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.ailart")
            } catch (error: Exception) {
                awaitingExport = false
                Toast.makeText(context, error.message ?: "备份工程失败", Toast.LENGTH_LONG).show()
            } finally { busy = pendingOperations > 0 || awaitingExport }
        }
    }
    SideEffect {
        menuBridge.hasDocument = current != null
        menuBridge.busy = busy
        menuBridge.action = { action ->
            when (action) {
                "new" -> newCanvas = true
                "open" -> openDialog = true
                "save" -> saveProject()
                "png" -> publish("png")
                "jpeg" -> publish("jpeg")
                "archive" -> saveProjectCopy()
                "image" -> import.launch("image/*")
                "import" -> importProject.launch("*/*")
                "rename" -> {
                    projectName = state?.optString("name", "未命名工程") ?: "未命名工程"
                    projectDialog = true
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 64.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(state?.optString("name", "未命名工程") ?: "画室", maxLines = 1,
                style = MaterialTheme.typography.titleMedium)
        }
        if (current == null) {
            Text("新建画布，即可开始和兰儿共同编辑。", Modifier.padding(12.dp))
        } else {
            val state = current.getJSONObject("state")
            val layers = state.getJSONArray("layers")
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${state.getInt("width")} × ${state.getInt("height")} · ${layers.length()} 层 · ${if (current.getBoolean("dirty")) "未保存" else "已保存"}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { perform { store.history("AWEI", false) } }, enabled = !busy) { Text("撤销") }
                TextButton(onClick = { perform { store.history("AWEI", true) } }, enabled = !busy) { Text("重做") }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.width(86.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("ink" to "墨笔", "pencil" to "铅笔", "soft" to "软笔", "spray" to "喷枪",
                        "eraser" to "橡皮", "select" to "选区", "move" to "移动", "pan" to "视图")
                        .forEach { (key, label) ->
                            FilterChip(selected = tool == key, onClick = { tool = key },
                                label = { Text(label, maxLines = 1) }, modifier = Modifier.fillMaxWidth())
                        }
                }
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                AndroidView(factory = { ctx -> StudioCanvas(ctx) }, modifier = Modifier.fillMaxSize(), update = { view ->
                    view.image = image
                    view.layers = layers
                    view.selectedId = selected
                    view.selection = state.optJSONObject("selection")
                    view.tool = tool; view.color = color; view.brushWidth = width; view.opacity = opacity
                    view.onStroke = { points ->
                        if (selectedLayer?.getString("kind") != "paint")
                            Toast.makeText(context, "请选择绘画图层", Toast.LENGTH_SHORT).show()
                        else edit("STROKE_ADD", JSONObject().put("id", UUID.randomUUID().toString())
                            .put("layerId", selected).put("tool", tool).put("color", color)
                            .put("width", width.toDouble()).put("opacity", opacity.toDouble()).put("points", points))
                    }
                    view.onSelection = { rect -> edit("SELECTION_CREATE", rect) }
                    view.onMove = { dx, dy ->
                        if (selectedLayer != null) {
                            if (state.optJSONObject("selection") != null) edit("SELECTION_EDIT", JSONObject()
                                .put("layerId", selected).put("action", "MOVE").put("dx", dx).put("dy", dy))
                            else edit("TRANSFORM", JSONObject().put("id", selected)
                                .put("x", selectedLayer.getDouble("x") + dx).put("y", selectedLayer.getDouble("y") + dy))
                        }
                    }
                })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedButton(onClick = { colorText = color; colorDialog = true }) {
                    Text("● 颜色", color = androidx.compose.ui.graphics.Color(Color.parseColor(color)))
                }
                Text("笔粗 ${width.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = width, onValueChange = { width = it }, valueRange = 1f..80f, modifier = Modifier.weight(1f))
                Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = opacity, onValueChange = { opacity = it }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("layers" to "图层", "properties" to "属性", "history" to "历史").forEach { (key, label) ->
                    FilterChip(selected = panel == key, onClick = { panel = if (panel == key) "" else key },
                        label = { Text(label) })
                }
            }
            if (panel.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 184.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when (panel) {
                    "layers" -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            TextButton(onClick = {
                                val id = UUID.randomUUID().toString()
                                perform { store.apply("AWEI", "LAYER_CREATE", JSONObject().put("id", id)
                                    .put("name", "绘画图层").put("parentId", if (selectedLayer?.optString("kind") == "group") selected else ""))
                                    .let { store.apply("AWEI", "LAYER_SELECT", JSONObject().put("id", id)) } }
                            }) { Text("＋图层") }
                            TextButton(onClick = { val id = UUID.randomUUID().toString(); perform {
                                store.apply("AWEI", "GROUP_CREATE", JSONObject().put("id", id))
                                store.apply("AWEI", "LAYER_SELECT", JSONObject().put("id", id))
                            } }) { Text("＋组") }
                            TextButton(onClick = { if (selected.isNotBlank()) edit("LAYER_COPY", JSONObject()
                                .put("id", selected).put("newId", UUID.randomUUID().toString())) }, enabled = selected.isNotBlank()) { Text("复制") }
                            TextButton(onClick = { if (selected.isNotBlank()) edit("LAYER_DELETE", JSONObject().put("id", selected)) },
                                enabled = selected.isNotBlank()) { Text("删除") }
                        }
                        for (i in 0 until layers.length()) {
                            val layer = layers.getJSONObject(i)
                            val id = layer.getString("id")
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TextButton(onClick = { edit("LAYER_SELECT", JSONObject().put("id", id)) }, Modifier.weight(1f)) {
                                    Text((if (layer.optString("parentId").isNotBlank()) "  ↳ " else "") +
                                        (if (layer.getString("kind") == "group") "▣ " else "▤ ") + layer.getString("name"),
                                        maxLines = 1, color = if (id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                                TextButton(onClick = { edit("LAYER_VISIBLE", JSONObject().put("id", id)
                                    .put("visible", !layer.getBoolean("visible"))) }) { Text(if (layer.getBoolean("visible")) "◉" else "○") }
                                TextButton(onClick = { edit("LAYER_LOCK", JSONObject().put("id", id)
                                    .put("locked", !layer.getBoolean("locked"))) }) { Text(if (layer.getBoolean("locked")) "锁" else "开") }
                            }
                        }
                    }
                    "properties" -> if (selectedLayer != null) {
                        Text(selectedLayer.getString("name") + " · " + selectedLayer.getString("kind"))
                        val siblings = (0 until layers.length()).filter {
                            layers.getJSONObject(it).optString("parentId") == selectedLayer.optString("parentId")
                        }
                        val siblingPosition = siblings.indexOfFirst { layers.getJSONObject(it).getString("id") == selected }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { layerName = selectedLayer.getString("name"); renameDialog = true }) { Text("重命名") }
                            TextButton(onClick = { edit("LAYER_MOVE", JSONObject().put("id", selected)
                                .put("index", siblings[(siblingPosition + 1).coerceAtMost(siblings.lastIndex)])) },
                                enabled = siblingPosition < siblings.lastIndex) { Text("上移") }
                            TextButton(onClick = { edit("LAYER_MOVE", JSONObject().put("id", selected)
                                .put("index", siblings[(siblingPosition - 1).coerceAtLeast(0)])) },
                                enabled = siblingPosition > 0) { Text("下移") }
                            TextButton(onClick = {
                                transformX = selectedLayer.getDouble("x").toString()
                                transformY = selectedLayer.getDouble("y").toString()
                                transformScale = selectedLayer.getDouble("scale").toString()
                                transformAngle = selectedLayer.getDouble("rotation").toString()
                                transformDialog = true
                            }) { Text("变换…") }
                        }
                        val modes = listOf("normal", "multiply", "screen", "add")
                        TextButton(onClick = { edit("LAYER_BLEND", JSONObject().put("id", selected)
                            .put("blend", modes[(modes.indexOf(selectedLayer.getString("blend")) + 1) % modes.size])) }) {
                            Text("混合：${selectedLayer.getString("blend")}")
                        }
                        Text("图层不透明度 ${(selectedLayer.getDouble("opacity") * 100).toInt()}%")
                        Row {
                            TextButton(onClick = { edit("LAYER_OPACITY", JSONObject().put("id", selected)
                                .put("opacity", (selectedLayer.getDouble("opacity") - 0.1).coerceIn(0.0, 1.0))) }) { Text("－") }
                            TextButton(onClick = { edit("LAYER_OPACITY", JSONObject().put("id", selected)
                                .put("opacity", (selectedLayer.getDouble("opacity") + 0.1).coerceIn(0.0, 1.0))) }) { Text("＋") }
                        }
                        val selection = state.optJSONObject("selection")
                        if (selection != null) Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("COPY" to "复制选区", "DELETE" to "删除", "SCALE" to "放大", "ROTATE" to "旋转").forEach { (action, label) ->
                                TextButton(onClick = { edit("SELECTION_EDIT", JSONObject().put("layerId", selected)
                                    .put("action", action).put("factor", 1.1).put("degrees", 15)) }) { Text(label) }
                            }
                            TextButton(onClick = { edit("SELECTION_CLEAR") }) { Text("取消选区") }
                            TextButton(onClick = { edit("CROP", JSONObject()
                                .put("x", selection.getDouble("x")).put("y", selection.getDouble("y"))
                                .put("width", selection.getDouble("width").toInt())
                                .put("height", selection.getDouble("height").toInt())) },
                                enabled = selection.getDouble("width") >= 64 && selection.getDouble("height") >= 64) { Text("裁剪画布") }
                        }
                    }
                    "history" -> {
                        val operations = current.getJSONArray("operations")
                        for (i in operations.length()-1 downTo maxOf(0, operations.length()-12)) {
                            val op = operations.getJSONObject(i)
                            Text("${if (op.getString("actor") == "LANER") "兰儿" else "阿伟"} · ${op.getString("type")}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (newCanvas) AlertDialog(onDismissRequest = { newCanvas = false }, title = { Text("新建画布") },
        text = { Column {
            OutlinedTextField(canvasWidth, { canvasWidth = it.filter(Char::isDigit).take(4) }, label = { Text("宽度") })
            OutlinedTextField(canvasHeight, { canvasHeight = it.filter(Char::isDigit).take(4) }, label = { Text("高度") })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("透明背景")
                Switch(checked = transparent, onCheckedChange = { transparent = it })
            }
        } }, confirmButton = { TextButton(onClick = {
            newCanvas = false; perform { store.create(canvasWidth.toInt(), canvasHeight.toInt(),
                if (transparent) "#00000000" else "#FFFFFFFF") }
        }) { Text("创建") } }, dismissButton = { TextButton(onClick = { newCanvas = false }) { Text("取消") } })
    if (openDialog) AlertDialog(onDismissRequest = { openDialog = false }, title = { Text("打开工程") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            for (i in 0 until documents.length()) {
                val id = documents.getJSONObject(i).getString("id")
                val item = documents.getJSONObject(i)
                TextButton(onClick = { openDialog = false; perform { store.open(id) } }) {
                    Text("${item.optString("name", "未命名工程")} · ${item.getInt("width")}×${item.getInt("height")}${if (item.getBoolean("saved")) " · 已保存" else " · 草稿"}")
                }
            }
        } }, confirmButton = { TextButton(onClick = { openDialog = false }) { Text("关闭") } })
    if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("图层名称") },
        text = { OutlinedTextField(layerName, { layerName = it.take(100) }) },
        confirmButton = { TextButton(onClick = {
            renameDialog = false
            if (selected.isNotBlank()) perform { store.apply("AWEI", "LAYER_RENAME", JSONObject().put("id", selected).put("name", layerName)) }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } })
    if (projectDialog) AlertDialog(onDismissRequest = { projectDialog = false }, title = { Text("工程名称") },
        text = { OutlinedTextField(projectName, { projectName = it.take(100) }) },
        confirmButton = { TextButton(onClick = { projectDialog = false
            if (projectName.isNotBlank()) perform { store.apply("AWEI", "DOCUMENT_RENAME", JSONObject().put("name", projectName)) }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { projectDialog = false }) { Text("取消") } })
    if (colorDialog) AlertDialog(onDismissRequest = { colorDialog = false }, title = { Text("画笔颜色") },
        text = { Column {
            OutlinedTextField(colorText, { colorText = it.uppercase().take(9) }, label = { Text("#AARRGGBB") })
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("#FF161616", "#FFFFFFFF", "#FFD94343", "#FF387ADB", "#FF55A765", "#FFF1C84A")
                    .forEach { shade -> TextButton(onClick = { colorText = shade }) {
                        Text("●", color = androidx.compose.ui.graphics.Color(Color.parseColor(shade)))
                    } }
            }
        } }, confirmButton = { TextButton(onClick = {
            if (colorText.matches(Regex("#[A-F0-9]{8}"))) { color = colorText; colorDialog = false }
            else Toast.makeText(context, "颜色需为 #AARRGGBB", Toast.LENGTH_SHORT).show()
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { colorDialog = false }) { Text("取消") } })
    if (transformDialog) AlertDialog(onDismissRequest = { transformDialog = false }, title = { Text("图层变换") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(transformX, { transformX = it }, label = { Text("X 位置") })
            OutlinedTextField(transformY, { transformY = it }, label = { Text("Y 位置") })
            OutlinedTextField(transformScale, { transformScale = it }, label = { Text("缩放（0.01–100）") })
            OutlinedTextField(transformAngle, { transformAngle = it }, label = { Text("旋转角度") })
        } }, confirmButton = { TextButton(onClick = {
            val x = transformX.toDoubleOrNull(); val y = transformY.toDoubleOrNull()
            val scale = transformScale.toDoubleOrNull(); val angle = transformAngle.toDoubleOrNull()
            if (x != null && y != null && scale != null && angle != null &&
                x.isFinite() && y.isFinite() && scale in 0.01..100.0 && angle.isFinite()) {
                transformDialog = false
                edit("TRANSFORM", JSONObject().put("id", selected).put("x", x).put("y", y)
                    .put("scale", scale).put("rotation", angle))
            } else Toast.makeText(context, "请输入有效的位置、缩放和角度", Toast.LENGTH_SHORT).show()
        }) { Text("应用") } }, dismissButton = { TextButton(onClick = { transformDialog = false }) { Text("取消") } })
}

private class StudioCanvas(context: Context) : View(context) {
    init { contentDescription = "画室画布，可使用所选工具绘画" }
    var image: Bitmap? = null; set(value) { field = value; invalidate() }
    var layers: JSONArray? = null
    var selectedId: String = ""
    var selection: JSONObject? = null
    var tool: String = "ink"
    var color: String = "#FF161616"
    var brushWidth: Float = 6f
    var opacity: Float = 1f
    var onStroke: (JSONArray) -> Unit = {}
    var onSelection: (JSONObject) -> Unit = {}
    var onMove: (Float, Float) -> Unit = { _, _ -> }
    private var zoom = 1f
    private var angle = 0f
    private var panX = 0f
    private var panY = 0f
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinch = 0f
    private var pinchAngle = 0f
    private var points = JSONArray()
    private val matrix = Matrix()
    private val checkerPaint = Paint().apply {
        val tile = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tile.setPixel(0, 0, Color.rgb(245, 245, 245))
        tile.setPixel(1, 1, Color.rgb(245, 245, 245))
        tile.setPixel(1, 0, Color.rgb(225, 225, 225))
        tile.setPixel(0, 1, Color.rgb(225, 225, 225))
        shader = android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT,
            android.graphics.Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(24f, 24f) })
        }
    }

    private fun layerMatrix(): Matrix {
        val all = layers ?: return Matrix()
        val selected = (0 until all.length()).map { all.getJSONObject(it) }
            .firstOrNull { it.getString("id") == selectedId } ?: return Matrix()
        val chain = mutableListOf(selected)
        var parentId = selected.optString("parentId")
        repeat(all.length()) {
            if (parentId.isBlank()) return@repeat
            val parent = (0 until all.length()).map { all.getJSONObject(it) }
                .firstOrNull { it.getString("id") == parentId } ?: return@repeat
            chain.add(parent)
            parentId = parent.optString("parentId")
        }
        val m = Matrix()
        for (layer in chain) {
            m.postScale(layer.getDouble("scale").toFloat(), layer.getDouble("scale").toFloat())
            m.postRotate(layer.getDouble("rotation").toFloat())
            m.postTranslate(layer.getDouble("x").toFloat(), layer.getDouble("y").toFloat())
        }
        return m
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(38, 38, 42))
        val bitmap = image ?: return
        matrix.reset()
        val fit = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * 0.92f
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postScale(fit * zoom, fit * zoom)
        matrix.postRotate(angle)
        matrix.postTranslate(width / 2f + panX, height / 2f + panY)
        canvas.save(); canvas.concat(matrix)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), checkerPaint)
        canvas.restore()
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        selection?.let { rect ->
            canvas.save(); canvas.concat(matrix)
            val x = rect.getDouble("x").toFloat(); val y = rect.getDouble("y").toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(52, 150, 255); style = Paint.Style.STROKE
                strokeWidth = 2f / (fit * zoom); pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
            }
            canvas.drawRect(x, y, x + rect.getDouble("width").toFloat(),
                y + rect.getDouble("height").toFloat(), paint)
            canvas.restore()
        }
        if (points.length() > 0 && tool !in listOf("pan", "move", "select")) {
            canvas.save(); canvas.concat(matrix); canvas.concat(layerMatrix())
            ArtRenderer.drawStroke(canvas, JSONObject().put("points", points).put("tool", tool)
                .put("color", color).put("width", brushWidth.toDouble()).put("opacity", opacity.toDouble()))
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return true
        if (event.pointerCount >= 2) {
            val dx = event.getX(1) - event.getX(0)
            val dy = event.getY(1) - event.getY(0)
            val distance = hypot(dx, dy)
            val rotation = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (pinch > 0f) {
                zoom = (zoom * distance / pinch).coerceIn(0.1f, 16f)
                angle += rotation - pinchAngle
            }
            pinch = distance; pinchAngle = rotation; points = JSONArray(); invalidate(); return true
        }
        pinch = 0f
        val inverse = Matrix()
        matrix.invert(inverse)
        val xy = floatArrayOf(event.x, event.y)
        inverse.mapPoints(xy)
        val local = floatArrayOf(xy[0], xy[1])
        val inverseLayer = Matrix()
        if (layerMatrix().invert(inverseLayer)) inverseLayer.mapPoints(local)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = xy[0]; startY = xy[1]; lastX = event.x; lastY = event.y
                points = JSONArray()
                if (tool !in listOf("pan", "move", "select")) points.put(JSONArray().put(local[0]).put(local[1]).put(event.pressure.coerceIn(0.1f, 1f)))
            }
            MotionEvent.ACTION_MOVE -> {
                if (tool == "pan") { panX += event.x - lastX; panY += event.y - lastY }
                else if (tool !in listOf("move", "select")) points.put(JSONArray().put(local[0]).put(local[1]).put(event.pressure.coerceIn(0.1f, 1f)))
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                when (tool) {
                    "select" -> onSelection(JSONObject().put("x", minOf(startX, xy[0])).put("y", minOf(startY, xy[1]))
                        .put("width", kotlin.math.abs(xy[0] - startX)).put("height", kotlin.math.abs(xy[1] - startY)))
                    "move" -> onMove(xy[0] - startX, xy[1] - startY)
                    "pan" -> Unit
                    else -> if (points.length() > 0) onStroke(JSONArray(points.toString()))
                }
                points = JSONArray()
            }
            MotionEvent.ACTION_CANCEL -> points = JSONArray()
        }
        invalidate(); return true
    }
}
