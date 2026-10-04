package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

private val layerModes = listOf(
    "normal" to "正常", "multiply" to "正片叠底",
    "screen" to "滤色", "add" to "相加"
)

private data class LayerEntry(val layer: JSONObject, val depth: Int)

/** Krita's controls / stack / operations structure, backed by ArtStore operations. */
@Composable
internal fun StudioLayersPanel(
    documentId: String,
    previews: StudioLayerPreviews,
    state: JSONObject,
    selectedId: String,
    revision: String,
    busy: Boolean,
    store: ArtStore,
    onEdit: (String, JSONObject) -> Unit
) {
    val context = LocalContext.current
    val rawLayers = state.getJSONArray("layers")
    val allLayers = (0 until rawLayers.length()).map { rawLayers.getJSONObject(it) }
    val active = allLayers.firstOrNull { it.getString("id") == selectedId }
    var blendMenu by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    var settingsMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var thumbnailSize by remember { mutableStateOf(32.dp) }
    var propertiesId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val openGroups = remember { mutableStateMapOf<String, Boolean>() }
    var opacityDraft by remember(selectedId, revision) {
        mutableFloatStateOf(active?.optDouble("opacity", 1.0)?.toFloat() ?: 1f)
    }

    // ArtStore stores sibling layers from bottom to top; the docker lists top first.
    val stack = buildList<LayerEntry> {
        val visited = mutableSetOf<String>()
        fun appendChildren(parent: String, depth: Int) {
            allLayers.asReversed().filter { it.optString("parentId") == parent }.forEach { layer ->
                val id = layer.getString("id")
                if (!visited.add(id)) return@forEach
                add(LayerEntry(layer, depth))
                if (layer.optString("kind") == "group" && openGroups[id] != false) {
                    appendChildren(id, depth + 1)
                }
            }
        }
        appendChildren("", 0)
    }.filter { filterText.isBlank() || it.layer.optString("name").contains(filterText, ignoreCase = true) }

    fun addLayer(group: Boolean, vector: Boolean = false) {
        val base = if (group) "图层组" else if (vector) "矢量图层" else "绘画图层"
        var name = base
        var number = 1
        while (allLayers.any { it.optString("name") == name }) {
            name = base + " " + number++
        }
        val parent = if (!group && active?.optString("kind") == "group") selectedId
            else active?.optString("parentId").orEmpty()
        onEdit(if (group) "GROUP_CREATE" else if (vector) "VECTOR_LAYER_CREATE" else "LAYER_CREATE",
            JSONObject().put("id", UUID.randomUUID().toString())
                .put("name", name).put("parentId", parent).put("select", true))
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { blendMenu = true }, enabled = active != null && !busy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (active == null) "无图层"
                        else layerModes.first { it.first == active.getString("blend") }.second,
                        maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = blendMenu, onDismissRequest = { blendMenu = false }) {
                    layerModes.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            blendMenu = false
                            active?.let {
                                onEdit("LAYER_BLEND", JSONObject().put("id", selectedId).put("blend", value))
                            }
                        })
                    }
                }
            }
            LayerAction(Icons.Default.FilterList, "按名称筛选图层",
                modifier = Modifier.size(36.dp), onClick = {
                    filterOpen = !filterOpen
                    if (!filterOpen) filterText = ""
                })
        }
        if (filterOpen) {
            OutlinedTextField(value = filterText, onValueChange = { filterText = it.take(100) },
                singleLine = true, label = { Text("搜索图层") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("不透明度 " + (opacityDraft * 100).toInt() + "%",
                style = MaterialTheme.typography.labelSmall)
            Slider(value = opacityDraft, onValueChange = { opacityDraft = it },
                onValueChangeFinished = {
                    if (active != null && opacityDraft != active.optDouble("opacity").toFloat()) {
                        onEdit("LAYER_OPACITY", JSONObject().put("id", selectedId)
                            .put("opacity", opacityDraft.toDouble()))
                    }
                }, enabled = active != null && !busy,
                modifier = Modifier.weight(1f).padding(start = 5.dp))
            Box {
                LayerAction(Icons.Default.Tune, "图层面板设置",
                    modifier = Modifier.size(36.dp), onClick = { settingsMenu = true })
                DropdownMenu(expanded = settingsMenu, onDismissRequest = { settingsMenu = false }) {
                    listOf(28.dp, 32.dp, 48.dp).forEach { size ->
                        DropdownMenuItem(text = { Text("缩略图 " + size.value.toInt() + "dp") },
                            onClick = { thumbnailSize = size; settingsMenu = false })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            items(stack, key = { it.layer.getString("id") }) { entry ->
                val layer = entry.layer
                val id = layer.getString("id")
                val isGroup = layer.optString("kind") == "group"
                val hasChildren = isGroup && allLayers.any { it.optString("parentId") == id }
                val chosen = selectedId == id
                Row(Modifier.fillMaxWidth().height(maxOf(44.dp, thumbnailSize + 8.dp))
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface)
                    .clickable(enabled = !busy, onClickLabel = "选中" + layer.optString("name")) {
                        if (!chosen) onEdit("LAYER_SELECT", JSONObject().put("id", id))
                    }.padding(start = (entry.depth.coerceAtMost(3) * 8).dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LayerAction(if (layer.optBoolean("visible", true)) Icons.Default.Visibility
                        else Icons.Default.VisibilityOff,
                        if (layer.optBoolean("visible", true)) "隐藏" + layer.optString("name")
                        else "显示" + layer.optString("name"),
                        enabled = !busy, modifier = Modifier.size(30.dp)) {
                        onEdit("LAYER_VISIBLE", JSONObject().put("id", id)
                            .put("visible", !layer.getBoolean("visible")))
                    }
                    if (hasChildren) {
                        LayerAction(if (openGroups[id] == false) Icons.Default.ChevronRight
                            else Icons.Default.ExpandMore, "展开或收起" + layer.optString("name"),
                            modifier = Modifier.size(22.dp)) {
                            openGroups[id] = openGroups[id] == false
                        }
                    } else Spacer(Modifier.width(22.dp))
                    StudioLayerThumbnail(documentId, state, layer, store, previews,
                        Modifier.size(thumbnailSize))
                    Spacer(Modifier.width(5.dp))
                    Column(Modifier.weight(1f)) {
                        Text(layer.optString("name"), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1)
                        if(ArtLayerLabels.value(layer)!=0)Text("● "+ArtLayerLabels.names.getValue(ArtLayerLabels.value(layer)),
                            color=androidx.compose.ui.graphics.Color(ArtLayerLabels.colors.getValue(ArtLayerLabels.value(layer))),style=MaterialTheme.typography.labelSmall)
                        if (layer.optDouble("opacity", 1.0) < 1.0 || layer.optString("blend") != "normal") {
                            Text((layer.optDouble("opacity", 1.0) * 100).toInt().toString() + "% · " +
                                layerModes.first { it.first == layer.getString("blend") }.second,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    LayerAction(if (layer.optBoolean("locked")) Icons.Default.Lock else Icons.Default.LockOpen,
                        if (layer.optBoolean("locked")) "解锁" + layer.optString("name")
                        else "锁定" + layer.optString("name"),
                        enabled = !busy, modifier = Modifier.size(32.dp)) {
                        onEdit("LAYER_LOCK", JSONObject().put("id", id)
                            .put("locked", !layer.getBoolean("locked")))
                    }
                }
            }
            item(key = "canvas_background") {
                Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 34.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(
                        androidx.compose.ui.graphics.Color(Color.parseColor(state.getString("background")))))
                    Spacer(Modifier.width(8.dp))
                    Text("画布背景", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Lock, contentDescription = "画布背景在新建图像时设定",
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                LayerAction(Icons.Default.Add, "添加绘画图层", enabled = !busy) {
                    addLayer(group = false)
                }
                Box {
                    LayerAction(Icons.Default.ArrowDropDown, "添加图层或图层组的选项",
                        enabled = !busy, onClick = { addMenu = true })
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("新建绘画图层") }, onClick = {
                            addMenu = false
                            addLayer(group = false)
                        })
                        DropdownMenuItem(text = { Text("新建矢量图层") }, onClick = {
                            addMenu = false
                            addLayer(group = false, vector = true)
                        })
                        DropdownMenuItem(text = { Text("新建图层组") }, onClick = {
                            addMenu = false
                            addLayer(group = true)
                        })
                    }
                }
                LayerAction(Icons.Default.ContentCopy, "复制当前图层", enabled = active != null && !busy) {
                    onEdit("LAYER_COPY", JSONObject().put("id", selectedId)
                        .put("newId", UUID.randomUUID().toString()).put("select", true))
                }
                val siblingIndex = allLayers.filter {
                    it.optString("parentId") == active?.optString("parentId")
                }.indexOfFirst { it.getString("id") == selectedId }
                val siblingCount = allLayers.count {
                    it.optString("parentId") == active?.optString("parentId")
                }
                LayerAction(Icons.Default.ArrowDownward, "下移图层",
                    enabled = active != null && !busy && siblingIndex > 0) {
                    onEdit("LAYER_MOVE_STEP", JSONObject().put("id", selectedId).put("direction", "down"))
                }
                LayerAction(Icons.Default.ArrowUpward, "上移图层",
                    enabled = active != null && !busy && siblingIndex < siblingCount - 1) {
                    onEdit("LAYER_MOVE_STEP", JSONObject().put("id", selectedId).put("direction", "up"))
                }
                LayerAction(Icons.Default.Tune, "图层属性", enabled = active != null && !busy) {
                    propertiesId = selectedId
                }
            }
            LayerAction(Icons.Default.Delete, "删除当前图层",
                enabled = active != null && !busy && allLayers.size > 1) { deleteId = selectedId }
        }
    }
    val propertiesLayer = allLayers.firstOrNull { it.getString("id") == propertiesId }
    if (propertiesLayer != null) {
        StudioLayerProperties(propertiesLayer, busy,
            onDismiss = { propertiesId = null },
            onSave = { params ->
                propertiesId = null
                onEdit("LAYER_PROPERTIES", params)
            })
    }
    val deleteLayer = allLayers.firstOrNull { it.getString("id") == deleteId }
    if (deleteLayer != null) {
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除图层") },
            text = { Text("删除“" + deleteLayer.optString("name") + "”？可以在操作历史中撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteLayer.getString("id")
                    deleteId = null
                    if (allLayers.any { it.optString("parentId") == id }) {
                        android.widget.Toast.makeText(context, "请先删除或移出组中的图层",
                            android.widget.Toast.LENGTH_SHORT).show()
                    } else onEdit("LAYER_DELETE", JSONObject().put("id", id))
                }) { Text("删除") }
            }, dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text("取消") }
            })
    }
}

