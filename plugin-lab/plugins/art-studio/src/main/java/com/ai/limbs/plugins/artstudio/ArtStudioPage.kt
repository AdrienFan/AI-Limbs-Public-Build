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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
        val density = pluginContext.resources.displayMetrics.density
        val menuHeight = (42f * density).toInt()
        val content = ComposeView(pluginContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { Studio(host, bridge) } }
        }
        // Reserve a real strip above the canvas so the menu remains clickable while the canvas redraws.
        root.addView(content, FrameLayout.LayoutParams(-1, -1).apply { topMargin = menuHeight })
        val menuBar = HorizontalScrollView(pluginContext).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(Color.rgb(64, 64, 64))
        }
        val menuRow = LinearLayout(pluginContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val menuItems = mutableListOf<TextView>()
        listOf("文件(F)", "编辑(E)", "视图(V)", "图像(I)", "图层(L)", "选择(S)",
            "滤镜(R)", "工具(T)", "设置(N)", "窗口(W)", "帮助(H)").forEach { title ->
            val item = TextView(pluginContext).apply {
                text = title
                textSize = 15f
                setTextColor(Color.rgb(218, 218, 218))
                gravity = Gravity.CENTER
                minHeight = menuHeight
                setPadding((11f * density).toInt(), 0, (11f * density).toInt(), 0)
                contentDescription = "$title，菜单标题"
                setOnClickListener {
                    val select = !isSelected
                    menuItems.forEach { menuItem ->
                        menuItem.isSelected = false
                        menuItem.setBackgroundColor(Color.TRANSPARENT)
                        menuItem.setTextColor(Color.rgb(218, 218, 218))
                    }
                    if (select) {
                        isSelected = true
                        setBackgroundColor(Color.rgb(85, 91, 99))
                        setTextColor(Color.WHITE)
                        if (title == "文件(F)") {
                            val anchor = this
                            PopupMenu(pluginContext, anchor).apply {
                                fun add(group: Int, id: Int, label: String, enabled: Boolean = true) {
                                    menu.add(group, id, id, label).isEnabled = enabled && !bridge.busy
                                }
                                val doc = bridge.hasDocument
                                add(0, 1, "新建(N)…")
                                add(0, 2, "打开(O)…")
                                add(0, 3, "打开最近图像(R)", bridge.hasRecent)
                                add(1, 4, "保存(S)", doc)
                                add(1, 5, "另存为(A)…", doc)
                                add(2, 6, "会话管理…")
                                add(3, 7, "导入 - 打开为无标题图像(I)…")
                                add(3, 8, "导出(X)…", doc)
                                add(3, 9, "导出 - 更多选项…", doc)
                                // Animation needs a timeline and frame data; no fictitious import/export.
                                add(4, 10, "导入动画 - 逐帧…", false)
                                add(4, 11, "导出动画(R)…", false)
                                add(5, 12, "保存增量版本(V)", doc)
                                add(5, 13, "保存增量备份(B)", doc)
                                add(6, 14, "新建模板 - 基于当前图像(C)…", doc)
                                add(6, 15, "新建图像 - 复制当前图像(F)", doc)
                                add(7, 16, "图像信息(D)", doc)
                                add(8, 17, "关闭(L)", doc)
                                add(8, 18, "全部关闭(C)", false)
                                add(8, 19, "退出(Q)")
                                if (android.os.Build.VERSION.SDK_INT >= 28) menu.setGroupDividerEnabled(true)
                                setOnMenuItemClickListener { item ->
                                    bridge.onFileCommand?.invoke(item.itemId)
                                    true
                                }
                                setOnDismissListener {
                                    anchor.isSelected = false
                                    anchor.setBackgroundColor(Color.TRANSPARENT)
                                    anchor.setTextColor(Color.rgb(218, 218, 218))
                                }
                                show()
                            }
                        }
                    }
                }
            }
            menuItems += item
            menuRow.addView(item, LinearLayout.LayoutParams(-2, -1))
        }
        menuBar.addView(menuRow, FrameLayout.LayoutParams(-2, -1))
        root.addView(menuBar, FrameLayout.LayoutParams(-1, menuHeight, Gravity.TOP))
        return root
    }
}

