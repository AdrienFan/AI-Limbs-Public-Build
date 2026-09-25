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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.zIndex

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
import kotlin.math.roundToInt

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
                                add(1, 4, "保存(S)", bridge.canSave)
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
                        } else if (title == "编辑(E)") {
                            val anchor = this
                            PopupMenu(pluginContext, anchor).apply {
                                fun add(group: Int, id: Int, label: String, enabled: Boolean = true) {
                                    menu.add(group, id, id, label).isEnabled = enabled && !bridge.busy
                                }
                                val doc = bridge.hasDocument
                                val pixels = bridge.canEditPixels
                                val clip = bridge.hasClipboard
                                add(0, 101, "撤销 " + bridge.undoLabel, bridge.canUndo)
                                add(0, 102, "重做 " + bridge.redoLabel, bridge.canRedo)
                                add(1, 103, "剪切(T)", pixels)
                                add(1, 104, "复制(C)", bridge.canCopyPixels)
                                add(1, 105, "剪切（锐利）(S)", false)
                                add(1, 106, "复制（锐利）(O)", false)
                                add(1, 107, "合并复制(M)", doc)
                                add(1, 108, "复制图层样式", false)
                                add(1, 109, "粘贴(P)", doc && clip)
                                add(1, 110, "粘贴到光标处", doc && clip && bridge.hasCanvasCursor)
                                add(1, 111, "粘贴到活动图层", pixels && clip)
                                add(1, 112, "粘贴为新图像(N)", clip && bridge.clipboardCanNew)
                                add(1, 113, "粘贴为参考图像(E)", false)
                                add(1, 114, "粘贴矢量形状样式", false)
                                add(1, 115, "粘贴图层样式", false)
                                add(2, 116, "清除(L)", pixels)
                                add(3, 117, "填充前景色(F)", pixels)
                                add(3, 118, "填充背景色(W)", pixels)
                                add(3, 119, "填充图案(I)", false)
                                add(3, 120, "填充 - 额外属性", false)
                                add(4, 121, "描边 - 选中形状(K)", false)
                                add(4, 122, "描边 - 选区(T)…", false)
                                add(5, 123, "拾取屏幕颜色(S)", false)
                                add(5, 124, "拾取屏幕颜色（拾取画布实际颜色）(S)", false)
                                if (android.os.Build.VERSION.SDK_INT >= 28) menu.setGroupDividerEnabled(true)
                                setOnMenuItemClickListener { item ->
                                    bridge.onEditCommand?.invoke(item.itemId)
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
    var canSave = false
    var hasRecent = false
    var canUndo = false

    var canRedo = false
    var undoLabel = ""
    var redoLabel = ""
    var canEditPixels = false
    var canCopyPixels = false
    var hasCanvasCursor = false
    var hasClipboard = false
    var clipboardCanNew = false
    var onFileCommand: ((Int) -> Unit)? = null
    var onEditCommand: ((Int) -> Unit)? = null
}

private enum class RightPane { COLOR, LAYERS, BRUSHES }
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
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
    var sampleRadius by remember { mutableIntStateOf(0) }
    var sampleBlend by remember { mutableIntStateOf(100) }
    var sampleMerged by remember { mutableStateOf(true) }
    var samplerOptionsDialog by remember { mutableStateOf(false) }
    var fillTolerance by remember { mutableIntStateOf(0) }
    var fillReferenceAll by remember { mutableStateOf(false) }
    var fillErase by remember { mutableStateOf(false) }
    var fillOptionsDialog by remember { mutableStateOf(false) }
    var mirrorDirection by remember { mutableStateOf("vertical") }
    var mirrorCount by remember { mutableIntStateOf(6) }
    var mirrorRadius by remember { mutableFloatStateOf(80f) }
    var mirrorPlacement by remember { mutableStateOf(false) }
    var mirrorOriginPlacement by remember { mutableStateOf(false) }
    var mirrorCenterX by remember { mutableFloatStateOf(0.5f) }
    var mirrorCenterY by remember { mutableFloatStateOf(0.5f) }
    var mirrorCenters by remember { mutableStateOf(JSONArray()) }
    var mirrorIntervalX by remember { mutableIntStateOf(1024) }
    var mirrorIntervalY by remember { mutableIntStateOf(1024) }
    var mirrorOptionsDialog by remember { mutableStateOf(false) }
    var dynaMass by remember { mutableFloatStateOf(0.5f) }
    var dynaDrag by remember { mutableFloatStateOf(0.15f) }
    var dynaOptionsDialog by remember { mutableStateOf(false) }
    var nibAngle by remember { mutableFloatStateOf(45f) }
    var nibOptionsDialog by remember { mutableStateOf(false) }
    var fillShape by remember { mutableStateOf(false) }
    var bezierContinuous by remember { mutableStateOf(false) }
    var gradientMode by remember { mutableStateOf("linear") }
    var gradientReverse by remember { mutableStateOf(false) }
    var gradientToColor by remember { mutableStateOf(false) }
    var gradientEndInput by remember { mutableStateOf("#FFFFFFFF") }
    var gradientEndColor by remember { mutableStateOf("#FFFFFFFF") }
    var gradientOptionsDialog by remember { mutableStateOf(false) }
    var newCanvas by remember { mutableStateOf(false) }
    var presentationDialog by remember { mutableStateOf(false) }
    var presentationError by remember { mutableStateOf<String?>(null) }
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
    var canvasCursor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var backgroundFillDialog by remember { mutableStateOf(false) }
    var backgroundFillColor by remember { mutableStateOf("#FFFFFFFF") }
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
    var leftDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var rightDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var leftDrawerPinned by rememberSaveable { mutableStateOf(false) }
    var rightDrawerPinned by rememberSaveable { mutableStateOf(false) }
    var activeRightPane by remember { mutableStateOf(RightPane.COLOR) }
    val rightPanePrefs = remember(context) {
        context.getSharedPreferences("art_studio_ui", Context.MODE_PRIVATE)
    }
    var rightPaneOrderNames by rememberSaveable {
        val defaults = RightPane.values().map { it.name }
        val saved = rightPanePrefs.getString("right_pane_order", null)
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val normalized = (saved.filter { it in defaults } +
            defaults.filterNot { it in saved }).distinct()
        mutableStateOf(normalized)
    }
    var draggingRightPane by remember { mutableStateOf<RightPane?>(null) }
    var draggingRightPaneOffset by remember { mutableFloatStateOf(0f) }
    val rightPaneHaptics = LocalHapticFeedback.current
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
        presentationError = null
        scope.launch {
            try {
                // The Host may return a structured denial without throwing; do not
                // dismiss the dialog until it confirms the requested mode.
                val response = JSONObject(host.invokeHostCapability("host.ui.presentation@1", JSONObject()
                    .put("operation", "set_mode")
                    .put("screen_id", ART_SCREEN)
                    .put("mode", mode)
                    .toString()))
                check(response.optBoolean("ok") && response.optString("mode") == mode) {
                    response.optString("error").ifBlank { "宿主未确认页面显示模式：$response" }
                }
                presentationDialog = false
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Page presentation request failed", error)
                presentationError = error.message ?: "无法切换页面显示"
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
        canvasCursor = null
        recentDocs = withContext(Dispatchers.IO) { store.recent() }
    }

    val current = snapshot
    val clipboardSize = remember(current?.optString("id"), current?.optBoolean("hasClipboard"),
        revision) {
        val clip = store.clipboardInfo()
        clip.optInt("width") to clip.optInt("height")
    }
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
    // Observe every Android menu bridge state during composition. SideEffect itself does
    // not register snapshot reads, so states that change after the last document closes
    // must be read here to guarantee a follow-up recomposition and bridge refresh.
    val menuBusy = busy
    val menuHasDocument = current != null
    val menuCanSave = current?.optBoolean("dirty") == true
    val menuHasRecent = recentDocs.length() > 0
    val menuCanUndo = current?.optBoolean("canUndo") == true
    val menuCanRedo = current?.optBoolean("canRedo") == true
    val menuUndoLabel = current?.optString("undoLabel") ?: ""
    val menuRedoLabel = current?.optString("redoLabel") ?: ""
    val menuHasClipboard = clipboardSize.first > 0 && clipboardSize.second > 0
    val menuHasCanvasCursor = canvasCursor != null
    val menuActive = selectedLayer
    val menuCanCopyPixels = current != null && menuActive != null &&
        menuActive.optString("kind") in setOf("paint", "image") &&
        menuActive.optString("parentId").isBlank() && menuActive.optBoolean("visible")
    val menuCanEditPixels = current != null && menuActive != null &&
        menuActive.optString("kind") in setOf("paint", "image") &&
        menuActive.optString("parentId").isBlank() && menuActive.optBoolean("visible") &&
        !menuActive.optBoolean("locked") &&
        menuActive.optDouble("x") == 0.0 && menuActive.optDouble("y") == 0.0 &&
        menuActive.optDouble("scale") == 1.0 && menuActive.optDouble("rotation") == 0.0
    val menuClipboardCanNew = clipboardSize.first in 64..4096 && clipboardSize.second in 64..4096

    SideEffect {
        menuBridge.busy = menuBusy
        menuBridge.hasDocument = menuHasDocument
        menuBridge.canSave = menuCanSave
        menuBridge.hasRecent = menuHasRecent
        menuBridge.canUndo = menuCanUndo
        menuBridge.canRedo = menuCanRedo
        menuBridge.undoLabel = menuUndoLabel
        menuBridge.redoLabel = menuRedoLabel
        menuBridge.hasClipboard = menuHasClipboard
        menuBridge.hasCanvasCursor = menuHasCanvasCursor
        menuBridge.canCopyPixels = menuCanCopyPixels
        menuBridge.canEditPixels = menuCanEditPixels
        menuBridge.clipboardCanNew = menuClipboardCanNew
        menuBridge.onEditCommand = { command ->
            if (!busy) when (command) {
                101 -> perform { store.history("AWEI", redo = false) }
                102 -> perform { store.history("AWEI", redo = true) }
                103 -> perform { store.copyPixels("AWEI", cut = true) }
                104 -> perform { store.copyPixels("AWEI") }
                107 -> perform { store.copyPixels("AWEI", merged = true) }
                109 -> perform { store.pastePixels("AWEI") }
                110 -> canvasCursor?.let { point ->
                    perform { store.pastePixels("AWEI", atX = point.first, atY = point.second) }
                }
                111 -> perform { store.pastePixels("AWEI", intoActive = true) }
                112 -> perform { store.pasteAsNew("AWEI") }
                116 -> perform { store.editPixels("AWEI", "CLEAR") }
                117 -> perform { store.editPixels("AWEI", "FILL", color) }
                118 -> backgroundFillDialog = true
            }
        }
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
                // Tool icons need only a narrow rail; color and layer controls keep a wider panel.
                val leftDrawerWidth = (maxWidth * 0.25f).coerceIn(84.dp, 96.dp)
                val drawerWidth = (maxWidth * 0.68f).coerceAtMost(280.dp)
                // A pinned panel must leave space to draw; both open panels share that space.
                val leftOccupied = if (leftDrawerOpen) leftDrawerWidth else 0.dp
                val rightDrawerWidth = if (rightDrawerPinned || leftDrawerOpen) {
                    drawerWidth.coerceAtMost(
                        (maxWidth - leftOccupied - railWidth * 2 - 112.dp).coerceAtLeast(0.dp))
                } else drawerWidth
                Box(Modifier.fillMaxSize().padding(
                    start = railWidth + if (leftDrawerOpen && leftDrawerPinned) leftDrawerWidth else 0.dp,
                    end = railWidth + if (rightDrawerOpen && rightDrawerPinned) rightDrawerWidth else 0.dp
                ).clipToBounds()) {
                AndroidView(factory = { ctx -> StudioCanvas(ctx).also { canvasRef[0] = it } },
                    modifier = Modifier.fillMaxSize(), update = { view ->
                    view.documentId = current.getString("id")
                    view.image = image
                    view.horizontalFitBias = when {
                        leftDrawerOpen && leftDrawerPinned && !(rightDrawerOpen && rightDrawerPinned) -> -1f
                        rightDrawerOpen && rightDrawerPinned && !(leftDrawerOpen && leftDrawerPinned) -> 1f
                        else -> 0f
                    }
                    view.layers = layers
                    view.selectedId = selected
                    view.selection = state.optJSONObject("selection")
                    view.tool = tool; view.color = color; view.brushWidth = width
                    view.opacity = opacity; view.mirrorDirection = mirrorDirection
                    view.mirrorCount = mirrorCount; view.mirrorRadius = mirrorRadius
                    view.mirrorPlacement = mirrorPlacement; view.mirrorCenters = mirrorCenters
                    view.mirrorIntervalX = mirrorIntervalX; view.mirrorIntervalY = mirrorIntervalY
                    view.mirrorAxisX = state.getInt("width") * mirrorCenterX
                    view.mirrorAxisY = state.getInt("height") * mirrorCenterY
                    view.mirrorOriginPlacement = mirrorOriginPlacement
                    view.onMirrorOrigin = { x, y ->
                        if (x >= 0.0 && x <= state.getInt("width") &&
                            y >= 0.0 && y <= state.getInt("height")) {
                            mirrorCenterX = (x / state.getInt("width")).toFloat()
                            mirrorCenterY = (y / state.getInt("height")).toFloat()
                            mirrorOriginPlacement = false
                        }
                    }
                    view.onMirrorPoint = { x, y ->
                        if (mirrorCenters.length() < 11 && x >= 0.0 && y >= 0.0 &&
                            x <= state.getDouble("width") && y <= state.getDouble("height"))
                            mirrorCenters = JSONArray(mirrorCenters.toString()).put(
                                JSONArray().put(x).put(y))
                        else if (mirrorCenters.length() >= 11)
                            Toast.makeText(context, "最多添加 11 支子画笔", Toast.LENGTH_SHORT).show()
                    }
                    view.dynaMass = dynaMass; view.dynaDrag = dynaDrag
                    view.nibAngle = nibAngle
                    view.fillShape = fillShape
                    view.bezierContinuous = bezierContinuous
                    view.gradientMode = gradientMode; view.gradientReverse = gradientReverse
                    view.gradientEndColor = if (gradientToColor) gradientEndColor else null
                    view.sampleRadius = sampleRadius; view.sampleMerged = sampleMerged
                    view.onSampleCoordinate = { x, y ->
                        scope.launch {
                            try {
                                val pixel = withContext(Dispatchers.IO) {
                                    mutex.withLock {
                                        val source = ArtColorSampler.renderSource(
                                            store, store.current(), selected)
                                        try { ArtColorSampler.sample(source, x, y, sampleRadius) }
                                        finally { source.recycle() }
                                    }
                                }
                                view.onSampleColor(pixel)
                            } catch (error: Exception) {
                                host.logger.e("ArtStudio", "Layer color sampling failed", error)
                                Toast.makeText(context, error.toString(),
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    view.onStroke = { points ->
                        if (selectedLayer?.getString("kind") != "paint")
                            Toast.makeText(context, "请选择绘画图层", Toast.LENGTH_SHORT).show()
                        else {
                            val stroke = JSONObject().put("id", UUID.randomUUID().toString())
                                .put("layerId", selected).put("tool", tool).put("color", color)
                                .put("width", width.toDouble()).put("opacity", opacity.toDouble())
                                .put("points", points)
                            if (tool == "mirror") stroke.put("mirrorDirection", mirrorDirection)
                                .put("mirrorCount", mirrorCount)
                                .put("mirrorRadius", mirrorRadius.toDouble())
                                .put("mirrorSeed", view.mirrorSeed)
                                .put("mirrorCenters", JSONArray(mirrorCenters.toString()))
                                .put("mirrorIntervalX", mirrorIntervalX)
                                .put("mirrorIntervalY", mirrorIntervalY)
                                .put("axisX", state.getInt("width") * mirrorCenterX.toDouble())
                                .put("axisY", state.getInt("height") * mirrorCenterY.toDouble())
                            if (tool in setOf("rectangle", "ellipse", "polygon"))
                                stroke.put("fillShape", fillShape)
                            if (tool == "gradient") {
                                stroke.put("gradientMode", gradientMode)
                                    .put("gradientReverse", gradientReverse)
                                if (gradientToColor) stroke.put("gradientEndColor", gradientEndColor)
                            }
                            if (tool == "calligraphy") stroke.put("nibAngle", nibAngle.toDouble())
                            if (tool == "dyna") stroke.put("mass", dynaMass.toDouble())
                                .put("drag", dynaDrag.toDouble())
                            edit("STROKE_ADD", stroke)
                        }
                    }
                    view.onCursor = { x, y -> canvasCursor = x to y }
                    view.onSampleColor = { pixel ->
                        val sampled = if (sampleBlend == 100) pixel else
                            ArtColorSampler.blend(Color.parseColor(color), pixel, sampleBlend)
                        if (Color.alpha(sampled) == 0)
                            Toast.makeText(context, "透明区域没有可取的颜色", Toast.LENGTH_SHORT).show()
                        else {
                            color = String.format(java.util.Locale.ROOT, "#%08X", sampled)
                            colorHexInput = color
                        }
                    }
                    view.onSelection = { rect -> edit("SELECTION_CREATE", rect) }
                    view.onFill = { x, y ->
                        perform { store.fillContiguous("AWEI", x, y, color,
                            tolerance = fillTolerance, referenceAllLayers = fillReferenceAll,
                            erase = fillErase) }
                    }
                    view.onCrop = { rect ->
                        val x = rect.getDouble("x").toInt().coerceIn(0, state.getInt("width"))
                        val y = rect.getDouble("y").toInt().coerceIn(0, state.getInt("height"))
                        val right = (rect.getDouble("x") + rect.getDouble("width")).toInt()
                            .coerceIn(0, state.getInt("width"))
                        val bottom = (rect.getDouble("y") + rect.getDouble("height")).toInt()
                            .coerceIn(0, state.getInt("height"))
                        if (right - x in 64..4096 && bottom - y in 64..4096)
                            edit("CROP", JSONObject().put("x", x).put("y", y)
                                .put("width", right - x).put("height", bottom - y))
                        else Toast.makeText(context, "裁剪区域至少 64 × 64 像素", Toast.LENGTH_SHORT).show()
                    }
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
                // Intercept taps outside an open drawer before they reach the canvas;
                // the drawer and its handle are drawn above this transparent dismiss area.
                if ((leftDrawerOpen && !leftDrawerPinned) ||
                    (rightDrawerOpen && !rightDrawerPinned)) {
                    Box(Modifier.fillMaxSize().padding(horizontal = railWidth)
                        .clickable(onClickLabel = "收起未固定侧栏") {
                            if (!leftDrawerPinned) leftDrawerOpen = false
                            if (!rightDrawerPinned) rightDrawerOpen = false
                        })
                }
                // Handles remain reachable; pinned drawers may stay open together.
                if (leftDrawerOpen) {
                    Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                        .padding(start = railWidth).width(leftDrawerWidth).fillMaxHeight()
                        .clickable { }, tonalElevation = 3.dp) {
                        Box(Modifier.fillMaxSize()) {
                            val availableTools = listOf(
                                Triple("ink", "自由画笔", "✎"),
                                Triple("pencil", "铅笔", "✏"),
                                Triple("soft", "软笔", "◌"),
                                Triple("spray", "喷枪", "☷"),
                                Triple("eraser", "橡皮擦", "▱"),
                                Triple("mirror", "多重画笔", "⇄"),
                                Triple("dyna", "动态画笔", "⌁"),
                                Triple("calligraphy", "斜头书法笔", "✒"),
                                Triple("line", "直线", "╱"),
                                Triple("rectangle", "矩形", "□"),
                                Triple("ellipse", "椭圆", "○"),
                                Triple("polygon", "多边形", "⬠"),
                                Triple("polyline", "折线", "⌁"),
                                Triple("bezier", "三次贝塞尔曲线", "∿"),
                                Triple("sampler", "颜色取样", "◉"),
                                Triple("fill", "连续区域填充", "▨"),
                                Triple("gradient", "线性渐变", "◩"),
                                Triple("select", "矩形选区", "▣"),
                                Triple("select_ellipse", "椭圆选区", "◯"),
                                Triple("select_polygon", "多边形选区", "⬡"),
                                Triple("select_freehand", "自由套索选区", "〰"),
                                Triple("crop", "裁剪画布", "⛶"),
                                Triple("move", "移动图层", "✥"),
                                Triple("transform", "图层变换", "⤡"),
                                Triple("pan", "平移画布", "✋"),
                                Triple("zoom", "缩放画布", "⌕"),
                                Triple("measure", "测量距离", "⌁")
                            )
                            val selectedToolName = availableTools.firstOrNull { it.first == tool }?.second ?: tool

                            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp, end = 2.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).fillMaxHeight(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Text(selectedToolName,
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth())
                                }
                                Box(Modifier.size(36.dp)
                                    .clickable(onClickLabel = if (leftDrawerPinned)
                                        "取消固定左侧工具栏" else "固定左侧工具栏") {
                                        leftDrawerPinned = !leftDrawerPinned
                                        if (!leftDrawerPinned) leftDrawerOpen = false
                                    }
                                    .semantics {
                                        contentDescription = if (leftDrawerPinned)
                                            "取消固定左侧工具栏" else "固定左侧工具栏"
                                    },
                                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Icon(Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                            .rotate(if (leftDrawerPinned) 0f else -45f),
                                        tint = if (leftDrawerPinned) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Column(Modifier.fillMaxSize().padding(top = 48.dp)
                                .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (tool == "sampler") {
                                    TextButton(onClick = { samplerOptionsDialog = true },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            contentDescription = "取色器选项"
                                        }) {
                                        Text("取色选项", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "fill") {
                                    TextButton(onClick = { fillOptionsDialog = true },
                                        modifier = Modifier.fillMaxWidth()
                                            .semantics { contentDescription = "连续区域填充选项" }) {
                                        Text("填充选项", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "mirror") {
                                    TextButton(onClick = { mirrorOptionsDialog = true },
                                        modifier = Modifier.fillMaxWidth()
                                            .semantics { contentDescription = "多重画笔选项" }) {
                                        Text("多重画笔选项", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "mirror") {
                                    TextButton(onClick = {
                                        mirrorOriginPlacement = !mirrorOriginPlacement
                                        if (mirrorOriginPlacement) mirrorPlacement = false
                                    }, modifier = Modifier.fillMaxWidth().semantics {
                                        contentDescription = if (mirrorOriginPlacement)
                                            "取消移动对称中心" else "点画布移动对称中心"
                                    }) {
                                        Text(if (mirrorOriginPlacement) "取消移动中心" else "移动中心",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "mirror" && mirrorDirection == "copytranslate") {
                                    TextButton(onClick = {
                                        mirrorPlacement = !mirrorPlacement
                                        if (mirrorPlacement) mirrorOriginPlacement = false
                                    },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            contentDescription = if (mirrorPlacement)
                                                "完成子画笔布置" else "点击画布添加子画笔"
                                        }) {
                                        Text(if (mirrorPlacement) "完成布置" else "添加子画笔",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool in setOf("rectangle", "ellipse", "polygon")) {
                                    TextButton(onClick = { fillShape = !fillShape },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            contentDescription = if (fillShape) "取消形状填充" else "填充形状"
                                        }) {
                                        Text(if (fillShape) "填充：前景色" else "填充：无",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "bezier") {
                                    TextButton(onClick = { bezierContinuous = !bezierContinuous },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            contentDescription = if (bezierContinuous)
                                                "关闭连续曲线模式" else "开启连续曲线模式"
                                        }) {
                                        Text(if (bezierContinuous) "连续曲线：双击结束" else "单段曲线：四点完成",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "gradient") {
                                    TextButton(onClick = { gradientOptionsDialog = true },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            contentDescription = "渐变选项"
                                        }) {
                                        Text("渐变选项", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "transform" && selectedLayer != null) {
                                    TextButton(onClick = {
                                        transformX = selectedLayer.getDouble("x").toString()
                                        transformY = selectedLayer.getDouble("y").toString()
                                        transformScale = selectedLayer.getDouble("scale").toString()
                                        transformAngle = selectedLayer.getDouble("rotation").toString()
                                        transformDialog = true
                                    }, modifier = Modifier.fillMaxWidth().semantics {
                                        contentDescription = "设置图层位置缩放旋转"
                                    }) {
                                        Text("变换参数", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "calligraphy") {
                                    TextButton(onClick = { nibOptionsDialog = true },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            contentDescription = "书法笔尖角度"
                                        }) {
                                        Text("笔尖角度", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (tool == "dyna") {
                                    TextButton(onClick = { dynaOptionsDialog = true },
                                        modifier = Modifier.fillMaxWidth()
                                            .semantics { contentDescription = "动态画笔选项" }) {
                                        Text("动态选项", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                availableTools.chunked(2).forEach { pair ->
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly) {
                                        pair.forEach { (id, label, glyph) ->
                                            TooltipBox(
                                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                                tooltip = {
                                                    PlainTooltip {
                                                        Text(label)
                                                    }
                                                },
                                                state = rememberTooltipState(),
                                                enableUserInput = true
                                            ) {
                                                Surface(Modifier.size(40.dp)
                                                    .clickable(onClickLabel = label) {
                                                        tool = id
                                                    },
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                                    color = if (tool == id)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant) {
                                                    Box(Modifier.fillMaxSize(),
                                                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                                                        Text(glyph, style = MaterialTheme.typography.titleMedium,
                                                            modifier = Modifier.semantics {
                                                                contentDescription = label
                                                            })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                    }
                }
                }
                if (rightDrawerOpen) {
                    Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                        .padding(end = railWidth).width(rightDrawerWidth).fillMaxHeight()
                        .clickable { }, tonalElevation = 3.dp) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val paneHeaderHeight = 40.dp
                            val pinHeaderHeight = 40.dp
                            val activePaneBodyHeight =
                                (maxHeight - pinHeaderHeight - paneHeaderHeight).coerceAtLeast(120.dp)
                            val rightAccordionState = rememberLazyListState()
                            val paneOrder = rightPaneOrderNames.mapNotNull { name ->
                                RightPane.values().firstOrNull { it.name == name }
                            }
                            val activeHeaderIndex =
                                paneOrder.indexOf(activeRightPane).coerceAtLeast(0)

                            LaunchedEffect(activeRightPane, rightPaneOrderNames, draggingRightPane) {
                                if (draggingRightPane == null) {
                                    rightAccordionState.animateScrollToItem(activeHeaderIndex)
                                }
                            }

                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().height(pinHeaderHeight)
                                    .padding(start = 10.dp, end = 2.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("视图列表",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f))
                                    Box(Modifier.size(36.dp)
                                        .clickable(onClickLabel = if (rightDrawerPinned)
                                            "取消固定右侧面板" else "固定右侧面板") {
                                            rightDrawerPinned = !rightDrawerPinned
                                            if (!rightDrawerPinned) rightDrawerOpen = false
                                        }
                                        .semantics {
                                            contentDescription = if (rightDrawerPinned)
                                                "取消固定右侧面板" else "固定右侧面板"
                                        },
                                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        Icon(Icons.Default.PushPin,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                                .rotate(if (rightDrawerPinned) 0f else -45f),
                                            tint = if (rightDrawerPinned) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                                    state = rightAccordionState) {
                                    paneOrder.forEach { pane ->
                                        val isActive = activeRightPane == pane
                                        val isDragging = draggingRightPane == pane
                                        val label = when (pane) {
                                            RightPane.COLOR -> "多功能拾色器"
                                            RightPane.LAYERS -> "图层"
                                            RightPane.BRUSHES -> "笔刷预设"
                                        }

                                        val header: @Composable () -> Unit = {
                                            Row(Modifier.fillMaxWidth().height(paneHeaderHeight)
                                                .then(if (isDragging) Modifier
                                                    .offset {
                                                        IntOffset(
                                                            0,
                                                            draggingRightPaneOffset.roundToInt())
                                                    }
                                                    .zIndex(1f) else Modifier)
                                                .background(if (isActive)
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                    else MaterialTheme.colorScheme.surface),
                                                verticalAlignment =
                                                    androidx.compose.ui.Alignment.CenterVertically) {
                                                Box(Modifier.weight(1f).fillMaxHeight()
                                                    .pointerInput(pane) {
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = {
                                                                draggingRightPane = pane
                                                                draggingRightPaneOffset = 0f
                                                                rightPaneHaptics.performHapticFeedback(
                                                                    HapticFeedbackType.LongPress)
                                                            },
                                                            onDragEnd = {
                                                                draggingRightPane = null
                                                                draggingRightPaneOffset = 0f
                                                                rightPanePrefs.edit()
                                                                    .putString("right_pane_order",
                                                                        rightPaneOrderNames
                                                                            .joinToString(","))
                                                                    .apply()
                                                            },
                                                            onDragCancel = {
                                                                draggingRightPane = null
                                                                draggingRightPaneOffset = 0f
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                draggingRightPaneOffset += dragAmount.y
                                                                val threshold =
                                                                    paneHeaderHeight.toPx()
                                                                val names =
                                                                    rightPaneOrderNames.toMutableList()
                                                                var index = names.indexOf(pane.name)
                                                                var changed = false
                                                                while (
                                                                    draggingRightPaneOffset >=
                                                                        threshold &&
                                                                    index >= 0 &&
                                                                    index < names.lastIndex
                                                                ) {
                                                                    val next = names[index + 1]
                                                                    names[index + 1] = names[index]
                                                                    names[index] = next
                                                                    index++
                                                                    draggingRightPaneOffset -= threshold
                                                                    changed = true
                                                                }
                                                                while (
                                                                    draggingRightPaneOffset <=
                                                                        -threshold &&
                                                                    index > 0
                                                                ) {
                                                                    val previous = names[index - 1]
                                                                    names[index - 1] = names[index]
                                                                    names[index] = previous
                                                                    index--
                                                                    draggingRightPaneOffset += threshold
                                                                    changed = true
                                                                }
                                                                if (changed) {
                                                                    rightPaneOrderNames = names
                                                                }
                                                            }
                                                        )
                                                    }
                                                    .clickable(
                                                        onClickLabel = if (isActive)
                                                            "$label 已展开"
                                                        else "展开$label"
                                                    ) {
                                                        if (draggingRightPane == null) {
                                                            activeRightPane = pane
                                                        }
                                                    }
                                                    .padding(start = 10.dp),
                                                    contentAlignment =
                                                        androidx.compose.ui.Alignment.CenterStart) {
                                                    Text(label,
                                                        style =
                                                            MaterialTheme.typography.titleSmall)
                                                }
                                                Box(Modifier.size(36.dp)
                                                    .clickable(onClickLabel =
                                                        "隐藏$label（待视图菜单完成）") { }
                                                    .semantics {
                                                        contentDescription =
                                                            "隐藏$label（待视图菜单完成）"
                                                    },
                                                    contentAlignment =
                                                        androidx.compose.ui.Alignment.Center) {
                                                    Text("×",
                                                        style =
                                                            MaterialTheme.typography.titleMedium,
                                                        color =
                                                            MaterialTheme.colorScheme
                                                                .onSurfaceVariant)
                                                }
                                            }
                                            HorizontalDivider(
                                                color =
                                                    MaterialTheme.colorScheme.outlineVariant)
                                        }

                                        if (isActive && draggingRightPane == null) {
                                            stickyHeader { header() }
                                        } else {
                                            item(key = "header_" + pane.name) { header() }
                                        }

                                        if (isActive && draggingRightPane == null) {
                                            item(key = "body_" + pane.name) {
                                                when (pane) {
                                                    RightPane.COLOR -> {
                                                        Column(Modifier.fillMaxWidth()
                                                            .height(activePaneBodyHeight)
                                                            .verticalScroll(rememberScrollState())
                                                            .padding(
                                                                horizontal = 10.dp,
                                                                vertical = 8.dp),
                                                            verticalArrangement =
                                                                Arrangement.spacedBy(8.dp)) {
                                                            AndroidView(
                                                                factory = { ctx ->
                                                                    StudioColorSelector(ctx)
                                                                },
                                                                modifier = Modifier.fillMaxWidth()
                                                                    .height(285.dp),
                                                                update = { picker ->
                                                                    picker.selectedColor =
                                                                        Color.parseColor(color)
                                                                    picker.onColorSelected =
                                                                        { selected ->
                                                                            color = String.format(
                                                                                java.util.Locale.ROOT,
                                                                                "#%08X", selected)
                                                                            colorHexInput = color
                                                                        }
                                                                })
                                                            Row(verticalAlignment =
                                                                androidx.compose.ui.Alignment
                                                                    .CenterVertically,
                                                                horizontalArrangement =
                                                                    Arrangement.spacedBy(8.dp)) {
                                                                Box(Modifier.size(28.dp).background(
                                                                    androidx.compose.ui.graphics.Color(
                                                                        Color.parseColor(color))))
                                                                Text("当前画笔颜色",
                                                                    style =
                                                                        MaterialTheme.typography
                                                                            .bodySmall)
                                                            }
                                                            OutlinedTextField(
                                                                colorHexInput,
                                                                { input ->
                                                                    colorHexInput =
                                                                        input.uppercase(
                                                                            java.util.Locale.ROOT)
                                                                            .take(9)
                                                                    if (colorHexInput.matches(
                                                                            Regex(
                                                                                "#[0-9A-F]{8}"))) {
                                                                        color = colorHexInput
                                                                    }
                                                                },
                                                                label = { Text("#AARRGGBB") },
                                                                singleLine = true,
                                                                modifier =
                                                                    Modifier.fillMaxWidth())
                                                            Text(
                                                                "色环选择色相，三角区调整饱和度与明度；下方两条色条也可拖动。",
                                                                style =
                                                                    MaterialTheme.typography
                                                                        .bodySmall)
                                                        }
                                                    }
                                                    RightPane.LAYERS -> {
                                                        Box(Modifier.fillMaxWidth()
                                                            .height(activePaneBodyHeight)
                                                            .clipToBounds()) {
                                                            StudioLayersPanel(
                                                                state = state,
                                                                selectedId = selected,
                                                                revision = revision,
                                                                busy = busy,
                                                                store = store,
                                                                onEdit = ::edit)
                                                        }
                                                    }
                                                    RightPane.BRUSHES -> {
                                                        Box(Modifier.fillMaxWidth()
                                                            .height(activePaneBodyHeight),
                                                            contentAlignment =
                                                                androidx.compose.ui.Alignment
                                                                    .Center) {
                                                            Text("笔刷预设待添加",
                                                                style =
                                                                    MaterialTheme.typography
                                                                        .bodySmall)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                    .width(railWidth).fillMaxHeight()
                    .clickable(onClickLabel = if (leftDrawerOpen && leftDrawerPinned)
                        "取消固定并收起左侧工具栏" else if (leftDrawerOpen)
                        "收起左侧工具栏" else "展开左侧工具栏") {
                        if (leftDrawerOpen) {
                            leftDrawerOpen = false
                            leftDrawerPinned = false
                        } else {
                            leftDrawerOpen = true
                            if (!rightDrawerPinned) rightDrawerOpen = false
                        }
                    }, tonalElevation = 3.dp) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(if (leftDrawerOpen) "‹" else "›", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                    .width(railWidth).fillMaxHeight()
                    .clickable(onClickLabel = if (rightDrawerOpen && rightDrawerPinned)
                        "取消固定并收起右侧面板" else if (rightDrawerOpen)
                        "收起右侧面板" else "展开右侧面板") {
                        if (rightDrawerOpen) {
                            rightDrawerOpen = false
                            rightDrawerPinned = false
                        } else {
                            rightDrawerOpen = true
                            if (!leftDrawerPinned) leftDrawerOpen = false
                        }
                    }, tonalElevation = 3.dp) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(if (rightDrawerOpen) "›" else "‹", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Surface(Modifier.fillMaxWidth().height(48.dp),
                color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(
                            onClick = { perform { store.history("AWEI", redo = false) } },
                            modifier = Modifier.widthIn(min = 48.dp)
                                .semantics { contentDescription = "撤销" },
                            enabled = !busy && current.optBoolean("canUndo")
                        ) { Text("↩️") }
                        TextButton(
                            onClick = { perform { store.history("AWEI", redo = true) } },
                            modifier = Modifier.widthIn(min = 48.dp)
                                .semantics { contentDescription = "重做" },
                            enabled = !busy && current.optBoolean("canRedo")
                        ) { Text("↪️") }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
    }
    if (mirrorOptionsDialog) {
        AlertDialog(onDismissRequest = { mirrorOptionsDialog = false },
            title = { Text("多重画笔选项") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mirrorDirection == "vertical",
                        onClick = { mirrorDirection = "vertical"; mirrorPlacement = false },
                        label = { Text("左右镜像") })
                    FilterChip(selected = mirrorDirection == "horizontal",
                        onClick = { mirrorDirection = "horizontal"; mirrorPlacement = false },
                        label = { Text("上下镜像") })
                    FilterChip(selected = mirrorDirection == "quad",
                        onClick = { mirrorDirection = "quad"; mirrorPlacement = false },
                        label = { Text("四象限镜像") })
                    FilterChip(selected = mirrorDirection == "radial",
                        onClick = { mirrorDirection = "radial"; mirrorPlacement = false },
                        label = { Text("旋转对称") })
                    FilterChip(selected = mirrorDirection == "snowflake",
                        onClick = { mirrorDirection = "snowflake"; mirrorPlacement = false },
                        label = { Text("雪花对称") })
                    FilterChip(selected = mirrorDirection == "translate",
                        onClick = { mirrorDirection = "translate"; mirrorPlacement = false },
                        label = { Text("随机平移") })
                    FilterChip(selected = mirrorDirection == "copytranslate",
                        onClick = { mirrorDirection = "copytranslate" },
                        label = { Text("自定子画笔") })
                    FilterChip(selected = mirrorDirection == "interval",
                        onClick = { mirrorDirection = "interval"; mirrorPlacement = false },
                        label = { Text("间隔复制") })
                    if (mirrorDirection == "radial" || mirrorDirection == "snowflake" ||
                        mirrorDirection == "translate") {
                        Text("基础画笔数：$mirrorCount" +
                            if (mirrorDirection == "snowflake") "（共 ${mirrorCount * 4} 支）" else "")
                        Slider(value = mirrorCount.toFloat(),
                            onValueChange = { mirrorCount = it.roundToInt().coerceIn(2, 12) },
                            valueRange = 2f..12f, steps = 9)
                    }
                    if (mirrorDirection == "translate") {
                        Text("平移半径：${mirrorRadius.roundToInt()} px")
                        Slider(value = mirrorRadius,
                            onValueChange = { mirrorRadius = it }, valueRange = 0f..512f)
                    }
                    if (mirrorDirection == "copytranslate") {
                        Text("已添加 ${mirrorCenters.length()} 支子画笔；关闭选项后从左栏进入布置模式，点击画布放置。")
                        TextButton(onClick = { mirrorCenters = JSONArray() }) {
                            Text("清空子画笔")
                        }
                    }
                    if (mirrorDirection == "interval") {
                        Text("横向间隔：$mirrorIntervalX px")
                        Slider(value = mirrorIntervalX.toFloat(),
                            onValueChange = { mirrorIntervalX = it.roundToInt().coerceIn(128, 2048) },
                            valueRange = 128f..2048f)
                        Text("纵向间隔：$mirrorIntervalY px")
                        Slider(value = mirrorIntervalY.toFloat(),
                            onValueChange = { mirrorIntervalY = it.roundToInt().coerceIn(128, 2048) },
                            valueRange = 128f..2048f)
                        Text("横纵网格最多 48 支画笔，超过时需加大间隔。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("对称中心随当前图层移动或变换。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = {
                TextButton(onClick = { mirrorOptionsDialog = false }) { Text("完成") }
            })
    }
    if (samplerOptionsDialog) {
        AlertDialog(onDismissRequest = { samplerOptionsDialog = false },
            title = { Text("取色器选项") },
            text = { Column(Modifier.heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())) {
                FilterChip(selected = sampleMerged, onClick = { sampleMerged = true },
                    label = { Text("合成画布") })
                FilterChip(selected = !sampleMerged, onClick = { sampleMerged = false },
                    label = { Text("当前图层") })
                if (!sampleMerged) Text("当前仅支持可见根绘画层或图像层。",
                    style = MaterialTheme.typography.bodySmall)
                Text("取色半径：$sampleRadius px")
                Slider(value = sampleRadius.toFloat(),
                    onValueChange = { sampleRadius = it.roundToInt().coerceIn(0, 32) },
                    valueRange = 0f..32f, steps = 31)
                Text("混合当前颜色：$sampleBlend%（100% 为纯取样）")
                Slider(value = sampleBlend.toFloat(),
                    onValueChange = { sampleBlend = it.roundToInt().coerceIn(0, 100) },
                    valueRange = 0f..100f)
                Text("半径内的像素按透明度混合；0 px 精确取单个像素。",
                    style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {
                TextButton(onClick = { samplerOptionsDialog = false }) { Text("完成") }
            })
    }
    if (gradientOptionsDialog) {
        AlertDialog(onDismissRequest = { gradientOptionsDialog = false },
            title = { Text("渐变选项") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = gradientMode == "linear",
                    onClick = { gradientMode = "linear" }, label = { Text("线性") })
                FilterChip(selected = gradientMode == "radial",
                    onClick = { gradientMode = "radial" }, label = { Text("径向") })
                FilterChip(selected = gradientMode == "angular",
                    onClick = { gradientMode = "angular" }, label = { Text("角度") })
                FilterChip(selected = !gradientToColor,
                    onClick = { gradientToColor = false }, label = { Text("前景色 → 透明") })
                FilterChip(selected = gradientToColor,
                    onClick = { gradientToColor = true }, label = { Text("前景色 → 终点色") })
                if (gradientToColor) {
                    OutlinedTextField(gradientEndInput, { gradientEndInput = it.take(9) },
                        label = { Text("终点颜色 #AARRGGBB") }, singleLine = true)
                    TextButton(onClick = {
                        if (gradientEndInput.matches(Regex("#[0-9A-Fa-f]{8}")))
                            gradientEndColor = gradientEndInput.uppercase(java.util.Locale.ROOT)
                        else Toast.makeText(context, "请输入 #AARRGGBB 格式颜色",
                            Toast.LENGTH_SHORT).show()
                    }) { Text("应用终点色") }
                    Text("当前终点色：$gradientEndColor", style = MaterialTheme.typography.bodySmall)
                }
                FilterChip(selected = gradientReverse,
                    onClick = { gradientReverse = !gradientReverse },
                    label = { Text("反向颜色") })
                Text("线性／径向用终点定范围，角度用终点定方向；反向会互换颜色。",
                    style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {
                TextButton(onClick = { gradientOptionsDialog = false }) { Text("完成") }
            })
    }
    if (nibOptionsDialog) {
        AlertDialog(onDismissRequest = { nibOptionsDialog = false },
            title = { Text("书法笔尖") },
            text = { Column {
                Text("固定角度：${nibAngle.roundToInt()}°")
                Slider(value = nibAngle, onValueChange = { nibAngle = it },
                    valueRange = 0f..180f)
                Text("笔尖宽度使用当前笔粗；触笔压力控制实际宽度。",
                    style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {
                TextButton(onClick = { nibOptionsDialog = false }) { Text("完成") }
            })
    }
    if (dynaOptionsDialog) {
        AlertDialog(onDismissRequest = { dynaOptionsDialog = false },
            title = { Text("动态画笔选项") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("惯性：${(dynaMass * 100).toInt()}%")
                Slider(value = dynaMass, onValueChange = { dynaMass = it },
                    valueRange = 0f..1f)
                Text("阻力：${(dynaDrag * 100).toInt()}%")
                Slider(value = dynaDrag, onValueChange = { dynaDrag = it },
                    valueRange = 0f..1f)
                Text("按 Krita 动态工具的质量与阻力公式平滑轨迹；同一笔画保留绘制时参数。",
                    style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {
                TextButton(onClick = { dynaOptionsDialog = false }) { Text("完成") }
            })
    }
    if (fillOptionsDialog) {
        AlertDialog(onDismissRequest = { fillOptionsDialog = false },
            title = { Text("连续区域填充选项") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("颜色容差：$fillTolerance%")
                    Slider(value = fillTolerance.toFloat(),
                        onValueChange = { fillTolerance = it.toInt().coerceIn(0, 100) },
                        valueRange = 0f..100f, steps = 99)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("参考所有可见图层", modifier = Modifier.weight(1f))
                        Switch(checked = fillReferenceAll,
                            onCheckedChange = { fillReferenceAll = it })
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("擦除连续区域", modifier = Modifier.weight(1f))
                        Switch(checked = fillErase, onCheckedChange = { fillErase = it })
                    }
                    Text("填色或擦除只修改当前图层；容差按每个 RGBA 通道比较。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = {
                TextButton(onClick = { fillOptionsDialog = false }) { Text("完成") }
            })
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
                    presentationError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
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
    if (backgroundFillDialog) AlertDialog(
        onDismissRequest = { backgroundFillDialog = false },
        title = { Text("填充背景色") },
        text = { OutlinedTextField(backgroundFillColor,
            { backgroundFillColor = it.uppercase(java.util.Locale.ROOT).take(9) },
            label = { Text("背景颜色 #AARRGGBB") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val fill = backgroundFillColor
            backgroundFillDialog = false
            perform { store.editPixels("AWEI", "FILL", fill) }
        }, enabled = backgroundFillColor.matches(Regex("#[0-9A-F]{8}"))) { Text("填充") } },
        dismissButton = { TextButton(onClick = { backgroundFillDialog = false }) { Text("取消") } })
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
            Text("存储地址：" + current.optString("storagePath").ifBlank { "尚未保存" })
            current.optString("externalUri").takeIf { it.isNotBlank() }?.let {
                Text("外部地址：" + it)
            }
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
    var horizontalFitBias: Float = 0f
        set(value) {
            val normalized = value.coerceIn(-1f, 1f)
            if (field != normalized) {
                field = normalized
                invalidate()
            }
        }
    var layers: JSONArray? = null
    var selectedId: String = ""
    var selection: JSONObject? = null
    var tool: String = "ink"
        set(value) {
            if (field != value) {
                field = value
                points = JSONArray()
                pathVertices = JSONArray()
                cropPreview = null
                selectionPreview = null
                measurement = null
                lastPathTap = 0L
                invalidate()
            }
        }
    var sampleRadius: Int = 0
    var sampleMerged: Boolean = true
    var onSampleCoordinate: (Int, Int) -> Unit = { _, _ -> }
    var color: String = "#FF161616"
    var brushWidth: Float = 6f
    var opacity: Float = 1f
    var gradientMode: String = "linear"
    var gradientReverse: Boolean = false
    var gradientEndColor: String? = null
    var fillShape: Boolean = false
    var bezierContinuous: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                points = JSONArray()
                pathVertices = JSONArray()
                lastPathTap = 0L
                invalidate()
            }
        }
    var nibAngle: Float = 45f
    var dynaMass: Float = 0.5f
    var dynaDrag: Float = 0.15f
    var mirrorCount: Int = 6
        set(value) { if (field != value) { field = value; invalidate() } }
    var mirrorRadius: Float = 80f
    var mirrorAxisX: Float = 0f
    var mirrorAxisY: Float = 0f
    var mirrorOriginPlacement: Boolean = false
    var onMirrorOrigin: (Double, Double) -> Unit = { _, _ -> }
    var mirrorIntervalX: Int = 1024
    var mirrorIntervalY: Int = 1024
    var mirrorPlacement: Boolean = false
    var mirrorCenters: JSONArray = JSONArray()
        set(value) { field = value; invalidate() }
    var onMirrorPoint: (Double, Double) -> Unit = { _, _ -> }
    var mirrorSeed: Int = 0
    var mirrorDirection: String = "vertical"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var onStroke: (JSONArray) -> Unit = {}
    var onSelection: (JSONObject) -> Unit = {}
    var onCrop: (JSONObject) -> Unit = {}
    var onSampleColor: (Int) -> Unit = {}
    var onFill: (Int, Int) -> Unit = { _, _ -> }
    var onCursor: (Int, Int) -> Unit = { _, _ -> }
    var onMove: (Float, Float) -> Unit = { _, _ -> }
    private var zoom = 1f
    private var angle = 0f
    private var panX = 0f
    private var panY = 0f
    private var startX = 0f
    private var startY = 0f
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinch = 0f
    private var pinchAngle = 0f
    private var multitouch = false
    private var points = JSONArray()
    private var pathVertices = JSONArray()
    private var cropPreview: JSONObject? = null
    private var selectionPreview: JSONObject? = null
    private var measurement: FloatArray? = null
    private var lastPathTap = 0L
    private var lastPathTapX = 0f
    private var lastPathTapY = 0f
    private val matrix = Matrix()
    private fun shapePoints(x: Float, y: Float): JSONArray = JSONArray()
        .put(JSONArray().put(shapeStartX).put(shapeStartY).put(1f))
        .put(JSONArray().put(x).put(y).put(1f))
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
        val fittedWidth = bitmap.width * fit
        val fittedCenterX = when {
            horizontalFitBias < 0f -> fittedWidth / 2f
            horizontalFitBias > 0f -> width - fittedWidth / 2f
            else -> width / 2f
        }
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postScale(fit * zoom, fit * zoom)
        matrix.postRotate(angle)
        matrix.postTranslate(fittedCenterX + panX, height / 2f + panY)
        canvas.save(); canvas.concat(matrix)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), checkerPaint)
        canvas.restore()
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        if (tool == "mirror") {
            canvas.save(); canvas.concat(matrix); canvas.concat(layerMatrix())
            val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(90, 167, 255)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f / (fit * zoom)
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 6f), 0f)
            }
            if (mirrorDirection == "vertical")
                canvas.drawLine(mirrorAxisX, 0f,
                    mirrorAxisX, bitmap.height.toFloat(), guide)
            else if (mirrorDirection == "horizontal")
                canvas.drawLine(0f, mirrorAxisY,
                    bitmap.width.toFloat(), mirrorAxisY, guide)
            else if (mirrorDirection == "quad") {
                canvas.drawLine(mirrorAxisX, 0f,
                    mirrorAxisX, bitmap.height.toFloat(), guide)
                canvas.drawLine(0f, mirrorAxisY,
                    bitmap.width.toFloat(), mirrorAxisY, guide)
            }
            else if (mirrorDirection == "copytranslate") {
                val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(90, 167, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f / (fit * zoom)
                }
                for (i in 0 until mirrorCenters.length()) {
                    val point = mirrorCenters.getJSONArray(i)
                    canvas.drawCircle(point.getDouble(0).toFloat(),
                        point.getDouble(1).toFloat(), 8f / (fit * zoom), center)
                }
            } else if (mirrorDirection != "translate" && mirrorDirection != "interval") {
                val arms = if (mirrorDirection == "snowflake") mirrorCount * 2 else mirrorCount
                for (arm in 0 until arms) {
                    canvas.save()
                    canvas.rotate(360f * arm / arms, mirrorAxisX, mirrorAxisY)
                    canvas.drawLine(mirrorAxisX, mirrorAxisY,
                        mirrorAxisX + bitmap.width, mirrorAxisY, guide)
                    canvas.restore()
                }
            }
            canvas.restore()
        }
        (selectionPreview ?: selection)?.let { selectedArea ->
            canvas.save(); canvas.concat(matrix)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(52, 150, 255); style = Paint.Style.STROKE
                strokeWidth = 2f / (fit * zoom); pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
            }
            canvas.drawPath(ArtSelection.path(selectedArea), paint)
            canvas.restore()
        }
        if (tool in listOf("select_polygon", "select_freehand") && pathVertices.length() > 0) {
            val vertices = if (points.length() > pathVertices.length()) points else pathVertices
            val outline = Path()
            for (index in 0 until vertices.length()) {
                val point = vertices.getJSONArray(index)
                if (index == 0) outline.moveTo(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
                else outline.lineTo(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
            }
            canvas.save(); canvas.concat(matrix)
            canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(52, 150, 255)
                style = Paint.Style.STROKE
                strokeWidth = 2f / (fit * zoom)
            })
            canvas.restore()
        }
        cropPreview?.let { rect ->
            canvas.save(); canvas.concat(matrix)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(240, 240, 240)
                style = Paint.Style.STROKE
                strokeWidth = 2f / (fit * zoom)
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 5f), 0f)
            }
            canvas.drawRect(rect.getDouble("x").toFloat(), rect.getDouble("y").toFloat(),
                (rect.getDouble("x") + rect.getDouble("width")).toFloat(),
                (rect.getDouble("y") + rect.getDouble("height")).toFloat(), outline)
            canvas.restore()
        }
        measurement?.let { mark ->
            canvas.save(); canvas.concat(matrix)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(238, 238, 238)
                style = Paint.Style.STROKE
                strokeWidth = 2f / (fit * zoom)
            }
            canvas.drawLine(mark[0], mark[1], mark[2], mark[3], paint)
            canvas.restore()
            val endpoint = floatArrayOf(mark[2], mark[3])
            matrix.mapPoints(endpoint)
            val distance = hypot(mark[2] - mark[0], mark[3] - mark[1])
            val degrees = Math.toDegrees(atan2(
                (mark[3] - mark[1]).toDouble(), (mark[2] - mark[0]).toDouble()))
            canvas.drawText("%.1f px  %.1f°".format(java.util.Locale.ROOT, distance, degrees),
                endpoint[0] + 12f, endpoint[1] - 12f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 14f * resources.displayMetrics.scaledDensity
                    setShadowLayer(3f, 0f, 0f, Color.BLACK)
                })
        }
        if (points.length() > 0 && tool !in listOf("pan", "move", "transform", "select", "select_ellipse", "select_polygon", "select_freehand", "sampler", "crop", "fill", "zoom", "measure")) {
            canvas.save(); canvas.concat(matrix); canvas.concat(layerMatrix())
            val preview = JSONObject().put("points", points).put("tool", tool)
                .put("color", color).put("width", brushWidth.toDouble()).put("opacity", opacity.toDouble())
                .put("previewOpen", true)
                .put("previewWidth", bitmap.width).put("previewHeight", bitmap.height)
            if (tool in setOf("rectangle", "ellipse", "polygon"))
                preview.put("fillShape", fillShape)
            if (tool == "gradient") {
                preview.put("gradientMode", gradientMode)
                    .put("gradientReverse", gradientReverse)
                gradientEndColor?.let { preview.put("gradientEndColor", it) }
            }
            if (tool == "calligraphy") preview.put("nibAngle", nibAngle.toDouble())
            if (tool == "dyna") preview.put("mass", dynaMass.toDouble())
                .put("drag", dynaDrag.toDouble())
            if (tool == "mirror") preview.put("mirrorDirection", mirrorDirection)
                .put("mirrorCount", mirrorCount)
                .put("mirrorRadius", mirrorRadius.toDouble())
                .put("mirrorSeed", mirrorSeed)
                .put("mirrorCenters", mirrorCenters)
                .put("mirrorIntervalX", mirrorIntervalX).put("mirrorIntervalY", mirrorIntervalY)
                .put("canvasWidth", bitmap.width).put("canvasHeight", bitmap.height)
                .put("axisX", mirrorAxisX.toDouble()).put("axisY", mirrorAxisY.toDouble())
            ArtRenderer.drawStroke(canvas, preview)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return true
        if (event.pointerCount >= 2) {
            multitouch = true
            val dx = event.getX(1) - event.getX(0)
            val dy = event.getY(1) - event.getY(0)
            val distance = hypot(dx, dy)
            val rotation = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (pinch > 0f) {
                zoom = (zoom * distance / pinch).coerceIn(0.1f, 16f)
                angle += rotation - pinchAngle
            }
            pinch = distance; pinchAngle = rotation
            points = JSONArray(); pathVertices = JSONArray(); lastPathTap = 0L
            selectionPreview = null; cropPreview = null
            invalidate(); return true
        }
        pinch = 0f
        if (multitouch && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                multitouch = false
                points = JSONArray()
                cropPreview = null
                selectionPreview = null
                invalidate()
            }
            return true
        }
        val inverse = Matrix()
        matrix.invert(inverse)
        val xy = floatArrayOf(event.x, event.y)
        inverse.mapPoints(xy)
        val local = floatArrayOf(xy[0], xy[1])
        val inverseLayer = Matrix()
        if (layerMatrix().invert(inverseLayer)) inverseLayer.mapPoints(local)
        if (tool == "mirror" && mirrorOriginPlacement) {
            if (event.actionMasked == MotionEvent.ACTION_UP)
                onMirrorOrigin(local[0].toDouble(), local[1].toDouble())
            invalidate()
            return true
        }
        if (tool == "mirror" && mirrorDirection == "copytranslate" && mirrorPlacement) {
            if (event.actionMasked == MotionEvent.ACTION_UP)
                onMirrorPoint(local[0].toDouble(), local[1].toDouble())
            invalidate()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multitouch = false
                startX = xy[0]; startY = xy[1]; lastX = event.x; lastY = event.y
                points = JSONArray()
                if (tool == "mirror") mirrorSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE)
                shapeStartX = local[0]; shapeStartY = local[1]
                cropPreview = null
                selectionPreview = null
                if (tool == "select_freehand") {
                    pathVertices = JSONArray()
                        .put(JSONArray().put(xy[0]).put(xy[1]).put(1f))
                }
                if (tool !in listOf("pan", "move", "transform", "select", "select_ellipse", "select_polygon", "select_freehand", "sampler", "crop", "fill", "zoom", "measure", "polygon", "polyline", "bezier"))
                    points.put(JSONArray().put(local[0]).put(local[1])
                        .put(event.pressure.coerceIn(0.1f, 1f)))
            }
            MotionEvent.ACTION_MOVE -> {
                if (tool == "pan") { panX += event.x - lastX; panY += event.y - lastY }
                else if (tool == "select_freehand") {
                    if (pathVertices.length() >= 2048) {
                        val reduced = JSONArray()
                        for (index in 0 until pathVertices.length() step 2)
                            reduced.put(pathVertices.getJSONArray(index))
                        pathVertices = reduced
                    }
                    pathVertices.put(JSONArray().put(xy[0]).put(xy[1]).put(1f))
                }
                else if (tool == "dyna") {
                    // Historical touch samples matter to the inertial brush; using only
                    // the latest batched point would make heavy strokes nearly stationary.
                    for (index in 0 until event.historySize) {
                        val sample = floatArrayOf(event.getHistoricalX(index),
                            event.getHistoricalY(index))
                        inverse.mapPoints(sample)
                        inverseLayer.mapPoints(sample)
                        points.put(JSONArray().put(sample[0]).put(sample[1])
                            .put(event.getHistoricalPressure(index).coerceIn(0.1f, 1f)))
                    }
                    points.put(JSONArray().put(local[0]).put(local[1])
                        .put(event.pressure.coerceIn(0.1f, 1f)))
                }
                else if (tool == "measure")
                    measurement = floatArrayOf(startX, startY, xy[0], xy[1])
                else if (tool == "select" || tool == "select_ellipse")
                    selectionPreview = JSONObject()
                        .put("shape", if (tool == "select_ellipse") "ellipse" else "rect")
                        .put("x", minOf(startX, xy[0])).put("y", minOf(startY, xy[1]))
                        .put("width", kotlin.math.abs(xy[0] - startX))
                        .put("height", kotlin.math.abs(xy[1] - startY))
                else if (tool == "crop") cropPreview = JSONObject()
                    .put("x", minOf(startX, xy[0])).put("y", minOf(startY, xy[1]))
                    .put("width", kotlin.math.abs(xy[0] - startX))
                    .put("height", kotlin.math.abs(xy[1] - startY))
                else if (tool in listOf("line", "rectangle", "ellipse", "gradient"))
                    points = shapePoints(local[0], local[1])
                else if (tool == "bezier")
                    points = JSONArray(pathVertices.toString()).put(JSONArray()
                        .put(local[0]).put(local[1]).put(1f))
                else if (tool in listOf("polygon", "polyline", "select_polygon") && pathVertices.length() > 0)
                    points = JSONArray(pathVertices.toString()).apply {
                        val vertex = if (tool == "select_polygon") xy else local
                        put(JSONArray().put(vertex[0]).put(vertex[1]).put(1f))
                    }
                else if (tool !in listOf("move", "transform", "select", "select_ellipse", "select_polygon", "select_freehand", "sampler", "crop", "fill", "zoom", "measure", "polygon", "polyline", "bezier"))
                    points.put(JSONArray().put(local[0]).put(local[1])
                        .put(event.pressure.coerceIn(0.1f, 1f)))
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val bitmap = image
                if (bitmap != null && xy[0] >= 0f && xy[1] >= 0f &&
                    xy[0] <= bitmap.width && xy[1] <= bitmap.height) {
                    onCursor(xy[0].toInt(), xy[1].toInt())
                }
                when (tool) {
                    "select_freehand" -> {
                        val endpoint = JSONArray().put(xy[0]).put(xy[1]).put(1f)
                        if (pathVertices.length() >= 2048)
                            pathVertices.put(pathVertices.length() - 1, endpoint)
                        else pathVertices.put(endpoint)
                        if (pathVertices.length() >= 3) {
                            val vertices = pathVertices
                            val xs = (0 until vertices.length()).map { vertices.getJSONArray(it).getDouble(0) }
                            val ys = (0 until vertices.length()).map { vertices.getJSONArray(it).getDouble(1) }
                            if (xs.maxOrNull()!! > xs.minOrNull()!! &&
                                ys.maxOrNull()!! > ys.minOrNull()!!)
                                onSelection(ArtSelection.fromVertices(vertices))
                        }
                        pathVertices = JSONArray()
                    }
                    "select", "select_ellipse" -> {
                        val selectedArea = JSONObject()
                            .put("shape", if (tool == "select_ellipse") "ellipse" else "rect")
                            .put("x", minOf(startX, xy[0])).put("y", minOf(startY, xy[1]))
                            .put("width", kotlin.math.abs(xy[0] - startX))
                            .put("height", kotlin.math.abs(xy[1] - startY))
                        if (tool == "select" || (selectedArea.getDouble("width") > 0 &&
                            selectedArea.getDouble("height") > 0)) onSelection(selectedArea)
                    }
                    "crop" -> onCrop(JSONObject().put("x", minOf(startX, xy[0]))
                        .put("y", minOf(startY, xy[1])).put("width", kotlin.math.abs(xy[0] - startX))
                        .put("height", kotlin.math.abs(xy[1] - startY)))
                    "move", "transform" -> onMove(xy[0] - startX, xy[1] - startY)
                    "pan" -> Unit
                    "zoom" -> {
                        val previous = zoom
                        zoom = (zoom * 1.5f).coerceAtMost(16f)
                        val factor = zoom / previous
                        panX = event.x - width / 2f - factor * (event.x - width / 2f - panX)
                        panY = event.y - height / 2f - factor * (event.y - height / 2f - panY)
                    }
                    "measure" -> measurement = floatArrayOf(startX, startY, xy[0], xy[1])
                    "fill" -> {
                        val sampled = image
                        if (sampled != null && xy[0] >= 0f && xy[1] >= 0f &&
                            xy[0] < sampled.width && xy[1] < sampled.height)
                            onFill(xy[0].toInt(), xy[1].toInt())
                    }
                    "sampler" -> {
                        val sampled = image
                        if (sampled != null && xy[0] >= 0f && xy[1] >= 0f &&
                            xy[0] < sampled.width && xy[1] < sampled.height)
                            if (sampleMerged)
                                onSampleColor(ArtColorSampler.sample(sampled,
                                    xy[0].toInt(), xy[1].toInt(), sampleRadius))
                            else onSampleCoordinate(xy[0].toInt(), xy[1].toInt())
                    }
                    "dyna" -> {
                        if (points.length() > 0) {
                            val last = points.getJSONArray(points.length() - 1)
                            if (hypot(local[0] - last.getDouble(0).toFloat(),
                                    local[1] - last.getDouble(1).toFloat()) > 0.1f)
                                points.put(JSONArray().put(local[0]).put(local[1])
                                    .put(event.pressure.coerceIn(0.1f, 1f)))
                            onStroke(JSONArray(points.toString()))
                        }
                    }
                    "line", "rectangle", "ellipse", "gradient" -> {
                        points = shapePoints(local[0], local[1])
                        onStroke(JSONArray(points.toString()))
                    }
                    "bezier" -> {
                        // The path is one anchor followed by triples of two handles and an endpoint.
                        // In continuous mode a double tap on the last endpoint finishes the path.
                        val doubleTap = event.eventTime - lastPathTap in 1L..350L &&
                            hypot(event.x - lastPathTapX, event.y - lastPathTapY) <
                                32f * resources.displayMetrics.density
                        val complete = pathVertices.length() >= 4 &&
                            (pathVertices.length() - 1) % 3 == 0
                        if (bezierContinuous && doubleTap && complete) {
                            onStroke(JSONArray(pathVertices.toString()))
                            pathVertices = JSONArray()
                            lastPathTap = 0L
                        } else {
                            if (pathVertices.length() < 1024)
                                pathVertices.put(JSONArray().put(local[0]).put(local[1]).put(1f))
                            if (!bezierContinuous && pathVertices.length() == 4) {
                                onStroke(JSONArray(pathVertices.toString()))
                                pathVertices = JSONArray()
                            }
                            lastPathTap = event.eventTime
                            lastPathTapX = event.x
                            lastPathTapY = event.y
                        }
                        points = JSONArray(pathVertices.toString())
                    }
                    "polygon", "polyline", "select_polygon" -> {
                        val doubleTap = event.eventTime - lastPathTap in 1L..350L &&
                            hypot(event.x - lastPathTapX, event.y - lastPathTapY) < 32f * resources.displayMetrics.density
                        val minimum = if (tool == "polyline") 2 else 3
                        if (doubleTap && pathVertices.length() >= minimum) {
                            if (tool == "select_polygon") {
                                val vertices = pathVertices
                                val xs = (0 until vertices.length()).map { vertices.getJSONArray(it).getDouble(0) }
                                val ys = (0 until vertices.length()).map { vertices.getJSONArray(it).getDouble(1) }
                                if (xs.maxOrNull()!! > xs.minOrNull()!! &&
                                    ys.maxOrNull()!! > ys.minOrNull()!!)
                                    onSelection(ArtSelection.fromVertices(vertices))
                            } else onStroke(JSONArray(pathVertices.toString()))
                            pathVertices = JSONArray()
                        } else {
                            if (tool != "select_polygon" || pathVertices.length() < 2048) {
                                val vertex = if (tool == "select_polygon") xy else local
                                pathVertices.put(JSONArray().put(vertex[0]).put(vertex[1]).put(1f))
                            }
                        }
                        lastPathTap = event.eventTime
                        lastPathTapX = event.x
                        lastPathTapY = event.y
                        points = JSONArray(pathVertices.toString())
                    }
                    else -> if (points.length() > 0) onStroke(JSONArray(points.toString()))
                }
                if (tool !in listOf("polygon", "polyline", "bezier")) points = JSONArray()
                cropPreview = null
                selectionPreview = null
            }
            MotionEvent.ACTION_CANCEL -> {
                multitouch = false
                points = JSONArray()
                pathVertices = JSONArray()
                cropPreview = null
                selectionPreview = null
            }
        }
        invalidate(); return true
    }
}