@Composable
private fun LayerAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier.size(36.dp),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(modifier.semantics { contentDescription = label }
        .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

@Composable
private fun StudioLayerProperties(
    layer: JSONObject,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (JSONObject) -> Unit
) {
    val id = layer.getString("id")
    var name by remember(id) { mutableStateOf(layer.getString("name")) }
    var opacity by remember(id) { mutableFloatStateOf(layer.getDouble("opacity").toFloat()) }
    var colorLabel by remember(id) {mutableIntStateOf(ArtLayerLabels.value(layer))}
    var blend by remember(id) { mutableStateOf(layer.getString("blend")) }
    var visible by remember(id) { mutableStateOf(layer.getBoolean("visible")) }
    var locked by remember(id) { mutableStateOf(layer.getBoolean("locked")) }
    var blendMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("图层属性") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("类型：" + when (layer.optString("kind")) {
                    "group" -> "图层组"
                    "image" -> "图片图层"
                    "text" -> "可编辑文字图层"
                    "vector" -> "可编辑矢量图层"
                    "colorize" -> "可编辑上色蒙版"
                    else -> "绘画图层"
                })
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    TextButton(onClick = { blendMenu = true }) {
                        Text("混合模式：" + layerModes.first { it.first == blend }.second)
                    }
                    DropdownMenu(expanded = blendMenu, onDismissRequest = { blendMenu = false }) {
                        layerModes.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                blend = value; blendMenu = false
                            })
                        }
                    }
                }
                Text("颜色标签")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ArtLayerLabels.names.forEach {(value,label)->FilterChip(selected=colorLabel==value,enabled=!busy,
                        onClick={colorLabel=value},label={Text(label,color=androidx.compose.ui.graphics.Color(ArtLayerLabels.colors.getValue(value)))})}
                }
                Text("不透明度：" + (opacity * 100).toInt() + "%")
                Slider(value = opacity, onValueChange = { opacity = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visible, onCheckedChange = { visible = it })
                    Text("可见")
                    Checkbox(checked = locked, onCheckedChange = { locked = it })
                    Text("锁定")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.trim().isNotEmpty() && !busy, onClick = {
                onSave(JSONObject().put("id", id).put("name", name.trim())
                    .put("opacity", opacity.toDouble()).put("blend", blend)
                    .put("visible", visible).put("locked", locked).put("colorLabel",colorLabel))
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** Each row previews only its own content; it never caches a full document bitmap. */
@Composable
private fun StudioLayerThumbnail(documentId: String, state: JSONObject, layer: JSONObject,
    store: ArtStore, previews: StudioLayerPreviews, modifier: Modifier) {
    val layerId = layer.getString("id")
    var shown by remember(previews, documentId, layerId) {
        mutableStateOf<StudioFrameCache.Lease<Bitmap>?>(null)
    }
    val view = remember(previews, documentId, layerId) { arrayOfNulls<StudioLayerThumbnailView>(1) }
    LaunchedEffect(previews, documentId, state, layer) {
        var pending: StudioFrameCache.Lease<Bitmap>? = null
        try {
            val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                ArtLayerPreview.snapshot(documentId, state, layer)
            }
            pending = previews.acquire(store, snapshot)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val old = shown
            shown = pending
            // Stop View borrowing the old pixels before releasing the final lease.
            view[0]?.bind(shown?.frame)
            pending = null
            old?.close()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            view[0]?.bind(null)
            shown?.close(); shown = null
            android.util.Log.e("ArtStudio", "Layer preview failed for $layerId", error)
        } finally { pending?.close() }
    }
    DisposableEffect(previews, documentId, layerId) {
        onDispose {
            view[0]?.bind(null)
            shown?.close(); shown = null
        }
    }
    // Replacing a document/layer also replaces the View that borrows its pixels.
    key(previews, documentId, layerId) {
        AndroidView(factory = { StudioLayerThumbnailView(it).also { widget -> view[0] = widget } },
            modifier = modifier, update = {
                view[0] = it
                it.contentDescription = layer.optString("name") + "预览"
                it.bind(shown?.frame)
            })
    }
}

/** Drawing a docker must never replay brush geometry, decode files, or acquire document locks. */
private class StudioLayerThumbnailView(context: android.content.Context) : View(context) {
    private var image: Bitmap? = null
    private val checkerA = Paint().apply { color = Color.rgb(219, 219, 219) }
    private val checkerB = Paint().apply { color = Color.WHITE }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    fun bind(next: Bitmap?) {
        if (image === next) return
        image = next
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val square = 6f * resources.displayMetrics.density
        for (row in 0..(height / square).toInt()) for (column in 0..(width / square).toInt()) {
            canvas.drawRect(column * square, row * square,
                (column + 1) * square, (row + 1) * square,
                if ((row + column) % 2 == 0) checkerA else checkerB)
        }
        val bitmap = image ?: return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null,
            RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), imagePaint)
    }
}
