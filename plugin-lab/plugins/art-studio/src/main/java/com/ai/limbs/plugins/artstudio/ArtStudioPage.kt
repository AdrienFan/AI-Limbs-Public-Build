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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot

internal class ArtStudioPage(private val host: InProcessPluginUiHost) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { Studio(host) } }
        }
}

@Composable
private fun Studio(host: InProcessPluginUiHost) {
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
    var selected by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    val state = store.current()
                    state to ArtRenderer.render(store, state)
                }
                snapshot = pair.first
                image = pair.second
                revision = withContext(Dispatchers.IO) { store.revision() }
            } catch (error: Exception) {
                snapshot = null
                image = null
            }
        }
    }

    fun perform(action: () -> JSONObject) {
        scope.launch {
            busy = true
            try {
                val pair = withContext(Dispatchers.IO) {
                    val state = action()
                    state to ArtRenderer.render(store, state)
                }
                snapshot = pair.first
                image = pair.second
                revision = withContext(Dispatchers.IO) { store.revision() }
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Edit failed", error)
                Toast.makeText(context, error.message ?: "画室操作失败", Toast.LENGTH_LONG).show()
            } finally { busy = false }
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
    LaunchedEffect(store) {
        refresh()
        while (true) {
            delay(1200)
            if (!busy && withContext(Dispatchers.IO) { store.revision() } != revision) refresh()
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("画室", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { newCanvas = true }, enabled = !busy) { Text("新建") }
            OutlinedButton(onClick = { openDialog = true }, enabled = !busy) { Text("打开") }
            OutlinedButton(onClick = { scope.launch(Dispatchers.IO) {
                try { store.save(); withContext(Dispatchers.Main) { Toast.makeText(context, "工程已保存", Toast.LENGTH_SHORT).show() } }
                catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() } }
            } }, enabled = snapshot != null && !busy) { Text("保存") }
            OutlinedButton(onClick = { perform { history(store, "REVERT") } }, enabled = snapshot != null && !busy) { Text("撤销") }
            OutlinedButton(onClick = { perform { history(store, "RESTORE") } }, enabled = snapshot != null && !busy) { Text("重做") }
            OutlinedButton(onClick = { import.launch("image/*") }, enabled = snapshot != null) { Text("导入图片") }
            OutlinedButton(onClick = { scope.launch(Dispatchers.IO) {
                try {
                    val result = ArtRenderer.export(context.applicationContext, store, store.current(), "png", "")
                    withContext(Dispatchers.Main) { Toast.makeText(context, "已导出：${result.getString("name")}", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() } }
            } }, enabled = snapshot != null) { Text("导出 PNG") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ink" to "墨笔", "pencil" to "铅笔", "soft" to "软笔", "spray" to "喷枪",
                "eraser" to "橡皮", "select" to "矩形选区", "move" to "移动图层", "pan" to "移动视图")
                .forEach { (key, label) ->
                    FilterChip(selected = tool == key, onClick = { tool = key }, label = { Text(label) })
                }
        }
        val current = snapshot
        if (current == null) {
            Text("新建画布，阿伟和兰儿就能编辑同一个工程。")
        } else {
            val state = current.getJSONObject("state")
            Text("${state.getInt("width")} × ${state.getInt("height")} · 阿伟：$tool · 兰儿：通过画室能力编辑",
                style = MaterialTheme.typography.bodySmall)
            AndroidView(factory = { ctx -> StudioCanvas(ctx) }, modifier = Modifier.fillMaxWidth().weight(1f), update = { view ->
                view.image = image
                view.tool = tool
                view.color = color
                view.brushWidth = width
                view.opacity = opacity
                view.onStroke = { points ->
                    if (selected.isBlank()) Toast.makeText(context, "先创建并选中绘画图层", Toast.LENGTH_SHORT).show()
                    else perform { store.apply("AWEI", "STROKE_ADD", JSONObject().put("id", UUID.randomUUID().toString())
                        .put("layerId", selected).put("tool", tool).put("color", color)
                        .put("width", width.toDouble()).put("opacity", opacity.toDouble()).put("points", points)) }
                }
                view.onSelection = { rect -> perform { store.apply("AWEI", "SELECTION_CREATE", rect) } }
                view.onMove = { dx, dy -> if (selected.isNotBlank()) {
                    if (state.optJSONObject("selection") != null) perform { store.apply("AWEI", "SELECTION_EDIT",
                        JSONObject().put("layerId", selected).put("action", "MOVE").put("dx", dx).put("dy", dy)) }
                    else {
                        val layer = (0 until state.getJSONArray("layers").length()).map { state.getJSONArray("layers").getJSONObject(it) }
                            .firstOrNull { it.getString("id") == selected }
                        if (layer != null) perform { store.apply("AWEI", "TRANSFORM", JSONObject()
                            .put("id", selected).put("x", layer.getDouble("x") + dx).put("y", layer.getDouble("y") + dy)) }
                    }
                } }
            })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("#FF161616", "#FFFFFFFF", "#FFD94343", "#FF387ADB", "#FF55A765", "#FFF1C84A")
                    .forEach { shade -> FilterChip(selected = color == shade, onClick = { color = shade }, label = { Text("●", color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(shade))) }) }
                Text("笔粗 ${width.toInt()}")
                Slider(value = width, onValueChange = { width = it }, valueRange = 1f..80f, modifier = Modifier.width(130.dp))
                Text("透明度 ${(opacity * 100).toInt()}%")
                Slider(value = opacity, onValueChange = { opacity = it }, modifier = Modifier.width(110.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    val id = UUID.randomUUID().toString()
                    val layers = state.getJSONArray("layers")
                    val parent = (0 until layers.length()).map { layers.getJSONObject(it) }
                        .firstOrNull { it.getString("id") == selected && it.getString("kind") == "group" }
                    selected = id
                    perform { store.apply("AWEI", "LAYER_CREATE", JSONObject().put("id", id)
                        .put("name", "绘画图层").put("parentId", parent?.getString("id") ?: "")) }
                }) { Text("＋图层") }
                OutlinedButton(onClick = { val id = UUID.randomUUID().toString(); selected = id
                    perform { store.apply("AWEI", "GROUP_CREATE", JSONObject().put("id", id)) } }) { Text("＋组") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform { store.apply("AWEI", "LAYER_COPY", JSONObject().put("id", selected).put("newId", UUID.randomUUID().toString())) } }) { Text("复制") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform { store.apply("AWEI", "LAYER_DELETE", JSONObject().put("id", selected)) } }) { Text("删除") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform {
                    val layers = store.current().getJSONObject("state").getJSONArray("layers")
                    val index = (0 until layers.length()).first { layers.getJSONObject(it).getString("id") == selected }
                    store.apply("AWEI", "LAYER_MOVE", JSONObject().put("id", selected)
                        .put("index", (index + 1).coerceAtMost(layers.length() - 1)))
                } }) { Text("上移") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform {
                    val layers = store.current().getJSONObject("state").getJSONArray("layers")
                    val index = (0 until layers.length()).first { layers.getJSONObject(it).getString("id") == selected }
                    store.apply("AWEI", "LAYER_MOVE", JSONObject().put("id", selected)
                        .put("index", (index - 1).coerceAtLeast(0)))
                } }) { Text("下移") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform {
                    val layer = store.current().getJSONObject("state").getJSONArray("layers")
                    val target = (0 until layer.length()).map { layer.getJSONObject(it) }.first { it.getString("id") == selected }
                    store.apply("AWEI", "TRANSFORM", JSONObject().put("id", selected).put("scale", target.getDouble("scale") * 1.1))
                } }) { Text("放大") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform {
                    val layers = store.current().getJSONObject("state").getJSONArray("layers")
                    val target = (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.getString("id") == selected }
                    store.apply("AWEI", "TRANSFORM", JSONObject().put("id", selected).put("rotation", target.getDouble("rotation") + 15.0))
                } }) { Text("旋转") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) {
                    val layers = state.getJSONArray("layers")
                    val layer = (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.getString("id") == selected }
                    layerName = layer.getString("name"); renameDialog = true
                } }) { Text("重命名") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform {
                    val layers = store.current().getJSONObject("state").getJSONArray("layers")
                    val layer = (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.getString("id") == selected }
                    store.apply("AWEI", "LAYER_VISIBLE", JSONObject().put("id", selected).put("visible", !layer.getBoolean("visible")))
                } }) { Text("显隐") }
                OutlinedButton(onClick = { if (selected.isNotBlank()) perform {
                    val layers = store.current().getJSONObject("state").getJSONArray("layers")
                    val layer = (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.getString("id") == selected }
                    store.apply("AWEI", "LAYER_LOCK", JSONObject().put("id", selected).put("locked", !layer.getBoolean("locked")))
                } }) { Text("锁定") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val layers = state.getJSONArray("layers")
                for (i in 0 until layers.length()) {
                    val layer = layers.getJSONObject(i)
                    val id = layer.getString("id")
                    FilterChip(selected = selected == id, onClick = { selected = id },
                        label = { Text((if (layer.getString("kind") == "group") "▣ " else if (layer.optString("parentId").isNotBlank()) "↳ " else "") +
                            layer.getString("name") + if (layer.getBoolean("visible")) "" else "（隐藏）") })
                }
            }
            if (selected.isNotBlank()) {
                val layers = state.getJSONArray("layers")
                val layer = (0 until layers.length()).map { layers.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == selected }
                if (layer != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("图层透明度 ${(layer.getDouble("opacity") * 100).toInt()}%")
                    for ((label, delta) in listOf("－" to -0.1, "＋" to 0.1)) {
                        TextButton(onClick = { perform { store.apply("AWEI", "LAYER_OPACITY", JSONObject()
                            .put("id", selected).put("opacity", (layer.getDouble("opacity") + delta).coerceIn(0.0, 1.0))) } }) {
                            Text(label)
                        }
                    }
                }
            }
            val selection = state.optJSONObject("selection")
            if (selection != null) Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("选区：${selection.getInt("width")}×${selection.getInt("height")}")
                for ((label, action, value) in listOf(
                    Triple("复制内容", "COPY", 0.0), Triple("删除内容", "DELETE", 0.0),
                    Triple("放大内容", "SCALE", 1.1), Triple("旋转内容", "ROTATE", 15.0))) {
                    OutlinedButton(onClick = { if (selected.isNotBlank()) perform {
                        val p = JSONObject().put("layerId", selected).put("action", action)
                        if (action == "SCALE") p.put("factor", value)
                        if (action == "ROTATE") p.put("degrees", value)
                        store.apply("AWEI", "SELECTION_EDIT", p)
                    } }) { Text(label) }
                }
                OutlinedButton(onClick = { perform { store.apply("AWEI", "CROP", JSONObject()
                    .put("x", selection.getDouble("x")).put("y", selection.getDouble("y"))
                    .put("width", selection.getDouble("width").toInt())
                    .put("height", selection.getDouble("height").toInt())) } }) { Text("裁剪画布") }
                TextButton(onClick = { perform { store.apply("AWEI", "SELECTION_CLEAR", JSONObject()) } }) { Text("取消选区") }
            }
            val operations = current.getJSONArray("operations")
            val lastLaner = (operations.length() - 1 downTo 0).map { operations.getJSONObject(it) }
                .firstOrNull { it.getString("actor") == "LANER" && it.getString("type") !in setOf("REVERT", "RESTORE") }
            if (lastLaner != null) Row {
                Text("兰儿最近操作：${lastLaner.getString("type")}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { perform { store.apply("AWEI", "REVERT", JSONObject().put("targetId", lastLaner.getString("id"))) } }) {
                    Text("单独撤销")
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
            newCanvas = false; selected = ""; perform { store.create(canvasWidth.toInt(), canvasHeight.toInt(),
                if (transparent) "#00000000" else "#FFFFFFFF") }
        }) { Text("创建") } }, dismissButton = { TextButton(onClick = { newCanvas = false }) { Text("取消") } })
    if (openDialog) AlertDialog(onDismissRequest = { openDialog = false }, title = { Text("打开工程") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            val docs = remember(openDialog) { store.list() }
            for (i in 0 until docs.length()) {
                val id = docs.getJSONObject(i).getString("id")
                TextButton(onClick = { openDialog = false; selected = ""; perform { store.open(id) } }) { Text(id) }
            }
        } }, confirmButton = { TextButton(onClick = { openDialog = false }) { Text("关闭") } })
    if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("图层名称") },
        text = { OutlinedTextField(layerName, { layerName = it.take(100) }) },
        confirmButton = { TextButton(onClick = {
            renameDialog = false
            if (selected.isNotBlank()) perform { store.apply("AWEI", "LAYER_RENAME", JSONObject().put("id", selected).put("name", layerName)) }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } })
}