private class StudioMenuBridge {
    var busy = false
    var hasDocument = false
    var hasRecent = false
    var onFileCommand: ((Int) -> Unit)? = null
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
    var colorHexInput by remember { mutableStateOf(color) }
    var width by remember { mutableFloatStateOf(6f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var newCanvas by remember { mutableStateOf(false) }
    var presentationDialog by remember { mutableStateOf(false) }
    var canvasTab by remember { mutableIntStateOf(0) }
    var canvasWidth by remember { mutableStateOf("1024") }
    var canvasHeight by remember { mutableStateOf("1024") }
    var canvasProjectName by remember { mutableStateOf("未命名工程") }
    var transparent by remember { mutableStateOf(false) }
    var openDialog by remember { mutableStateOf(false) }
    var recentOnly by remember { mutableStateOf(false) }
    var saveAsDialog by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf("") }
    var pendingSaveAsName by remember { mutableStateOf("") }
    var sessionDialog by remember { mutableStateOf(false) }
    var sessionName by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf(JSONArray()) }
    var templateDialog by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    var templateItems by remember { mutableStateOf(JSONArray()) }
    var duplicateDialog by remember { mutableStateOf(false) }
    var duplicateName by remember { mutableStateOf("") }
    var documentInfoDialog by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var advancedExportDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf("png") }
    var cropX by remember { mutableStateOf("0") }
    var cropY by remember { mutableStateOf("0") }
    var cropWidth by remember { mutableStateOf("") }
    var cropHeight by remember { mutableStateOf("") }
    var outputWidth by remember { mutableStateOf("") }
    var outputHeight by remember { mutableStateOf("") }
    var closeDialog by remember { mutableStateOf(false) }
    var exitAfterClose by remember { mutableStateOf(false) }
    var recentDocs by remember { mutableStateOf(JSONArray()) }
    var importingUntitled by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var layerName by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var colorDialog by remember { mutableStateOf(false) }
    var colorText by remember { mutableStateOf(color) }
    var transformDialog by remember { mutableStateOf(false) }
    var transformX by remember { mutableStateOf("0") }
    var transformY by remember { mutableStateOf("0") }
    var transformScale by remember { mutableStateOf("1") }
    var transformAngle by remember { mutableStateOf("0") }
    var leftDrawerOpen by remember { mutableStateOf(false) }
    var rightDrawerOpen by remember { mutableStateOf(false) }
    var layerPanelExpanded by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf("") }
    var awaitingExport by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingOperations by remember { mutableIntStateOf(0) }
    var renderSerial by remember { mutableIntStateOf(0) }
    var revision by remember { mutableStateOf("") }
    var documents by remember { mutableStateOf(JSONArray()) }
    val canvasRef = remember { arrayOfNulls<StudioCanvas>(1) }
    val mutex = remember { Mutex() }
    val selected = snapshot?.optJSONObject("state")?.optString("selectedLayerId") ?: ""

    fun requestPresentationMode(mode: String) {
        presentationDialog = false
        scope.launch {
            try {
                host.invokeHostCapability("host.ui.presentation@1", JSONObject()
                    .put("operation", "set_mode")
                    .put("screen_id", ART_SCREEN)
                    .put("mode", mode)
                    .toString())
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Page presentation request failed", error)
                Toast.makeText(context, error.message ?: "无法切换页面显示", Toast.LENGTH_LONG).show()
            }
        }
    }

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

    val openExternal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val untitled = importingUntitled
        importingUntitled = false
        if (uri != null) perform {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "未命名图像"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(64 * 1024 * 1024 + 1) }
                ?: error("无法读取文件")
            require(bytes.size <= 64 * 1024 * 1024) { "文件超过 64 MB" }
            val zip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
            if (zip) {
                val opened = store.importArchive(bytes)
                if (untitled) store.apply("AWEI", "DOCUMENT_RENAME",
                    JSONObject().put("name", "未命名图像"))
                else {
                    try {
                        context.contentResolver.takePersistableUriPermission(uri,
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        store.linkExternal(opened.getString("id"), uri.toString())
                    } catch (error: SecurityException) {
                        host.logger.i("ArtStudio", "Opened read-only document as an independent draft")
                        scope.launch {
                            Toast.makeText(context, "该文件只读；修改后请使用另存为", Toast.LENGTH_LONG).show()
                        }
                    }
                    store.current()
                }
            } else store.openImage(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                if (untitled) "未命名图像" else name.substringBeforeLast('.').ifBlank { "未命名图像" })
        }
    }
    val saveAsFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) { awaitingExport = false; busy = pendingOperations > 0 }
        else scope.launch {
            try {
                val name = pendingSaveAsName
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val created = store.saveAs(name, activate = false)
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            java.io.File(created.getString("path")).inputStream().use { it.copyTo(output) }
                        } ?: error("无法写入所选工程文件")
                        context.contentResolver.takePersistableUriPermission(uri,
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        store.linkExternal(created.getString("id"), uri.toString())
                        store.open(created.getString("id"))
                    }
                }
                refresh()
                Toast.makeText(context, "已另存为 " + name, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Save As failed", error)
                refresh()
                Toast.makeText(context, error.message ?: "另存为失败", Toast.LENGTH_LONG).show()
            } finally { awaitingExport = false; busy = pendingOperations > 0 }
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
    LaunchedEffect(openDialog, recentOnly) {
        if (openDialog) documents = withContext(Dispatchers.IO) {
            if (recentOnly) store.recent() else store.list()
        }
    }
    LaunchedEffect(sessionDialog) {
        if (sessionDialog) sessions = withContext(Dispatchers.IO) { store.sessions() }
    }
    LaunchedEffect(templateDialog) {
        if (templateDialog) templateItems = withContext(Dispatchers.IO) { store.templates() }
    }
    LaunchedEffect(snapshot?.optString("id")) {
        recentDocs = withContext(Dispatchers.IO) { store.recent() }
    }

    val current = snapshot
    val state = current?.getJSONObject("state")
    val layers = state?.getJSONArray("layers")
    val selectedLayer = (0 until (layers?.length() ?: 0)).map { layers!!.getJSONObject(it) }
        .firstOrNull { it.getString("id") == selected }
    fun edit(type: String, params: JSONObject = JSONObject()) {
        perform { store.apply("AWEI", type, params) }
    }
    fun publish(format: String, options: JSONObject = JSONObject()) {
        if (awaitingExport || busy) return
        awaitingExport = true
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    mutex.withLock { ArtRenderer.export(host.dataDir, store, store.current(), format, "", options) }
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
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val result = store.save()
                        val external = result.optString("externalUri")
                        if (external.isNotBlank()) {
                            context.contentResolver.openOutputStream(android.net.Uri.parse(external), "wt")
                                ?.use { output ->
                                    java.io.File(result.getString("path")).inputStream().use { it.copyTo(output) }
                                } ?: error("无法写入外部工程文件")
                            store.markExternalSynced(result.getString("id"))
                        }
                    }
                }
                refresh()
                Toast.makeText(context, "工程已保存", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, error.message ?: "保存工程失败", Toast.LENGTH_LONG).show()
            } finally { busy = pendingOperations > 0 || awaitingExport }
        }
    }
    fun leaveScreen() {
        var wrapper: Context = context
        repeat(12) {
            if (wrapper is androidx.activity.ComponentActivity) {
                (wrapper as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
                return
            }
            wrapper = (wrapper as? android.content.ContextWrapper)?.baseContext
                ?: error("无法返回画室上级页面")
        }
        error("无法返回画室上级页面")
    }
    fun finishCurrent(save: Boolean, discard: Boolean, exit: Boolean) {
        if (busy) return
        ++renderSerial
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        if (save) {
                            val result = store.save()
                            val external = result.optString("externalUri")
                            if (external.isNotBlank()) {
                                context.contentResolver.openOutputStream(android.net.Uri.parse(external), "wt")
                                    ?.use { output ->
                                        java.io.File(result.getString("path")).inputStream()
                                            .use { it.copyTo(output) }
                                    } ?: error("无法写入外部工程文件")
                                store.markExternalSynced(result.getString("id"))
                            }
                        }
                        if (discard) store.discardCurrent() else store.close()
                    }
                }
                snapshot = null
                image?.recycle()
                image = null
                revision = ""
                recentDocs = withContext(Dispatchers.IO) { store.recent() }
                if (exit) leaveScreen()
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Close failed", error)
                Toast.makeText(context, error.message ?: "无法关闭画室工程", Toast.LENGTH_LONG).show()
            } finally { busy = pendingOperations > 0 || awaitingExport }
        }
    }
    fun requestClose(exit: Boolean) {
        if (current == null) {
            if (exit) leaveScreen()
            return
        }
        if (current.optBoolean("dirty")) {
            exitAfterClose = exit
            closeDialog = true
        } else finishCurrent(save = false, discard = false, exit = exit)
    }
    SideEffect {
        menuBridge.busy = busy
        menuBridge.hasDocument = current != null
        menuBridge.hasRecent = recentDocs.length() > 0
        menuBridge.onFileCommand = { command ->
            if (!busy) when (command) {
                1 -> { canvasTab = 0; newCanvas = true }
                2 -> { recentOnly = false; openDialog = true }
                3 -> { recentOnly = true; openDialog = true }
                4 -> saveProject()
                5 -> { saveAsName = state?.optString("name", "未命名工程") ?: "未命名工程"; saveAsDialog = true }
                6 -> sessionDialog = true
                7 -> { importingUntitled = true; openExternal.launch(arrayOf("*/*")) }
                8 -> exportDialog = true
                9 -> {
                    cropX = "0"; cropY = "0"
                    cropWidth = state?.optInt("width")?.toString() ?: ""
                    cropHeight = state?.optInt("height")?.toString() ?: ""
                    outputWidth = cropWidth; outputHeight = cropHeight
                    advancedExportDialog = true
                }
                12 -> perform { store.saveIncrementalVersion() }
                13 -> perform {
                    val result = store.saveIncrementalBackup()
                    val external = result.optString("externalUri")
                    if (external.isNotBlank()) {
                        context.contentResolver.openOutputStream(android.net.Uri.parse(external), "wt")
                            ?.use { output ->
                                java.io.File(result.getString("path")).inputStream().use { it.copyTo(output) }
                            } ?: error("无法写入外部工程文件")
                        store.markExternalSynced(result.getString("id"))
                    }
                    store.current()
                }
                14 -> { templateName = state?.optString("name", "模板") ?: "模板"; templateDialog = true }
                15 -> { duplicateName = (state?.optString("name", "未命名工程") ?: "未命名工程") + " 副本"
                    duplicateDialog = true }
                16 -> documentInfoDialog = true
                17 -> requestClose(false)
                19 -> requestClose(true)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (current == null) {
            Text("尚未创建画布。", Modifier.padding(top = 72.dp, start = 12.dp))
        } else {
            val state = current.getJSONObject("state")
            val layers = state.getJSONArray("layers")
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val railWidth = 20.dp
                val drawerWidth = (maxWidth * 0.68f).coerceAtMost(280.dp)
                Box(Modifier.fillMaxSize().padding(horizontal = railWidth).clipToBounds()) {
                AndroidView(factory = { ctx -> StudioCanvas(ctx).also { canvasRef[0] = it } },
                    modifier = Modifier.fillMaxSize(), update = { view ->
                    view.documentId = current.getString("id")
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
                // The handles stay visible; only one drawer can cover the canvas at a time.
                if (leftDrawerOpen) {
                    Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                        .padding(start = railWidth).width(drawerWidth).fillMaxHeight()
                        .clickable { }, tonalElevation = 3.dp) {
                        Box(Modifier.fillMaxSize())
                    }
                }
                if (rightDrawerOpen) {
                    Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                        .padding(end = railWidth).width(drawerWidth).fillMaxHeight()
                        .clickable { }, tonalElevation = 3.dp) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            // Keep the three panes independent; compact screens allocate enough
                            // room for actual layer controls, and the title can expand that pane.
                            val compact = maxHeight < 600.dp
                            val colorShare = if (layerPanelExpanded) 0.14f
                                else if (compact) 0.32f else 0.26f
                            val layerShare = if (layerPanelExpanded) 0.78f
                                else if (compact) 0.57f else 0.35f
                            val brushShare = if (layerPanelExpanded) 0.08f
                                else if (compact) 0.11f else 0.39f
                            Column(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxWidth().weight(colorShare)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("多功能拾色器", style = MaterialTheme.typography.titleSmall)
                                    AndroidView(factory = { ctx -> StudioColorSelector(ctx) },
                                        modifier = Modifier.fillMaxWidth().height(285.dp),
                                        update = { picker ->
                                            picker.selectedColor = Color.parseColor(color)
                                            picker.onColorSelected = { selected ->
                                                color = String.format(java.util.Locale.ROOT, "#%08X", selected)
                                                colorHexInput = color
                                            }
                                        })
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.size(28.dp).background(
                                            androidx.compose.ui.graphics.Color(Color.parseColor(color))))
                                        Text("当前画笔颜色", style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedTextField(colorHexInput, { input ->
                                        colorHexInput = input.uppercase(java.util.Locale.ROOT).take(9)
                                        if (colorHexInput.matches(Regex("#[0-9A-F]{8}"))) {
                                            color = colorHexInput
                                        }
                                    }, label = { Text("#AARRGGBB") }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth())
                                    Text("色环选择色相，三角区调整饱和度与明度；下方两条色条也可拖动。",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(Modifier.fillMaxWidth().height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant))
                                Box(Modifier.fillMaxWidth().weight(layerShare)) {
                                    StudioLayersPanel(state = state, selectedId = selected,
                                        revision = revision, busy = busy, store = store,
                                        expanded = layerPanelExpanded,
                                        onExpand = { layerPanelExpanded = !layerPanelExpanded },
                                        onEdit = ::edit)
                                }
                                Spacer(Modifier.fillMaxWidth().height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant))
                                Box(Modifier.fillMaxWidth().weight(brushShare)
                                    .padding(10.dp)) {
                                    Text("笔刷预设", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
                Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                    .width(railWidth).fillMaxHeight()
                    .clickable(onClickLabel = if (leftDrawerOpen) "收起左侧工具栏" else "展开左侧工具栏") {
                        leftDrawerOpen = !leftDrawerOpen
                        if (leftDrawerOpen) rightDrawerOpen = false
                    }, tonalElevation = 3.dp) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(if (leftDrawerOpen) "‹" else "›", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                    .width(railWidth).fillMaxHeight()
                    .clickable(onClickLabel = if (rightDrawerOpen) "收起右侧面板" else "展开右侧面板") {
                        rightDrawerOpen = !rightDrawerOpen
                        if (rightDrawerOpen) leftDrawerOpen = false
                    }, tonalElevation = 3.dp) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(if (rightDrawerOpen) "›" else "‹", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Surface(Modifier.fillMaxWidth().height(48.dp),
                color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { canvasRef[0]?.fitToWindow() }, enabled = image != null) {
                        Text("居中")
                    }
                    TextButton(onClick = { presentationDialog = true }) {
                        Text("全屏")
                    }
                }
            }
        }
    }
    if (presentationDialog) {
        AlertDialog(onDismissRequest = { presentationDialog = false },
            title = { Text("页面显示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { requestPresentationMode("fullscreen_portrait") },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("竖屏全屏")
                    }
                    TextButton(onClick = { requestPresentationMode("fullscreen_landscape") },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("横屏全屏")
                    }
                    TextButton(onClick = { requestPresentationMode("normal") },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("退出全屏")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { presentationDialog = false }) { Text("取消") }
            })
    }
    if (newCanvas) {
        val chosenWidth = canvasWidth.toIntOrNull()
        val chosenHeight = canvasHeight.toIntOrNull()
        val dimensionsValid = chosenWidth != null && chosenHeight != null &&
            chosenWidth in 64..4096 && chosenHeight in 64..4096
        val canCreate = dimensionsValid && canvasProjectName.trim().isNotBlank() && !busy
        AlertDialog(onDismissRequest = { newCanvas = false },
            title = { Text("新建图像") },
            text = { Column(Modifier.heightIn(max = 510.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = canvasTab == 0, onClick = { canvasTab = 0 },
                        label = { Text("尺寸") })
                    FilterChip(selected = canvasTab == 1, onClick = { canvasTab = 1 },
                        label = { Text("内容") })
                }
                if (canvasTab == 0) {
                    Text("图像大小", style = MaterialTheme.typography.titleSmall)
                    Text("预设", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Triple(512, 512, "512 方图"), Triple(1024, 1024, "1024 方图"),
                            Triple(1536, 1024, "横图 3:2"), Triple(1920, 1080, "横图 16:9"),
                            Triple(1080, 1920, "竖图 9:16"), Triple(2480, 3508, "A4 像素尺寸"))
                            .forEach { (w, h, label) ->
                                FilterChip(selected = chosenWidth == w && chosenHeight == h,
                                    onClick = { canvasWidth = w.toString(); canvasHeight = h.toString() },
                                    label = { Text(label) })
                            }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(canvasWidth, { canvasWidth = it.filter(Char::isDigit).take(4) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("宽度 (px)") })
                        OutlinedTextField(canvasHeight, { canvasHeight = it.filter(Char::isDigit).take(4) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("高度 (px)") })
                    }
                    TextButton(onClick = {
                        val oldWidth = canvasWidth
                        canvasWidth = canvasHeight
                        canvasHeight = oldWidth
                    }) { Text("交换宽高") }
                    Text(if (dimensionsValid) "实际图像：" + chosenWidth + " × " + chosenHeight +
                        " 像素 · 约 " + String.format(java.util.Locale.ROOT, "%.1f",
                            chosenWidth!!.toDouble() * chosenHeight!! * 4 / 1048576.0) + " MiB/层"
                        else "宽度和高度均需在 64–4096 像素之间",
                        color = if (dimensionsValid) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Text("当前画室使用 RGB、8 位/通道；尺寸以像素计。分辨率与 ICC 特性文件暂不写入工程。",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("图像内容", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(canvasProjectName, { canvasProjectName = it.take(100) },
                        singleLine = true, label = { Text("工程名称") },
                        modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("透明背景", Modifier.weight(1f))
                        Switch(checked = transparent, onCheckedChange = { transparent = it })
                    }
                    Text("关闭透明背景时使用白色背景；创建后自动选中第一个绘画图层。",
                        style = MaterialTheme.typography.bodySmall)
                }
            } },
            confirmButton = { TextButton(onClick = {
                val widthPx = chosenWidth ?: return@TextButton
                val heightPx = chosenHeight ?: return@TextButton
                if (!canCreate) return@TextButton
                val background = if (transparent) "#00000000" else "#FFFFFFFF"
                val name = canvasProjectName.trim()
                newCanvas = false
                perform { store.create(widthPx, heightPx, background, name) }
            }, enabled = canCreate) { Text("创建") } },
            dismissButton = { TextButton(onClick = { newCanvas = false }) { Text("取消") } })
    }
    if (saveAsDialog) AlertDialog(onDismissRequest = { saveAsDialog = false },
        title = { Text("另存为工程") },
        text = { OutlinedTextField(saveAsName, { saveAsName = it.take(100) },
            label = { Text("新工程名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val name = saveAsName.trim()
            if (name.isNotBlank() && !busy) {
                saveAsDialog = false
                pendingSaveAsName = name
                awaitingExport = true
                busy = true
                saveAsFile.launch(name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".ailart")
            }
        }, enabled = saveAsName.isNotBlank() && !busy) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { saveAsDialog = false }) { Text("取消") } })
    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false },
        title = { Text("导出图像") },
        text = { Column {
            Text("导出当前画布；原工程和图层保持不变。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = exportFormat == "png", onClick = { exportFormat = "png" },
                    label = { Text("PNG") })
                FilterChip(selected = exportFormat == "jpeg", onClick = { exportFormat = "jpeg" },
                    label = { Text("JPEG") })
            }
        } },
        confirmButton = { TextButton(onClick = { exportDialog = false; publish(exportFormat) }) {
            Text("选择保存位置")
        } },
        dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("取消") } })
    if (advancedExportDialog) AlertDialog(onDismissRequest = { advancedExportDialog = false },
        title = { Text("导出 - 更多选项") },
        text = { Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("裁切范围（以画布像素为单位）")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(cropX, { cropX = it }, Modifier.weight(1f), label = { Text("X") })
                OutlinedTextField(cropY, { cropY = it }, Modifier.weight(1f), label = { Text("Y") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(cropWidth, { cropWidth = it }, Modifier.weight(1f), label = { Text("宽") })
                OutlinedTextField(cropHeight, { cropHeight = it }, Modifier.weight(1f), label = { Text("高") })
            }
            Text("输出大小（像素）")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(outputWidth, { outputWidth = it }, Modifier.weight(1f),
                    label = { Text("宽") })
                OutlinedTextField(outputHeight, { outputHeight = it }, Modifier.weight(1f),
                    label = { Text("高") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = exportFormat == "png", onClick = { exportFormat = "png" },
                    label = { Text("PNG") })
                FilterChip(selected = exportFormat == "jpeg", onClick = { exportFormat = "jpeg" },
                    label = { Text("JPEG") })
            }
        } },
        confirmButton = { TextButton(onClick = {
            val x = cropX.toIntOrNull(); val y = cropY.toIntOrNull()
            val w = cropWidth.toIntOrNull(); val h = cropHeight.toIntOrNull()
            val outW = outputWidth.toIntOrNull(); val outH = outputHeight.toIntOrNull()
            val canvasW = state?.optInt("width") ?: 0
            val canvasH = state?.optInt("height") ?: 0
            if (x == null || y == null || w == null || h == null || outW == null || outH == null ||
                x < 0 || y < 0 || w <= 0 || h <= 0 || x.toLong() + w > canvasW ||
                y.toLong() + h > canvasH || outW !in 64..4096 || outH !in 64..4096) {
                Toast.makeText(context, "裁切范围或输出尺寸无效", Toast.LENGTH_LONG).show()
            } else {
                advancedExportDialog = false
                publish(exportFormat, JSONObject().put("x", x).put("y", y)
                    .put("cropWidth", w).put("cropHeight", h)
                    .put("width", outW).put("height", outH))
            }
        }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { advancedExportDialog = false }) { Text("取消") } })
    if (duplicateDialog) AlertDialog(onDismissRequest = { duplicateDialog = false },
        title = { Text("复制当前图像") },
        text = { OutlinedTextField(duplicateName, { duplicateName = it.take(100) },
            label = { Text("新工程名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val name = duplicateName.trim()
            if (name.isNotBlank()) { duplicateDialog = false; perform { store.duplicate(name) } }
        }, enabled = duplicateName.isNotBlank()) { Text("复制") } },
        dismissButton = { TextButton(onClick = { duplicateDialog = false }) { Text("取消") } })
    if (templateDialog) AlertDialog(onDismissRequest = { templateDialog = false },
        title = { Text("画室模板") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(templateName, { templateName = it.take(100) },
                label = { Text("基于当前图像创建模板") }, singleLine = true)
            for (i in 0 until templateItems.length()) {
                val template = templateItems.getJSONObject(i)
                TextButton(onClick = {
                    templateDialog = false
                    perform { store.fromTemplate(template.getString("id")) }
                }) { Text("使用模板：" + template.getString("name")) }
            }
        } },
        confirmButton = { TextButton(onClick = {
            if (templateName.isNotBlank()) {
                templateDialog = false
                perform { store.createTemplate(templateName.trim()) }
            }
        }, enabled = templateName.isNotBlank()) { Text("保存模板") } },
        dismissButton = { TextButton(onClick = { templateDialog = false }) { Text("取消") } })
    if (sessionDialog) AlertDialog(onDismissRequest = { sessionDialog = false },
        title = { Text("会话管理") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("当前画室每次只显示一个工程；会话记录恢复时要打开的工程。")
            OutlinedTextField(sessionName, { sessionName = it.take(100) },
                label = { Text("会话名称") }, singleLine = true)
            for (i in 0 until sessions.length()) {
                val session = sessions.getJSONObject(i)
                Row {
                    TextButton(onClick = {
                        sessionDialog = false
                        perform { store.openSession(session.getString("name")) }
                    }, modifier = Modifier.weight(1f)) { Text("打开：" + session.getString("name")) }
                    TextButton(onClick = {
                        sessionDialog = false
                        perform { store.deleteSession(session.getString("name")) }
                    }) { Text("删除") }
                }
            }
        } },
        confirmButton = { TextButton(onClick = {
            if (sessionName.isNotBlank() && current != null) {
                sessionDialog = false
                perform { store.saveSession(sessionName.trim()) }
            }
        }, enabled = sessionName.isNotBlank() && current != null) { Text("保存会话") } },
        dismissButton = { TextButton(onClick = { sessionDialog = false }) { Text("关闭") } })
    if (documentInfoDialog && current != null) AlertDialog(
        onDismissRequest = { documentInfoDialog = false }, title = { Text("图像信息") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("名称：" + state?.optString("name"))
            Text("尺寸：" + state?.optInt("width") + " × " + state?.optInt("height") + " px")
            Text("图层：" + (state?.optJSONArray("layers")?.length() ?: 0))
            Text("色彩：RGB / 8 位通道")
            Text("背景：" + state?.optString("background"))
            Text("修订：" + current.optInt("revision"))
            Text("状态：" + if (current.optBoolean("dirty")) "未保存" else "已保存")
            Text("工程 ID：" + current.getString("id"))
        } },
        confirmButton = { TextButton(onClick = { documentInfoDialog = false }) { Text("关闭") } })
    if (closeDialog) AlertDialog(onDismissRequest = { closeDialog = false },
        title = { Text("保存当前工程？") },
        text = { Text("当前工程有未保存的修改。") },
        confirmButton = { Row {
            TextButton(onClick = {
                closeDialog = false
                finishCurrent(save = true, discard = false, exit = exitAfterClose)
            }) { Text("保存并关闭") }
            TextButton(onClick = {
                closeDialog = false
                finishCurrent(save = false, discard = true, exit = exitAfterClose)
            }, enabled = current?.optBoolean("externalPending") != true) { Text("舍弃修改") }
        } },
        dismissButton = { TextButton(onClick = { closeDialog = false }) { Text("取消") } })
    if (openDialog) AlertDialog(onDismissRequest = { openDialog = false },
        title = { Text(if (recentOnly) "打开最近图像" else "打开工程") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (!recentOnly) TextButton(onClick = {
                openDialog = false; importingUntitled = false; openExternal.launch(arrayOf("*/*"))
            }) { Text("选择手机上的工程或图片…") }
            if (documents.length() == 0) Text("暂无工程")
            for (i in 0 until documents.length()) {
                val id = documents.getJSONObject(i).getString("id")
                val item = documents.getJSONObject(i)
                TextButton(onClick = { openDialog = false; perform { store.open(id) } }) {
                    Text("${item.optString("name", "未命名工程")} · ${item.getInt("width")}×${item.getInt("height")}${if (item.optBoolean("saved")) " · 已保存" else " · 草稿"}")
                }
            }
        } }, confirmButton = { TextButton(onClick = { openDialog = false }) { Text("关闭") } })
    if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("图层名称") },
        text = { OutlinedTextField(layerName, { layerName = it.take(100) }) },
        confirmButton = { TextButton(onClick = {
            renameDialog = false
            if (selected.isNotBlank()) perform { store.apply("AWEI", "LAYER_RENAME", JSONObject().put("id", selected).put("name", layerName)) }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } })
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
    var documentId: String = ""; set(value) {
        if (field != value) {
            field = value
            fitToWindow()
        }
    }
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
    fun fitToWindow() {
        zoom = 1f
        angle = 0f
        panX = 0f
        panY = 0f
        invalidate()
    }
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
        val fit = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * 0.98f
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