private fun history(store: ArtStore, action: String): JSONObject {
    val ops = store.current().getJSONArray("operations")
    val disabled = mutableSetOf<String>()
    for (i in 0 until ops.length()) {
        val op = ops.getJSONObject(i)
        when (op.getString("type")) {
            "REVERT" -> disabled.add(op.getJSONObject("parameters").getString("targetId"))
            "RESTORE" -> disabled.remove(op.getJSONObject("parameters").getString("targetId"))
        }
    }
    val target = (ops.length() - 1 downTo 0).map { ops.getJSONObject(it) }
        .firstOrNull { it.getString("type") !in setOf("REVERT", "RESTORE") &&
            (it.getString("id") in disabled) == (action == "RESTORE") } ?: error("没有可${if (action == "REVERT") "撤销" else "重做"}的操作")
    return store.apply("AWEI", action, JSONObject().put("targetId", target.getString("id")))
}

private class StudioCanvas(context: Context) : View(context) {
    var image: Bitmap? = null; set(value) { field = value; invalidate() }
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
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        if (points.length() > 0 && tool !in listOf("pan", "move", "select")) {
            val path = Path()
            for (i in 0 until points.length()) {
                val point = points.getJSONArray(i)
                if (i == 0) path.moveTo(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
                else path.lineTo(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
            }
            canvas.save(); canvas.concat(matrix)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = brushWidth
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = Color.parseColor(this@StudioCanvas.color)
            })
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = xy[0]; startY = xy[1]; lastX = event.x; lastY = event.y
                points = JSONArray()
                if (tool !in listOf("pan", "move", "select")) points.put(JSONArray().put(xy[0]).put(xy[1]).put(event.pressure.coerceIn(0.1f, 1f)))
            }
            MotionEvent.ACTION_MOVE -> {
                if (tool == "pan") { panX += event.x - lastX; panY += event.y - lastY }
                else if (tool !in listOf("move", "select")) points.put(JSONArray().put(xy[0]).put(xy[1]).put(event.pressure.coerceIn(0.1f, 1f)))
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
