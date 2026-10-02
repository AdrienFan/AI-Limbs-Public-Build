package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.InputDevice
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.zIndex

import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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
import kotlin.math.pow
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
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val platformConfiguration = LocalViewConfiguration.current
                    val panelConfiguration = remember(platformConfiguration) {
                        object : ViewConfiguration by platformConfiguration {
                            override val doubleTapTimeoutMillis = 300L
                        }
                    }
                    CompositionLocalProvider(LocalViewConfiguration provides panelConfiguration) {
                        Studio(host, bridge)
                    }
                }
            }
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
                        } else if (title == "视图(V)") {
                            val anchor = this
                            PopupMenu(pluginContext, anchor).apply {
                                fun add(group: Int, id: Int, label: String, enabled: Boolean = true,
                                        checked: Boolean? = null) {
                                    menu.add(group, id, id, label).apply {
                                        isEnabled = enabled && !bridge.busy
                                        if (checked != null) {
                                            isCheckable = true
                                            isChecked = checked
                                        }
                                    }
                                }
                                val view = ArtStudioViewControl.state.value
                                val doc = bridge.hasDocument
                                add(0, 201, "隐藏面板模式(S)    Tab", doc, view.panelsHidden)
                                add(0, 202, "全屏模式(U)    Ctrl+Shift+F",
                                    checked = view.presentationMode != "normal")
                                add(0, 248, "独立画布窗口", false)
                                add(0, 203, "四方连续显示(W)    Shift+W", false)
                                menu.addSubMenu(0, 204, 204, "四方连续显示方向").apply {
                                    add(0, 205, 205, "水平和垂直").isEnabled = false
                                    add(0, 206, 206, "仅水平").isEnabled = false
                                    add(0, 207, 207, "仅垂直").isEnabled = false
                                }.item.isEnabled = false
                                add(0, 208, "快速预渲染(I)    Shift+L", false)
                                add(0, 209, "色彩校样    Ctrl+Y", false)
                                add(0, 210, "色域超出警告色    Ctrl+Shift+Y", false)
                                menu.addSubMenu(1, 211, 211, "缩放、旋转、镜像(Z)").apply {
                                    fun action(id: Int, label: String, enabled: Boolean = doc) {
                                        add(1, id, id, label).isEnabled = enabled && !bridge.busy
                                    }
                                    action(212, "放大")
                                    action(213, "缩小")
                                    action(214, "100% 缩放")
                                    action(215, "适合窗口")
                                    action(216, "适合宽度")
                                    action(217, "适合高度")
                                    action(218, "顺时针旋转")
                                    action(219, "逆时针旋转")
                                    action(220, "重置旋转")
                                    action(221, "镜像画布")
                                    action(222, "围绕光标镜像", false)
                                    action(223, "围绕画布镜像", false)
                                    action(224, "重置显示")
                                }
                                add(1, 225, "显示为打印大小", false)
                                add(2, 226, "显示标尺(R)", false)
                                add(2, 227, "显示标尺游标", false)
                                add(2, 228, "显示参考线", false)
                                add(2, 229, "锁定参考线", false)
                                add(2, 230, "显示状态栏(B)", doc, view.statusBarVisible)
                                add(3, 231, "显示网格(G)    Ctrl+Shift+'", doc, view.gridVisible)
                                add(3, 232, "显示像素网格", doc, view.pixelGridVisible)
                                menu.addSubMenu(4, 233, 233, "吸附(S)").apply {
                                    listOf("参考线", "网格", "像素", "正交方向", "节点",
                                        "延长线", "交点", "边界框", "图像边界", "图像中心")
                                        .forEachIndexed { index, label ->
                                            add(4, 234 + index, 234 + index, "吸附到$label").isEnabled = false
                                        }
                                }.item.isEnabled = false
                                add(5, 244, "显示辅助尺(H)", false)
                                add(5, 245, "显示辅助尺预览(A)", false)
                                add(5, 246, "显示参考图像(H)", false)
                                add(5, 249, "色板操作菜单", false)
                                add(6, 247, "刷新画布", doc)
                                if (android.os.Build.VERSION.SDK_INT >= 28) menu.setGroupDividerEnabled(true)
                                setOnMenuItemClickListener { selected ->
                                    bridge.onViewCommand?.invoke(selected.itemId)
                                    true
                                }
                                setOnDismissListener {
                                    anchor.isSelected = false
                                    anchor.setBackgroundColor(Color.TRANSPARENT)
                                    anchor.setTextColor(Color.rgb(218, 218, 218))
                                }
                                show()
                            }
                        } else if (title == "图像(I)") {
                            val anchor = this
                            PopupMenu(pluginContext, anchor).apply {
                                fun add(group: Int, id: Int, label: String, enabled: Boolean = true) {
                                    menu.add(group, id, id, label).isEnabled = enabled && !bridge.busy
                                }
                                val doc = bridge.hasDocument
                                add(0, 301, "图像属性(P)…", doc)
                                add(0, 302, "图像背景色与透明度(I)…", doc)
                                add(0, 303, "转换图像色彩空间(C)…", false)
                                add(1, 304, "裁切至图像大小(T)", false)
                                add(1, 305, "裁切至当前图层大小(L)", false)
                                add(1, 306, "裁切至选区大小(E)", bridge.canCropSelection)
                                add(1, 307, "清理未使用的图像数据", false)
                                menu.addSubMenu(2, 308, 308, "旋转(R)").apply {
                                    add(2, 309, 309, "旋转图像…").isEnabled = false
                                    add(2, 310, 310, "顺时针旋转 90°").isEnabled = false
                                    add(2, 311, 311, "逆时针旋转 90°").isEnabled = false
                                    add(2, 312, 312, "旋转 180°").isEnabled = false
                                }.item.isEnabled = false
                                add(2, 313, "斜切图像(S)…", false)
                                add(3, 314, "翻转图像（水平）(M)", false)
                                add(3, 315, "翻转图像（垂直）(V)", false)
                                add(4, 316, "缩放图像大小(N)…    Ctrl+Alt+I", false)
                                add(4, 317, "偏移图像(O)…", false)
                                add(4, 318, "更改画布大小(E)…    Ctrl+Alt+C", doc)
                                add(5, 319, "切割图像(A)", false)
                                add(5, 320, "小波分解…", false)
                                add(5, 321, "分离图像通道(G)…", false)
                                if (android.os.Build.VERSION.SDK_INT >= 28) menu.setGroupDividerEnabled(true)
                                setOnMenuItemClickListener { selected ->
                                    bridge.onImageCommand?.invoke(selected.itemId)
                                    true
                                }
                                setOnDismissListener {
                                    anchor.isSelected = false
                                    anchor.setBackgroundColor(Color.TRANSPARENT)
                                    anchor.setTextColor(Color.rgb(218, 218, 218))
                                }
                                show()
                            }
                        } else {
                            showStudioRemainingMenu(pluginContext, this, title.substringBefore('('),
                                bridge.menuContext, bridge.busy) { command, captured ->
                                bridge.onRemainingCommand?.invoke(command, captured)
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
    var menuContext = JSONObject().put("document", JSONObject.NULL).put("layerClipboard", false)
        .put("settings", JSONObject().put("selectionVisible",true).put("panelsHidden",false))
    var onRemainingCommand: ((JSONObject, JSONObject) -> Unit)? = null

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
    var canCropSelection = false
    var onFileCommand: ((Int) -> Unit)? = null
    var onEditCommand: ((Int) -> Unit)? = null
    var onViewCommand: ((Int) -> Unit)? = null
    var onImageCommand: ((Int) -> Unit)? = null
}

private enum class RightPane { COLOR, LAYERS, BRUSHES, FOOTPRINTS }
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Studio(host: InProcessPluginUiHost, menuBridge: StudioMenuBridge) {

    val context = LocalContext.current
    val store = remember(host.dataDir) { ArtStore(host.dataDir) }
    val scope = rememberCoroutineScope()
    val viewOptions by ArtStudioViewControl.state.collectAsState()
    val canvasZoom by ArtStudioViewControl.canvasZoom.collectAsState()
    var remainingContext by remember { mutableStateOf(JSONObject()) }
    var remainingSettings by remember { mutableStateOf(JSONObject()) }
    var remainingDialog by remember { mutableStateOf<JSONObject?>(null) }
    var remainingCaptured by remember { mutableStateOf(JSONObject()) }
    var remainingDefaults by remember { mutableStateOf(JSONObject()) }
    var remainingResult by remember { mutableStateOf<JSONObject?>(null) }
    var remainingExportPath by remember { mutableStateOf("") }
    var remainingGroupExports by remember { mutableStateOf(JSONArray()) }
    var remainingImportContext by remember { mutableStateOf<JSONObject?>(null) }
    var remainingImportItem by remember { mutableStateOf<JSONObject?>(null) }
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var referenceBitmaps by remember { mutableStateOf<Map<String,Bitmap>>(emptyMap()) }
    var referenceMultiple by remember { mutableStateOf(false) }
    var colorizeWidth by remember { mutableFloatStateOf(16f) }
    var colorizeErase by remember { mutableStateOf(false) }
    var selectionBezierEditing by remember {mutableStateOf(false)}
    var selectionBezierComponent by remember {mutableIntStateOf(0)}
    var selectionBezierNode by remember {mutableIntStateOf(0)}
    var selectionBezierMode by remember {mutableStateOf("replace")}
    var selectionBezierSmooth by remember {mutableStateOf(false)}
    var contiguousSettings by remember {mutableStateOf(ArtColorSelection.defaults())}
    var similarSettings by remember {mutableStateOf(ArtColorSelection.defaults())}
    var magneticSettings by remember {mutableStateOf(ArtMagneticSelection.defaults())}
    var magneticImage by remember {mutableStateOf<ArtMagneticSelection.Image?>(null)}
    var magneticDraft by remember {mutableStateOf(false)}
    var comicPanelSettings by remember {mutableStateOf(ArtComicPanels.defaults())}
    var encloseFillSettings by remember {mutableStateOf(ArtEncloseFill.defaults())}
    var smartPatchSettings by remember { mutableStateOf(ArtSmartPatch.info().getJSONObject("defaults")) }
    var assistantAdding by remember { mutableStateOf(true) }
    var assistantType by remember { mutableStateOf("ruler") }
    var referenceImportContext by remember { mutableStateOf<JSONObject?>(null) }
    var textDialog by remember { mutableStateOf<JSONObject?>(null) }
    var textFonts by remember { mutableStateOf(JSONArray()) }
    val toolWindow by ArtStudioToolOptionsControl.state.collectAsState()
    val tool = toolWindow.activeTool
    var shapeMultiple by remember { mutableStateOf(false) }
    var vectorNibAngle by remember { mutableFloatStateOf(45f) }
    var vectorFixation by remember { mutableFloatStateOf(1f) }
    var vectorThinning by remember { mutableFloatStateOf(0f) }
    var vectorSmoothing by remember { mutableFloatStateOf(0f) }
    var vectorPressure by remember { mutableStateOf(true) }
    var vectorCap by remember { mutableStateOf("round") }
    var freehandMode by remember { mutableStateOf("curve") }
    var freehandPrecision by remember { mutableFloatStateOf(2f) }
    var freehandClosed by remember { mutableStateOf(false) }
    var bezierEditing by remember { mutableStateOf(false) }
    var bezierNode by remember { mutableIntStateOf(0) }
    var bezierNodeType by remember { mutableStateOf("symmetric") }
    var bezierClosed by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf("#FF161616") }
    var colorHexInput by remember { mutableStateOf(color) }
    var width by remember { mutableFloatStateOf(6f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var sampleRadius by remember { mutableIntStateOf(0) }
    var sampleBlend by remember { mutableIntStateOf(100) }
    var sampleMerged by remember { mutableStateOf(true) }
    var fillTolerance by remember { mutableIntStateOf(0) }
    var fillReferenceAll by remember { mutableStateOf(false) }
    var fillErase by remember { mutableStateOf(false) }
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
    var dynaMass by remember { mutableFloatStateOf(0.5f) }
    var dynaDrag by remember { mutableFloatStateOf(0.15f) }
    var nibAngle by remember { mutableFloatStateOf(45f) }
    var rasterBrushes by remember { mutableStateOf(JSONObject().apply {ArtBrush.tools.keys.forEach {put(it,ArtBrush.defaults(it))}}) }
    var fillShape by remember { mutableStateOf(false) }
    var bezierContinuous by remember { mutableStateOf(false) }
    var gradientMode by remember { mutableStateOf("linear") }
    var gradientReverse by remember { mutableStateOf(false) }
    var gradientToColor by remember { mutableStateOf(false) }
    var gradientEndInput by remember { mutableStateOf("#FFFFFFFF") }
    var gradientEndColor by remember { mutableStateOf("#FFFFFFFF") }
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
    var saveAsChooseLocation by remember { mutableStateOf(false) }
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
    var imageBackgroundDialog by remember { mutableStateOf(false) }
    var imageBackgroundColor by remember { mutableStateOf("#FFFFFFFF") }
    var canvasResizeDialog by remember { mutableStateOf(false) }
    var resizeWidth by remember { mutableStateOf("") }
    var resizeHeight by remember { mutableStateOf("") }
    var resizeOffsetX by remember { mutableStateOf("0") }
    var resizeOffsetY by remember { mutableStateOf("0") }
    var exportDialog by remember { mutableStateOf(false) }
    var advancedExportDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf("png") }
    var exportChooseLocation by remember { mutableStateOf(false) }
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
    var resizeRequest by remember { mutableStateOf<JSONObject?>(null) }
    var resizeAction by remember { mutableStateOf<((JSONObject?) -> JSONObject)?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var layerName by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var colorDialog by remember { mutableStateOf(false) }
    var colorText by remember { mutableStateOf(color) }
    var transformX by remember { mutableStateOf("0") }
    var transformY by remember { mutableStateOf("0") }
    var transformScale by remember { mutableStateOf("1") }
    var transformAngle by remember { mutableStateOf("0") }
    var leftDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var rightDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var leftDrawerPinned by rememberSaveable { mutableStateOf(false) }
    var rightDrawerPinned by rememberSaveable { mutableStateOf(false) }
    var dockPanelState by remember { mutableStateOf(ArtDockPanels.initial()) }
    val activeRightPane = RightPane.values().firstOrNull {
        it.name.lowercase(java.util.Locale.ROOT) == dockPanelState.optString("activePane")
    }
    var panelCloseDialog by remember { mutableStateOf<RightPane?>(null) }
    var suppressPanelCloseConfirmation by remember { mutableStateOf(false) }
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
                ArtStudioViewControl.setPresentationMode(mode)
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
                        StudioRenderFrame.create(store,state)
                    }
                }
                if (serial == renderSerial) {
                    snapshot = pair.first
                    image?.recycle()
                    image = pair.second
                    referenceBitmaps.values.forEach { it.recycle() }
                    referenceBitmaps = pair.fourth
                    revision = pair.third
                } else { pair.second.recycle();pair.fourth.values.forEach { it.recycle() } }
            } catch (error: Exception) {
                if (serial == renderSerial) { snapshot = null; image = null }
            }
        }
    }

    fun showImageImportNotice(result: JSONObject) {
        result.optJSONObject("imageImport")?.getJSONArray("warnings")?.let { warnings ->
            if (warnings.length() > 0) Toast.makeText(context,
                (0 until warnings.length()).joinToString("\n") { warnings.getString(it) },
                Toast.LENGTH_LONG).show()
        }
    }
    fun perform(confirmation: JSONObject? = null, onSuccess: (() -> Unit)? = null,
        action: (JSONObject?) -> JSONObject) {
        val serial = ++renderSerial
        pendingOperations++
        busy = true
        scope.launch {
            try {
                lateinit var operationResult: JSONObject
                val pair = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        operationResult = action(confirmation)
                        val state = store.current()
                        StudioRenderFrame.create(store,state)
                    }
                }
                if (serial == renderSerial) {
                    snapshot = pair.first
                    image?.recycle()
                    image = pair.second
                    referenceBitmaps.values.forEach { it.recycle() }
                    referenceBitmaps = pair.fourth
                    revision = pair.third
                } else { pair.second.recycle();pair.fourth.values.forEach { it.recycle() } }
                showImageImportNotice(operationResult)
                operationResult.optJSONObject("encloseResult")?.let {result ->
                    if(!result.getBoolean("changed"))Toast.makeText(context,result.getString("message"),Toast.LENGTH_SHORT).show()
                }
                if(operationResult.optBoolean("comicPanelFeedback") && !operationResult.getBoolean("changed"))
                    Toast.makeText(context,operationResult.getString("message"),Toast.LENGTH_SHORT).show()
                if(operationResult.has("referenceId") && serial==renderSerial)
                    canvasRef[0]?.fitReferences(snapshot?.getJSONObject("state"))
                onSuccess?.invoke()
            } catch (request: ArtImageResizeRequired) {
                resizeRequest = request.plan
                resizeAction = action
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Edit failed", error)
                Toast.makeText(context, error.message ?: "画室操作失败", Toast.LENGTH_LONG).show()
            } finally { pendingOperations--; busy = pendingOperations > 0 || awaitingExport }
        }
    }

    val exportRemainingLayer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val source = remainingExportPath
        remainingExportPath = ""
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        java.io.File(source).inputStream().use { it.copyTo(output) }
                    } ?: error("无法写入所选文件")
                }
                Toast.makeText(context,"图层图像已保存",Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                host.logger.e("ArtStudio","Layer export failed",error)
                Toast.makeText(context,error.message ?: "图层导出失败",Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportRemainingGroups=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val images=remainingGroupExports
        remainingGroupExports=JSONArray()
        if(uri!=null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,
                        android.provider.DocumentsContract.getTreeDocumentId(uri))
                    for(n in 0 until images.length()) {
                        val image=images.getJSONObject(n)
                        val target=android.provider.DocumentsContract.createDocument(context.contentResolver,parent,
                            "image/png",image.getString("name")) ?: error("无法创建图层组图像文件")
                        context.contentResolver.openOutputStream(target)?.use { output ->
                            java.io.File(image.getString("path")).inputStream().use { it.copyTo(output) }
                        } ?: error("无法写入图层组图像")
                    }
                }
                Toast.makeText(context,"已保存 ${images.length()} 个图层组",Toast.LENGTH_SHORT).show()
            } catch(error:Exception) {
                host.logger.e("ArtStudio","Group image export failed",error)
                remainingResult=JSONObject().put("title","导出未完成").put("text",error.message ?: "图层组导出失败")
            }
        }
    }
    fun acceptDockState(next: JSONObject) {
        if (next.getLong("revision") > dockPanelState.getLong("revision")) dockPanelState = next
    }
    fun changeDock(command: String, pane: RightPane? = null, enabled: Boolean? = null,
        suppressConfirmation: Boolean = false) {
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { mutex.withLock {
                    store.changeDockPanels(command, pane?.name?.lowercase(java.util.Locale.ROOT),
                        enabled, suppressConfirmation)
                } }
                acceptDockState(next)
            } catch (error: Exception) {
                remainingResult = JSONObject().put("title", "停靠面板操作未完成")
                    .put("text", error.message ?: "面板状态保存失败")
            }
        }
    }
    fun executeRemaining(item: JSONObject, captured: JSONObject, parameters: JSONObject) {
        if (busy) return
        val arguments=JSONObject(parameters.toString())
        if(item.optBoolean("documentWrite")) {
            val doc=captured.getJSONObject("document")
            arguments.put("documentId",doc.getString("id")).put("expectedRevision",doc.getInt("revision"))
        }
        pendingOperations++; busy=true
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) { mutex.withLock {
                    store.executeMenu("AWEI",item.getString("id"),arguments)
                } }
                if (result.has("dockPanels")) acceptDockState(result.getJSONObject("dockPanels"))
                if (item.getString("id") in setOf("options_configure", "reset_configurations"))
                    acceptDockState(withContext(Dispatchers.IO) { store.dockPanelState() })
                if ((result.has("images") || result.has("path")) && captured.getJSONObject("storage").getBoolean("custom")) {
                    remainingResult=JSONObject().put("title","已保存到默认目录").put("text",
                        if(result.has("images")) (0 until result.getJSONArray("images").length()).joinToString("\n") {
                            result.getJSONArray("images").getJSONObject(it).getString("path")
                        } else result.getString("path"))
                } else if(result.has("images")) {
                    remainingGroupExports=result.getJSONArray("images")
                    exportRemainingGroups.launch(null)
                } else if(result.has("path")) {
                    remainingExportPath=result.getString("path")
                    exportRemainingLayer.launch(result.getString("name"))
                } else if(result.has("state")) {
                    if(item.getString("id")=="window.current") {
                        val doc=result.getJSONObject("state")
                        remainingResult=JSONObject().put("title","当前画布").put("text",
                            "${doc.getString("name")}\n${doc.getInt("width")} × ${doc.getInt("height")} 像素")
                    } else refresh()
                } else if(item.getString("id").startsWith("help") || item.getString("id") in setOf("buginfo","sysinfo","histogram")) {
                    if(!result.has("title")) result.put("title",item.getString("title"))
                    remainingResult=result
                } else Toast.makeText(context,"已执行：${item.getString("title")}",Toast.LENGTH_SHORT).show()
                showImageImportNotice(result)
            } catch(request:ArtImageResizeRequired) {
                resizeRequest=request.plan
                resizeAction={ confirmation ->
                    val confirmed=JSONObject(arguments.toString()).put("confirmResize", requireNotNull(confirmation))
                    store.executeMenu("AWEI",item.getString("id"),confirmed)
                }
            } catch(error:Exception) {
                host.logger.e("ArtStudio","Menu operation failed",error)
                remainingResult=JSONObject().put("title","操作未完成").put("text",error.message ?: "菜单操作失败")
            } finally { pendingOperations--; busy=pendingOperations>0 || awaitingExport }
        }
    }
    val importRemainingLayer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val captured=remainingImportContext; val item=remainingImportItem
        remainingImportContext=null; remainingImportItem=null
        if(uri!=null && captured!=null && item!=null) scope.launch {
            try {
                val encoded=withContext(Dispatchers.IO) {
                    val bytes=context.contentResolver.openInputStream(uri)?.use { it.readNBytes(8*1024*1024+1) }
                        ?: error("无法读取图像文件")
                    require(bytes.size<=8*1024*1024) { "图片大小上限为 8 MB" }
                    android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
                }
                executeRemaining(item,captured,JSONObject().put("base64",encoded))
            } catch(error:Exception) {
                host.logger.e("ArtStudio","Layer import failed",error)
                remainingResult=JSONObject().put("title","导入未完成").put("text",error.message ?: "无法导入图片")
            }
        }
    }
    val addReference = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val captured=referenceImportContext
        referenceImportContext=null
        if(uri!=null && captured!=null) perform { confirmation ->
            val bytes=context.contentResolver.openInputStream(uri)?.use { it.readNBytes(8*1024*1024+1) }
                ?: error("无法读取参考图像")
            require(bytes.size<=8*1024*1024) { "参考图像输入上限8 MiB" }
            val name=context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,null,null)?.use { cursor -> if(cursor.moveToFirst()) cursor.getString(0) else "参考图像" } ?: "参考图像"
            val params=JSONObject(captured.toString()).put("name",name)
                .put("base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
            if(confirmation!=null) params.put("confirmResize",confirmation)
            store.referenceAdd("AWEI",params)
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
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val header = input.readNBytes(4)
                val projectFile = header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte()
                val limit = if (projectFile) 64 * 1024 * 1024 else 8 * 1024 * 1024
                val body = input.readNBytes(limit + 1 - header.size)
                require(body.size + header.size <= limit) {
                    if (projectFile) "工程文件超过 64 MB" else "图片大小上限为 8 MB"
                }
                header + body
            } ?: error("无法读取文件")
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
            } else {
                require(bytes.size <= 8 * 1024 * 1024) { "图片大小上限为 8 MB" }
                store.openImage(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                    if (untitled) "未命名图像" else name.substringBeforeLast('.').ifBlank { "未命名图像" },
                    confirmResize = it)
            }
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
        if (uri == null) {
            if(exportPath.isNotEmpty()) java.io.File(exportPath).delete()
            exportPath=""
            awaitingExport = false; busy = pendingOperations > 0
        }
        else {
            val source = exportPath
            exportPath=""
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
                } finally {
                    java.io.File(source).delete()
                    awaitingExport = false; busy = pendingOperations > 0
                }
            }
        }
    }
    val exportJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri == null) {
            if(exportPath.isNotEmpty()) java.io.File(exportPath).delete()
            exportPath=""
            awaitingExport = false; busy = pendingOperations > 0
        }
        else {
            val source = exportPath
            exportPath=""
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
                } finally {
                    java.io.File(source).delete()
                    awaitingExport = false; busy = pendingOperations > 0
                }
            }
        }
    }
    LaunchedEffect(store) {
        refresh()
        while (true) {
            val menuData=withContext(Dispatchers.IO) { store.menuUiState() }
            remainingContext=menuData
            acceptDockState(menuData.getJSONObject("dockPanels"))
            val settings=menuData.getJSONObject("settings")
            if(settings.toString()!=remainingSettings.toString()) {
                val previous=remainingSettings
                remainingSettings=settings
                if(!previous.has("brushWidth") || previous.getDouble("brushWidth")!=settings.getDouble("brushWidth"))
                    width=settings.getDouble("brushWidth").toFloat()
                if(!previous.has("brushOpacity") || previous.getDouble("brushOpacity")!=settings.getDouble("brushOpacity"))
                    opacity=settings.getDouble("brushOpacity").toFloat()
                for(key in listOf("panelsHidden","gridVisible","pixelGridVisible"))
                    if(!previous.has(key) || previous.getBoolean(key)!=settings.getBoolean(key))
                        ArtStudioViewControl.setOption(key,settings.getBoolean(key))
            }
            val request=menuData.optJSONObject("request")
            if(request!=null && !request.optBoolean("applied")) {
                val panel = request.getString("action").removePrefix("docker.")
                require(panel in ArtDockPanels.ids) { "未知画室面板请求" }
                if (dockPanelState.getJSONObject("visible").getBoolean(panel)) {
                    rightDrawerOpen=true
                    ArtStudioViewControl.setOption("panelsHidden",false)
                }
                withContext(Dispatchers.IO) { store.ackMenuUiRequest(request.getString("id")) }
            }
            delay(400)
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
    LaunchedEffect(tool,current?.optString("id"),revision,selected,
        magneticSettings.getString("reference"),magneticSettings.getInt("filterRadius"),magneticSettings.getBoolean("limitToSelection")) {
        magneticImage=null
        if(tool=="select_magnetic" && current!=null && selected.isNotBlank()) {
            val request=JSONObject(magneticSettings.toString()).put("documentId",current.getString("id"))
                .put("expectedRevision",current.getInt("revision")).put("layerId",selected)
            try {
                magneticImage=withContext(Dispatchers.IO) {store.magneticReference(request)}
            } catch(error: kotlinx.coroutines.CancellationException) {throw error}
            catch(error: Exception) {
                host.logger.e("ArtStudio","Magnetic reference preparation failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_LONG).show()
            }
        }
    }
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
        if (type.startsWith("SHAPE_") || type == "VECTOR_LAYER_CREATE") {
            if (busy || current == null) return
            if (!params.has("documentId")) params.put("documentId", current.getString("id"))
            if (!params.has("expectedRevision")) params.put("expectedRevision", current.getInt("revision"))
        }
        perform { store.apply("AWEI", type, params) }
    }
    fun openTextEditor(layer: JSONObject? = null, atX: Double = 0.0, atY: Double = 0.0) {
        if (busy || current == null) return
        val capturedDocument = current.getString("id")
        val capturedRevision = current.getInt("revision")
        val capturedLayer = layer?.let { JSONObject(it.toString()) }
        scope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) { ArtText.fonts() }
                require(catalog.getBoolean("available")) { "当前设备没有可用的基础文字渲染接口或中英文字体" }
                val fields = capturedLayer?.getJSONObject("text")?.let { JSONObject(it.toString()) }
                    ?: JSONObject().put("content", "").put("fontId", catalog.getString("defaultFontId"))
                        .put("fontSize", 48.0).put("boxWidth", minOf(640, state!!.getInt("width")))
                        .put("lineSpacing", 1.2).put("align", "left").put("color", color)
                fields.put("documentId", capturedDocument).put("expectedRevision", capturedRevision)
                    .put("x", capturedLayer?.getDouble("x") ?: atX)
                    .put("y", capturedLayer?.getDouble("y") ?: atY)
                capturedLayer?.let { fields.put("id", it.getString("id")) }
                textFonts = catalog.getJSONArray("fonts")
                textDialog = fields
            } catch (error: Exception) {
                host.logger.e("ArtStudio", "Text editor font catalog failed", error)
                Toast.makeText(context, error.message ?: "无法打开文字编辑器", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun publish(format: String, options: JSONObject = JSONObject(), chooseLocation: Boolean = false) {
        if (awaitingExport || busy) return
        awaitingExport = true
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val storage=store.saveDirectorySettings()
                        val direct=storage.getBoolean("custom") && !chooseLocation
                        val directory=if(direct) java.io.File(storage.getString("imagesDirectory"))
                            else java.io.File(host.cacheDir,"art-export-staging")
                        ArtRenderer.export(store,store.current(),format,"",options,directory)
                            .put("directSave",direct)
                    }
                }
                if(result.getBoolean("directSave")) {
                    awaitingExport=false
                    Toast.makeText(context,"图片已保存：" + result.getString("path"),Toast.LENGTH_LONG).show()
                } else {
                    exportPath = result.getString("path")
                    if (format == "png") exportPng.launch(result.getString("name"))
                    else exportJpeg.launch(result.getString("name"))
                }
            } catch (e: Exception) {
                if (exportPath.isNotEmpty()) java.io.File(exportPath).delete()
                exportPath = ""
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
    val menuCanCropSelection = state?.let { imageState ->
        imageState.optJSONObject("selection")?.let { selection ->
            val left = kotlin.math.floor(selection.getDouble("x")).toInt()
                .coerceIn(0, imageState.getInt("width"))
            val top = kotlin.math.floor(selection.getDouble("y")).toInt()
                .coerceIn(0, imageState.getInt("height"))
            val right = kotlin.math.ceil(selection.getDouble("x") + selection.getDouble("width"))
                .toInt().coerceIn(0, imageState.getInt("width"))
            val bottom = kotlin.math.ceil(selection.getDouble("y") + selection.getDouble("height"))
                .toInt().coerceIn(0, imageState.getInt("height"))
            right - left >= 64 && bottom - top >= 64
        }
    } == true
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
    val menuClipboardCanNew = ArtImagePolicy.dimensionsValid(clipboardSize.first, clipboardSize.second)

    SideEffect {
        menuBridge.menuContext=JSONObject(remainingContext.toString()).put("dockPanels", dockPanelState)
            .put("document", current ?: JSONObject.NULL)
            .put("layerClipboard",remainingContext.optBoolean("layerClipboard"))
            .put("settings",remainingContext.optJSONObject("settings")?.let { JSONObject(it.toString()) }
                ?.put("panelsHidden",viewOptions.panelsHidden) ?: JSONObject()
                .put("selectionVisible",true).put("panelsHidden",false).put("brushWidth",6.0).put("brushOpacity",1.0))
        menuBridge.onRemainingCommand={ item,captured ->
            if(!busy) {
                val id=item.getString("id")
                if(id in setOf("import_layer_from_file","import_layer_as_paint_layer")) {
                    remainingImportContext=captured; remainingImportItem=item
                    importRemainingLayer.launch(ArtImageFormats.pickerMimeTypes)
                } else if(id.removePrefix("docker.") in ArtDockPanels.ids) {
                    val panel = id.removePrefix("docker.")
                    executeRemaining(item,captured,JSONObject().put("enabled",
                        !dockPanelState.getJSONObject("visible").getBoolean(panel)))
                } else if(id in setOf("toggle_display_selection","view_toggledockers")) {
                    val settings=captured.getJSONObject("settings")
                    executeRemaining(item,captured,JSONObject().put("enabled", !settings.getBoolean(
                        if(id=="toggle_display_selection") "selectionVisible" else "panelsHidden")))
                } else {
                    val params=item.optJSONArray("parameters") ?: JSONArray()
                    val defaults=JSONObject()
                    val doc=captured.optJSONObject("document")?.getJSONObject("state")
                    if(id in setOf("selectionscale","edit_selection")) doc?.optJSONObject("selection")?.let { selection ->
                        listOf("x","y","width","height").forEach { defaults.put(it,selection.getDouble(it)) }
                    }
                    if(id=="art.storage_directory") {
                        val storage=captured.getJSONObject("storage")
                        defaults.put("directory",storage.getString("configuredDirectory"))
                        item.put("notice","当前默认目录：" + storage.getString("directory") +
                            "\n新工程、图片与备份分别放在 documents、exports、backups；旧工程继续原位保存。仅接受有文件访问权限的目录。")
                    }
                    if(id=="options_configure") {
                        val settings=captured.getJSONObject("settings")
                        defaults.put("brushWidth",settings.getDouble("brushWidth")).put("brushOpacity",settings.getDouble("brushOpacity"))
                            .put("confirmPanelClose",dockPanelState.getBoolean("confirmClose"))
                    }
                    if(params.length()>0 || id in setOf("flatten_image","flatten_layer","merge_layer","cut_layer_clipboard","reset_configurations")) {
                        remainingDialog=item;remainingCaptured=captured;remainingDefaults=defaults
                    } else executeRemaining(item,captured,JSONObject())
                }
            }
        }
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
        menuBridge.canCropSelection = menuCanCropSelection
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
                112 -> perform { store.pasteAsNew("AWEI", it) }
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
                5 -> { saveAsName = state?.optString("name", "未命名工程") ?: "未命名工程"; saveAsChooseLocation=false; saveAsDialog = true }
                6 -> sessionDialog = true
                7 -> { importingUntitled = true; openExternal.launch(arrayOf("*/*")) }
                8 -> { exportChooseLocation=false; exportDialog = true }
                9 -> {
                    cropX = "0"; cropY = "0"
                    cropWidth = state?.optInt("width")?.toString() ?: ""
                    cropHeight = state?.optInt("height")?.toString() ?: ""
                    outputWidth = cropWidth; outputHeight = cropHeight
                    exportChooseLocation=false
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
        menuBridge.onImageCommand = { command ->
            if (!busy && current != null) when (command) {
                301 -> documentInfoDialog = true
                306 -> perform { store.cropToSelection("AWEI") }
                302 -> {
                    imageBackgroundColor = current.getJSONObject("state").getString("background")
                    imageBackgroundDialog = true
                }
                318 -> {
                    val imageState = current.getJSONObject("state")
                    resizeWidth = imageState.getInt("width").toString()
                    resizeHeight = imageState.getInt("height").toString()
                    resizeOffsetX = "0"
                    resizeOffsetY = "0"
                    canvasResizeDialog = true
                }
            }
        }
        menuBridge.onViewCommand = { command ->
            if (!busy) when (command) {
                201 -> ArtStudioViewControl.setOption("panelsHidden", !viewOptions.panelsHidden)
                202 -> presentationDialog = true
                230 -> ArtStudioViewControl.setOption("statusBarVisible", !viewOptions.statusBarVisible)
                231 -> ArtStudioViewControl.setOption("gridVisible", !viewOptions.gridVisible)
                232 -> ArtStudioViewControl.setOption("pixelGridVisible", !viewOptions.pixelGridVisible)
                else -> {
                    val action = mapOf(
                        212 to "zoom_in", 213 to "zoom_out", 214 to "zoom_100",
                        215 to "fit", 216 to "fit_width", 217 to "fit_height",
                        218 to "rotate_right", 219 to "rotate_left",
                        220 to "reset_rotation", 221 to "mirror",
                        224 to "reset_display", 247 to "refresh"
                    )[command]
                    if (action != null) ArtStudioViewControl.command(action)
                }
            }
        }
    }
    SideEffect {
        ArtStudioViewControl.canvasAttached = current != null && canvasRef[0] != null
    }
    DisposableEffect(Unit) {
        onDispose {
            ArtStudioViewControl.canvasAttached = false
            ArtStudioViewControl.canvasZoom.value = null
            referenceBitmaps.values.forEach { it.recycle() }
        }
    }
    LaunchedEffect(Unit) {
        ArtStudioViewControl.zoomRequests.collect { request ->
            val canvas = canvasRef[0]
            // Queued view operations must never zoom a subsequently opened document.
            if (canvas != null && canvas.documentId == request.documentId && canvas.image != null)
                canvas.zoomToPercent(request.percent)
        }
    }
    fun runViewCommand(command: String) {
        val canvas = requireNotNull(canvasRef[0]) { "画布尚未完成挂载" }
        when (command) {
            "zoom_in" -> canvas.zoomIn()
            "zoom_out" -> canvas.zoomOut()
            "zoom_100" -> canvas.zoomTo100Percent()
            "fit" -> canvas.fitViewport()
            "fit_width" -> canvas.fitWidth()
            "fit_height" -> canvas.fitHeight()
            "rotate_right" -> canvas.rotateBy(15f)
            "rotate_left" -> canvas.rotateBy(-15f)
            "reset_rotation" -> canvas.resetRotation()
            "mirror" -> canvas.toggleMirror()
            "reset_display" -> canvas.resetDisplay()
            "refresh" -> refresh()
            else -> error("未知视图命令：$command")
        }
    }
    LaunchedEffect(Unit) {
        ArtStudioViewControl.commands.collect { command -> runViewCommand(command) }
    }
    val pageView = LocalView.current
    StudioViewConnection(host,
        physicalState = {
            val canvas = canvasRef[0]
            val visible = pageView.isAttachedToWindow && pageView.isShown &&
                pageView.windowVisibility == View.VISIBLE
            val attached = visible && canvas != null && canvas.isAttachedToWindow &&
                canvas.image != null && canvas.documentId.isNotBlank()
            ArtStudioViewControl.canvasAttached = attached
            ArtStudioViewControl.describe().apply {
                put("pageVisible", visible).put("canvasAttached", attached)
                if (attached) put("canvasZoom", ArtStudioViewControl.canvasZoom.value?.describe())
                else remove("canvasZoom")
                getJSONObject("toolOptionsWindow").put("visible",
                    attached && ArtStudioToolOptionsControl.state.value.open)
            }
        },
        execute = { operation, parameters ->
            check(!busy) { "画室正在处理绘画操作，请稍后再操作视图" }
            when (operation) {
                "command" -> {
                    runViewCommand(parameters.getString("command"))
                    JSONObject().put("accepted", true).put("command", parameters.getString("command"))
                }
                "zoom" -> {
                    val canvas = requireNotNull(canvasRef[0])
                    val zoomState = requireNotNull(ArtStudioViewControl.canvasZoom.value)
                    require(canvas.documentId == parameters.getString("documentId")) { "画布已切换" }
                    val percent = parameters.getDouble("percent")
                    require(percent.isFinite() && percent in zoomState.minPercent..zoomState.maxPercent)
                    canvas.zoomToPercent(percent)
                    JSONObject().put("accepted", true).put("documentId", canvas.documentId)
                        .put("requestedPercent", percent)
                }
                "set" -> ArtStudioViewControl.setOption(parameters.getString("option"), parameters.getBoolean("enabled"))
                "zoom_tool" -> ArtStudioViewControl.setZoomToolMode(parameters.getString("mode"))
                "tool_options" -> ArtStudioToolOptionsControl.command(parameters)
                "presentation" -> {
                    ArtStudioViewControl.setPresentationMode(parameters.getString("mode"))
                    ArtStudioViewControl.describe().put("accepted", true)
                }
                else -> error("未知页面视图操作：$operation")
            }
        })
    LaunchedEffect(toolWindow.open, toolWindow.toolId) {
        // A tool window must not leave a full-canvas drawer dismiss shield behind it.
        if (toolWindow.open) {
            if (!leftDrawerPinned) leftDrawerOpen = false
            if (!rightDrawerPinned) rightDrawerOpen = false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
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
                val leftOccupied =
                    if (leftDrawerOpen && !viewOptions.panelsHidden) leftDrawerWidth else 0.dp
                val rightDrawerWidth = if (rightDrawerPinned || leftDrawerOpen) {
                    drawerWidth.coerceAtMost(
                        (maxWidth - leftOccupied - railWidth * 2 - 112.dp).coerceAtLeast(0.dp))
                } else drawerWidth
                Box(Modifier.fillMaxSize().padding(
                    start = if (viewOptions.panelsHidden) 0.dp else
                        railWidth + if (leftDrawerOpen && leftDrawerPinned) leftDrawerWidth else 0.dp,
                    end = if (viewOptions.panelsHidden) 0.dp else
                        railWidth + if (rightDrawerOpen && rightDrawerPinned) rightDrawerWidth else 0.dp
                ).clipToBounds()) {
                AndroidView(factory = { ctx -> StudioCanvas(ctx).also { canvasRef[0] = it } },
                    modifier = Modifier.fillMaxSize(), update = { view ->
                    view.documentId = current.getString("id")
                    view.scene = state; view.sceneRevision = current.getInt("revision")
                    view.shapeMultiple = shapeMultiple; view.shapeBusy = busy
                    view.onShapeEdit = ::edit
                    view.colorizeWidth=colorizeWidth;view.colorizeErase=colorizeErase
                    view.onColorizeCreate={p -> if(!busy)perform {store.colorizeCreate("AWEI",p)}}
                    view.onColorizeStroke={p -> if(!busy)perform {store.colorizeStroke("AWEI",p)}}
                    view.selectionBezierEditing=selectionBezierEditing
                    view.selectionBezierComponent=selectionBezierComponent;view.selectionBezierNode=selectionBezierNode
                    view.selectionBezierMode=selectionBezierMode;view.selectionBezierSmooth=selectionBezierSmooth
                    view.onSelectionBezierNode={selectionBezierNode=it}
                    view.onSelectionBezierCreate={p -> if(!busy)perform {store.bezierSelectionCreate("AWEI",p)}}
                    view.onSelectionBezierEdit={p -> if(!busy)perform {store.bezierSelectionEdit("AWEI",p)}}
                    view.colorSelectionOptions=if(tool=="select_contiguous")contiguousSettings else similarSettings
                    view.onColorSelection={p -> if(!busy)perform {store.colorSelection("AWEI",p,tool=="select_contiguous")}}
                    view.magneticOptions=magneticSettings;view.magneticSource=magneticImage
                    view.onMagneticDraft={magneticDraft=it}
                    view.onMagneticComplete={p -> if(!busy)perform {store.magneticCommit("AWEI",p)}}
                    view.comicPanelOptions=comicPanelSettings
                    view.onComicPanel={mode,p -> if(!busy)perform {store.comicEdit("AWEI",mode,p)}}
                    view.encloseFillOptions=encloseFillSettings
                    view.onEncloseFill={p -> if(!busy)perform {store.encloseFill("AWEI",p)}}
                    view.smartPatchOptions = smartPatchSettings
                    view.onSmartPatch = { p -> if(!busy)perform { store.smartPatch("AWEI",p) } }
                    view.assistantAdding = assistantAdding
                    view.assistantType = assistantType
                    view.onAssistantCreated = { assistantAdding=false }
                    view.onAssistantEdit = { type,p -> if(!busy) perform { store.apply("AWEI",type,p) } }
                    view.onAssistedStroke = { p -> if(!busy) perform { store.apply("AWEI","STROKE_ADD",p) } }
                    view.brushSettings = JSONObject(rasterBrushes.getJSONObject(if(tool in ArtBrush.tools)tool else "ink").toString())
                    view.brushAssetFile = store::assetFile
                    view.referenceBitmaps = referenceBitmaps
                    view.referenceMultiple = referenceMultiple
                    view.onReferenceEdit = { type,p -> if(!busy) perform { store.apply("AWEI",type,p) } }
                    view.calligraphyOptions = JSONObject().put("width",width.toDouble())
                        .put("angle",vectorNibAngle.toDouble()).put("fixation",vectorFixation.toDouble())
                        .put("thinning",vectorThinning.toDouble()).put("smoothing",vectorSmoothing.toDouble())
                        .put("usePressure",vectorPressure).put("cap",vectorCap).put("color",color)
                        .put("opacity",opacity.toDouble())
                    view.onCalligraphy = { p -> if (!busy) perform { store.calligraphy("AWEI",p) } }
                    view.freehandMode = freehandMode; view.freehandPrecision = freehandPrecision
                    view.freehandClosed = freehandClosed
                    view.onFreehand = { p -> if (!busy) perform { store.freehand("AWEI", p) } }
                    view.bezierEditing = bezierEditing; view.bezierNode = bezierNode
                    view.bezierNodeType = bezierNodeType; view.bezierClosed = bezierClosed
                    view.onBezierNode = { bezierNode = it }
                    view.onBezierCreate = { p -> if (!busy) perform { store.pathCreate("AWEI", p) } }
                    view.image = image
                    view.gridVisible = viewOptions.gridVisible
                    view.pixelGridVisible = viewOptions.pixelGridVisible
                    view.horizontalFitBias = when {
                        !viewOptions.panelsHidden && leftDrawerOpen && leftDrawerPinned && !(rightDrawerOpen && rightDrawerPinned) -> -1f
                        !viewOptions.panelsHidden && rightDrawerOpen && rightDrawerPinned && !(leftDrawerOpen && leftDrawerPinned) -> 1f
                        else -> 0f
                    }
                    view.layers = layers
                    view.selectedId = selected
                    view.selection = state.optJSONObject("selection")
                    view.selectionVisible=remainingSettings.optBoolean("selectionVisible",true)
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
                        if (view.shapeCreationContext != null || selectedLayer?.getString("kind") == "vector") {
                            if (tool !in ArtShapes.kinds)
                                Toast.makeText(context, "此矢量层目前支持直线、矩形、椭圆和多边形", Toast.LENGTH_SHORT).show()
                            else {
                                val vertices = JSONArray()
                                for (n in 0 until points.length()) {
                                    val p = points.getJSONArray(n)
                                    vertices.put(JSONArray().put(p.getDouble(0)).put(p.getDouble(1)))
                                }
                                val shape = JSONObject().put("id", UUID.randomUUID().toString())
                                    .put("kind", tool).put("points", vertices)
                                    .put("stroke", color).put("strokeWidth", width.toDouble())
                                    .put("opacity", opacity.toDouble())
                                    .put("fill", if (fillShape && tool != "line") color else "#00000000")
                                val captured = view.shapeCreationContext
                                if (captured != null) edit("SHAPE_CREATE", JSONObject(captured.toString()).put("shape", shape))
                                else Toast.makeText(context, "请重新开始绘制形状", Toast.LENGTH_SHORT).show()
                            }
                        } else if (selectedLayer?.getString("kind") != "paint")
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
                    view.onText = { x, y, hit ->
                        openTextEditor(if (hit) selectedLayer else null, x, y)
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
                        if (ArtImagePolicy.dimensionsValid(right - x, bottom - y))
                            edit("CROP", JSONObject().put("x", x).put("y", y)
                                .put("width", right - x).put("height", bottom - y))
                        else Toast.makeText(context, "裁剪区域须包含至少一个有效像素", Toast.LENGTH_SHORT).show()
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
                if (!viewOptions.panelsHidden &&
                    ((leftDrawerOpen && !leftDrawerPinned) ||
                    (rightDrawerOpen && !rightDrawerPinned))) {
                    Box(Modifier.fillMaxSize().padding(horizontal = railWidth)
                        .clickable(onClickLabel = "收起未固定侧栏") {
                            if (!leftDrawerPinned) leftDrawerOpen = false
                            if (!rightDrawerPinned) rightDrawerOpen = false
                        })
                }
                // Handles remain reachable; pinned drawers may stay open together.
                if (leftDrawerOpen && !viewOptions.panelsHidden) {
                    Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                        .padding(start = railWidth).width(leftDrawerWidth).fillMaxHeight()
                        .clickable { }, tonalElevation = 3.dp) {
                        Box(Modifier.fillMaxSize()) {
                            val availableTools = ArtToolCatalog.implemented
                            val plannedTools = ArtToolCatalog.pending
                            val selectedToolName = if (tool == "zoom")
                                "缩放画布 · " + if (viewOptions.zoomToolMode == "in") "放大" else "缩小"
                            else availableTools.firstOrNull { it.first == tool }?.second ?: tool

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
                                availableTools.chunked(2).forEach { pair ->
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly) {
                                        pair.forEach { (id, label, glyph) ->
                                            val toolLabel = if (id == "zoom")
                                                label + if (viewOptions.zoomToolMode == "in")
                                                    "：放大；再次点击切换缩小" else "：缩小；再次点击切换放大"
                                            else label
                                            TooltipBox(
                                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                                tooltip = {
                                                    PlainTooltip {
                                                        Text(toolLabel + "；双击打开参数")
                                                    }
                                                },
                                                state = rememberTooltipState(),
                                                enableUserInput = true
                                            ) {
                                                Surface(Modifier.size(40.dp)
                                                    .semantics {
                                                        contentDescription = toolLabel + "；双击打开参数"
                                                        customActions = listOf(CustomAccessibilityAction("打开工具参数") {
                                                            ArtStudioToolOptionsControl.show(id); true
                                                        })
                                                    }
                                                    .combinedClickable(
                                                        onClickLabel = toolLabel,
                                                        onClick = {
                                                            if (id == "zoom" && tool == "zoom")
                                                                ArtStudioViewControl.setZoomToolMode("toggle")
                                                            ArtStudioToolOptionsControl.select(id)
                                                        },
                                                        onDoubleClick = { ArtStudioToolOptionsControl.show(id) }
                                                    ),
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                                    color = if (tool == id)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant) {
                                                    Box(Modifier.fillMaxSize(),
                                                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                                                        if (id == "zoom") {
                                                            Icon(Icons.Default.Search, contentDescription = null,
                                                                modifier = Modifier.size(24.dp))
                                                            Text(viewOptions.zoomToolBadge,
                                                                fontSize = 10.sp, lineHeight = 12.sp,
                                                                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
                                                                    .padding(end = 3.dp, bottom = 2.dp))
                                                        } else {
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
                                plannedTools.chunked(2).forEach { pair ->
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly) {
                                        pair.forEach { item ->
                                            TooltipBox(
                                                positionProvider =
                                                    TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                                tooltip = {
                                                    PlainTooltip {
                                                        Text("${item.label} · 尚未实现；双击查看说明")
                                                    }
                                                },
                                                state = rememberTooltipState(),
                                                enableUserInput = true
                                            ) {
                                                Surface(Modifier.size(40.dp).semantics {
                                                    contentDescription = "${item.label}，尚未实现，双击查看说明"
                                                    customActions = listOf(CustomAccessibilityAction("查看工具说明") {
                                                        ArtStudioToolOptionsControl.show(item.id); true
                                                    })
                                                }.combinedClickable(
                                                    onClickLabel = "工具尚未实现",
                                                    onClick = { },
                                                    onDoubleClick = { ArtStudioToolOptionsControl.show(item.id) }
                                                ),
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant
                                                        .copy(alpha = 0.45f)) {
                                                    Box(Modifier.fillMaxSize(),
                                                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                                                        Text(item.glyph,
                                                            color = MaterialTheme.colorScheme
                                                                .onSurfaceVariant.copy(alpha = 0.45f),
                                                            style = MaterialTheme.typography.titleMedium)
                                                        Text("未", Modifier.align(
                                                            androidx.compose.ui.Alignment.BottomEnd),
                                                            color = MaterialTheme.colorScheme
                                                                .onSurfaceVariant.copy(alpha = 0.7f),
                                                            style = MaterialTheme.typography.labelSmall)
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
                if (rightDrawerOpen && !viewOptions.panelsHidden) {
                    Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                        .padding(end = railWidth).width(rightDrawerWidth).fillMaxHeight()
                        .clickable { }, tonalElevation = 3.dp) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val paneHeaderHeight = 40.dp
                            val pinHeaderHeight = 40.dp
                            val rightAccordionState = rememberLazyListState()
                            val paneOrder = rightPaneOrderNames.mapNotNull { name ->
                                RightPane.values().firstOrNull { it.name == name }
                            }.filter { dockPanelState.getJSONObject("visible")
                                .getBoolean(it.name.lowercase(java.util.Locale.ROOT)) }
                            // Headers before and after the active pane share one scroll list; neither reserves viewport space.
                            val activePaneBodyHeight =
                                (maxHeight - pinHeaderHeight - paneHeaderHeight).coerceAtLeast(120.dp)
                            val activeHeaderIndex =
                                paneOrder.indexOf(activeRightPane).coerceAtLeast(0)

                            LaunchedEffect(activeRightPane, paneOrder, draggingRightPane) {
                                if (draggingRightPane == null && activeRightPane != null && paneOrder.isNotEmpty()) {
                                    rightAccordionState.animateScrollToItem(activeHeaderIndex)
                                }
                            }

                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxWidth().height(pinHeaderHeight)) {
                                    Text("视图列表",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                            .combinedClickable(
                                                onClickLabel = "恢复上次展开状态",
                                                onDoubleClick = { changeDock("collapse_all") },
                                                onClick = { changeDock("restore") }))
                                    Box(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                                        .padding(start = 2.dp).size(36.dp)
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
                                    if (paneOrder.isEmpty()) item {
                                        Text("所有面板已关闭，可在「设置 → 停靠面板」重新显示。",
                                            modifier = Modifier.padding(12.dp))
                                    }
                                    paneOrder.forEach { pane ->
                                        val isActive = activeRightPane == pane
                                        val isDragging = draggingRightPane == pane
                                        val label = when (pane) {
                                            RightPane.COLOR -> "多功能拾色器"
                                            RightPane.LAYERS -> "图层"
                                            RightPane.BRUSHES -> "笔刷预设"
                                            RightPane.FOOTPRINTS -> "足迹"
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
                                                                val names = rightPaneOrderNames.filter { name ->
                                                                    dockPanelState.getJSONObject("visible")
                                                                        .getBoolean(name.lowercase(java.util.Locale.ROOT))
                                                                }.toMutableList()
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
                                                                    val reordered = names.iterator()
                                                                    rightPaneOrderNames = rightPaneOrderNames.map { name ->
                                                                        if (name in names) reordered.next() else name
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                                    .combinedClickable(
                                                        onClickLabel = "展开$label",
                                                        onDoubleClick = {
                                                            if (draggingRightPane == null) changeDock("collapse", pane)
                                                        },
                                                        onClick = {
                                                            if (draggingRightPane == null && !isActive) changeDock("expand", pane)
                                                        })
                                                    .semantics { contentDescription = "$label，单击展开，双击折叠，长按拖动排序" }
                                                    .padding(start = 10.dp),
                                                    contentAlignment =
                                                        androidx.compose.ui.Alignment.CenterStart) {
                                                    Text((if (isActive) "▾ " else "▸ ") + label,
                                                        style =
                                                            MaterialTheme.typography.titleSmall)
                                                }
                                                Box(Modifier.size(36.dp)
                                                    .clickable(onClickLabel = "关闭$label") {
                                                        if (draggingRightPane == null) {
                                                            if (dockPanelState.getBoolean("confirmClose")) {
                                                                suppressPanelCloseConfirmation = false
                                                                panelCloseDialog = pane
                                                            } else changeDock("set_visible", pane, false)
                                                        }
                                                    }
                                                    .semantics {
                                                        contentDescription =
                                                            "关闭$label"
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
                                                    RightPane.FOOTPRINTS -> {
                                                        val timeline = current.getJSONArray("timeline")
                                                        val position = current.getInt("timelinePosition")
                                                        val observedRevision = current.getInt("revision")
                                                        val historyScroll = rememberLazyListState()
                                                        val timeFormat = remember {
                                                            java.text.SimpleDateFormat("HH:mm:ss",
                                                                java.util.Locale.getDefault())
                                                        }
                                                        LaunchedEffect(current.getString("id"),
                                                            observedRevision, activeRightPane) {
                                                            historyScroll.scrollToItem(position)
                                                        }
                                                        LazyColumn(Modifier.fillMaxWidth()
                                                            .height(activePaneBodyHeight),
                                                            state = historyScroll) {
                                                            items(timeline.length(),
                                                                key = { index ->
                                                                    "footprint_" + timeline
                                                                        .getJSONObject(index)
                                                                        .getString("id")
                                                                }) { index ->
                                                                val step = timeline.getJSONObject(index)
                                                                val currentStep = index == position
                                                                val future = index > position
                                                                val label = step.getString("label")
                                                                val actor = when (step.getString("actor")) {
                                                                    "AWEI" -> "阿伟"
                                                                    "LANER" -> "兰儿"
                                                                    else -> "工程"
                                                                }
                                                                val time = step.optLong("timestamp")
                                                                Row(Modifier.fillMaxWidth()
                                                                    .background(if (currentStep)
                                                                        MaterialTheme.colorScheme
                                                                            .surfaceVariant
                                                                    else MaterialTheme.colorScheme
                                                                        .surface)
                                                                    .clickable(
                                                                        enabled = !busy && !currentStep,
                                                                        onClickLabel = "查看第${index}步：$label") {
                                                                        perform {
                                                                            store.historyJump(
                                                                                "AWEI",
                                                                                step.getString("id"),
                                                                                observedRevision)
                                                                        }
                                                                    }
                                                                    .padding(horizontal = 12.dp,
                                                                        vertical = 9.dp),
                                                                    verticalAlignment =
                                                                        androidx.compose.ui.Alignment
                                                                            .CenterVertically) {
                                                                    Text(if (currentStep) "●" else "○",
                                                                        color = if (currentStep)
                                                                            MaterialTheme.colorScheme
                                                                                .primary
                                                                        else MaterialTheme.colorScheme
                                                                            .onSurfaceVariant,
                                                                        modifier = Modifier
                                                                            .padding(end = 8.dp))
                                                                    if (step.has("color")) {
                                                                        Box(Modifier.padding(end = 8.dp)
                                                                            .size(12.dp)
                                                                            .background(
                                                                                androidx.compose.ui.graphics
                                                                                    .Color(Color.parseColor(
                                                                                        step.getString("color"))),
                                                                                androidx.compose.foundation
                                                                                    .shape.CircleShape))
                                                                    }
                                                                    Column(Modifier.weight(1f)) {
                                                                        Text("$index · $label",
                                                                            maxLines = 1,
                                                                            overflow = TextOverflow
                                                                                .Ellipsis,
                                                                            color = MaterialTheme
                                                                                .colorScheme.onSurface
                                                                                .copy(alpha =
                                                                                    if (future)
                                                                                        0.55f
                                                                                    else 1f))
                                                                        Text(if (time > 0L)
                                                                            "$actor · " +
                                                                                timeFormat.format(
                                                                                    java.util.Date(time))
                                                                        else actor,
                                                                            style = MaterialTheme
                                                                                .typography.bodySmall,
                                                                            color = MaterialTheme
                                                                                .colorScheme
                                                                                .onSurfaceVariant)
                                                                    }
                                                                }
                                                                HorizontalDivider()
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
                }
                if (!viewOptions.panelsHidden) Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
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
                if (!viewOptions.panelsHidden) Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
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
            if (viewOptions.statusBarVisible && !viewOptions.panelsHidden)
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
                    Row(Modifier.weight(1f, fill = false).padding(start = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val liveZoom = canvasZoom?.takeIf { it.documentId == current.getString("id") }
                        val zoomEnabled = image != null && liveZoom != null
                        val activeTrack = MaterialTheme.colorScheme.primary
                        val inactiveTrack = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        val thumbColor = if (zoomEnabled) activeTrack else inactiveTrack
                        Slider(value = liveZoom?.sliderPosition ?: 0f,
                            onValueChange = { position ->
                                canvasRef[0]?.zoomToSlider(position)
                            },
                            enabled = zoomEnabled,
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f, fill = false).widthIn(max = 120.dp).semantics {
                                contentDescription = "画布缩放比例"
                            },
                            thumb = {
                                Box(Modifier.size(18.dp).background(thumbColor,
                                    androidx.compose.foundation.shape.CircleShape))
                            },
                            track = { sliderState ->
                                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                                    val start = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl)
                                        size.width else 0f
                                    val end = size.width - start
                                    val y = size.height / 2f
                                    drawLine(inactiveTrack,
                                        androidx.compose.ui.geometry.Offset(start, y),
                                        androidx.compose.ui.geometry.Offset(end, y),
                                        strokeWidth = size.height,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    drawLine(if (zoomEnabled) activeTrack else inactiveTrack,
                                        androidx.compose.ui.geometry.Offset(start, y),
                                        androidx.compose.ui.geometry.Offset(start + (end - start) * sliderState.value, y),
                                        strokeWidth = size.height,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                }
                            })
                        Text(if (liveZoom != null)
                            String.format(java.util.Locale.ROOT, "%.1f%%", liveZoom.percent) else "—",
                            modifier = Modifier.width(52.dp),
                            fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                        TextButton(onClick = { canvasRef[0]?.fitToWindow() }, enabled = image != null,
                            modifier = Modifier.width(48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("居中")
                        }
                        TextButton(onClick = { presentationDialog = true },
                            modifier = Modifier.width(48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("全屏")
                        }
                    }
                }
            }
        }
    }
    if (toolWindow.open && current != null) {
        val tool = toolWindow.toolId
        val toolTitle = ArtToolCatalog.implemented.firstOrNull { it.first == tool }?.second
            ?: ArtToolCatalog.pending.first { it.id == tool }.label
        StudioToolOptionsWindow(toolWindow, toolTitle,
            ArtStudioToolOptionsControl::move, ArtStudioToolOptionsControl::minimize,
            ArtStudioToolOptionsControl::restore, ArtStudioToolOptionsControl::close) {
            val pending = ArtToolCatalog.pending.firstOrNull { it.id == tool }
            if (pending != null) {
                Text("该工具尚未实现，当前仅提供说明入口，不能用于绘画。")
            } else {
                if(tool in ArtBrush.tools)StudioBrushOptions(store,tool,rasterBrushes.getJSONObject(tool),width,opacity,busy,
                    {updated -> rasterBrushes=JSONObject(rasterBrushes.toString()).put(tool,updated)},
                    {w,o -> width=w;opacity=o})
                if(tool=="colorize_mask") {
                    StudioColorizeOptions(current,selected,busy,colorizeWidth,colorizeErase,color,
                        {colorizeWidth=it},{colorizeErase=it},{color=it},
                        {p -> if(!busy)perform {store.colorizeCreate("AWEI",p)}},
                        {p -> if(!busy)perform {store.colorizeUpdate("AWEI",p)}},
                        {type,p -> if(!busy)perform {store.apply("AWEI",type,p)}})
                }
                if(tool=="select_bezier") {
                    StudioBezierSelectionOptions(current,busy,selectionBezierEditing,selectionBezierComponent,selectionBezierNode,
                        selectionBezierMode,selectionBezierSmooth,{value ->
                            if(canvasRef[0]?.selectionBezierHasDraft==true)Toast.makeText(context,"请先完成或取消当前选区",Toast.LENGTH_SHORT).show()
                            else {
                                if(value) {
                                    val area=current.getJSONObject("state").optJSONObject("selection")
                                    val parts=area?.let {ArtBezierSelection.parts(it)} ?: emptyList()
                                    val curves=parts.indices.filter {parts[it].getJSONObject("selection").optString("shape","rect")=="bezier"}
                                    if(selectionBezierComponent !in curves && curves.isNotEmpty())selectionBezierComponent=curves.first()
                                }
                                selectionBezierEditing=value
                            }
                        },{selectionBezierComponent=it},{selectionBezierNode=it},{selectionBezierMode=it},{selectionBezierSmooth=it},
                        {command -> canvasRef[0]?.selectionBezierCommand(command)},
                        {p -> if(!busy)perform {store.bezierSelectionEdit("AWEI",p)}})
                }
                if(tool=="select_contiguous")StudioColorSelectionOptions(contiguousSettings,true,busy,{contiguousSettings=it})
                if(tool=="select_similar")StudioColorSelectionOptions(similarSettings,false,busy,{similarSettings=it})
                if(tool=="select_magnetic")StudioMagneticSelectionOptions(magneticSettings,busy,magneticDraft,magneticImage!=null,
                    {magneticSettings=it},{command -> canvasRef[0]?.magneticCommand(command)})
                if(tool=="comic_panel") {
                    StudioComicPanelOptions(current,selected,busy,comicPanelSettings,{comicPanelSettings=it},
                        {p -> if(!busy)perform {store.apply("AWEI","VECTOR_LAYER_CREATE",p.put("id",java.util.UUID.randomUUID().toString()))}},
                        {p -> if(!busy)perform {store.comicFrame("AWEI",p)}},
                        {p -> if(!busy)perform {store.apply("AWEI","SHAPE_STYLE",p)}})
                }
                if(tool=="enclose_fill") {
                    StudioEncloseFillOptions(encloseFillSettings,busy,color,{colorText=color;colorDialog=true},{encloseFillSettings=it})
                }
                if(tool=="smart_patch") {
                    StudioSmartPatchOptions(smartPatchSettings,busy,{smartPatchSettings=it})
                }
                if(tool=="assistant") {
                    StudioAssistantOptions(current,busy,assistantAdding,assistantType,
                        {assistantAdding=it},{assistantType=it},
                        {type,p -> if(!busy)perform { store.apply("AWEI",type,p) }},
                        {assistantAdding=false;ArtStudioToolOptionsControl.select("ink")})
                }
                if(tool in ArtAssistants.brushTools) {
                    val assistantSettings=ArtAssistants.settings(current.getJSONObject("state"))
                    FilterChip(selected=assistantSettings.getBoolean("snapping"),enabled=!busy,
                        onClick={
                            val p=JSONObject().put("documentId",current.getString("id")).put("expectedRevision",current.getInt("revision"))
                                .put("settings",JSONObject().put("snapping",!assistantSettings.getBoolean("snapping")))
                            perform { store.apply("AWEI","ASSISTANT_SETTINGS",p) }
                        },label={Text("吸附到辅助尺规")})
                    TextButton(onClick={ArtStudioToolOptionsControl.show("assistant")},enabled=!busy) { Text("设置辅助尺规") }
                }
                if (tool == "vector_bezier") {
                    StudioBezierOptions(current, selected, busy, bezierEditing, bezierNode,
                        bezierNodeType, bezierClosed, fillShape, { mode ->
                            if (mode != bezierEditing && canvasRef[0]?.bezierHasDraft == true)
                                Toast.makeText(context,"请先完成或取消当前路径",Toast.LENGTH_SHORT).show()
                            else bezierEditing = mode
                        }, { bezierNode = it }, { bezierNodeType = it },
                        { bezierClosed = it }, { fillShape = it },
                        { canvasRef[0]?.bezierCommand(it) }, ::edit)
                }
                if (tool == "reference_images") {
                    StudioReferenceOptions(current,busy,referenceMultiple,{referenceMultiple=it},{
                        referenceImportContext=JSONObject().put("documentId",current.getString("id"))
                            .put("expectedRevision",current.getInt("revision"))
                        addReference.launch(arrayOf("image/*"))
                    },{canvasRef[0]?.fitReferences()}, {type,p ->
                        if(!busy) perform { store.apply("AWEI",type,p) }
                    })
                }
                if (tool == "vector_calligraphy") {
                    StudioCalligraphyOptions(current,selected,busy,vectorNibAngle,vectorFixation,
                        vectorThinning,vectorSmoothing,vectorPressure,vectorCap,
                        {vectorNibAngle=it},{vectorFixation=it},{vectorThinning=it},
                        {vectorSmoothing=it},{vectorPressure=it},{vectorCap=it},::edit)
                }
                if (tool == "vector_freehand") {
                    StudioFreehandOptions(current, selected, busy, freehandMode, freehandPrecision,
                        freehandClosed, fillShape, { freehandMode = it }, { freehandPrecision = it },
                        { freehandClosed = it }, { fillShape = it }, ::edit)
                }
                if (tool == "shape_select") {
                    StudioShapeOptions(current, selected, busy, color, width,
                        shapeMultiple, { shapeMultiple = it }, ::edit,
                        { bezierEditing = true; bezierNode = 0; ArtStudioToolOptionsControl.select("vector_bezier") })
                }
                if (tool == "svg_text") {
                    Text("点击画布添加文字；点击选中文字编辑。", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { openTextEditor(selectedLayer) },
                        enabled = !busy && selectedLayer?.optString("kind") == "text") {
                        Text("编辑选中文字")
                    }
                    TextButton(onClick = { openTextEditor() }, enabled = !busy && current != null) {
                        Text("新建文字")
                    }
                    // Planned text features share the text parameter window; they cannot execute yet.
                    Text("高级排版", style = MaterialTheme.typography.labelLarge)
                    ArtToolCatalog.textAdvancedOptions.forEach { option ->
                        TextButton(onClick = { }, enabled = false) {
                            Text("$option（待实现）", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (tool == "sampler") {
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
                }
                if (tool == "fill") {
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
                if (tool == "mirror") {
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
                        Text("已添加 ${mirrorCenters.length()} 支子画笔；使用下方布置按钮后，点击窗口外的画布放置。")
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
                }
                if (tool == "transform") {
                    if (selectedLayer == null) {
                        Text("请先选择要变换的图层。")
                    } else {
                        LaunchedEffect(current.getString("id"), selected, current.getInt("revision")) {
                            transformX = selectedLayer.getDouble("x").toString()
                            transformY = selectedLayer.getDouble("y").toString()
                            transformScale = selectedLayer.getDouble("scale").toString()
                            transformAngle = selectedLayer.getDouble("rotation").toString()
                        }
                        OutlinedTextField(transformX, { transformX = it }, label = { Text("X 位置") })
                        OutlinedTextField(transformY, { transformY = it }, label = { Text("Y 位置") })
                        OutlinedTextField(transformScale, { transformScale = it }, label = { Text("缩放（0.01–100）") })
                        OutlinedTextField(transformAngle, { transformAngle = it }, label = { Text("旋转角度") })
                        TextButton(enabled = !busy, onClick = {
                            val x = transformX.toDoubleOrNull(); val y = transformY.toDoubleOrNull()
                            val scale = transformScale.toDoubleOrNull(); val angle = transformAngle.toDoubleOrNull()
                            if (x != null && y != null && scale != null && angle != null &&
                                x.isFinite() && y.isFinite() && scale in 0.01..100.0 && angle.isFinite()) {
                                edit("TRANSFORM", JSONObject().put("id", selected).put("x", x).put("y", y)
                                    .put("scale", scale).put("rotation", angle))
                            } else Toast.makeText(context, "请输入有效的位置、缩放和角度", Toast.LENGTH_SHORT).show()
                        }) { Text("应用变换") }
                    }
                }
                if (tool == "dyna") {
                    Text("惯性：${(dynaMass * 100).toInt()}%")
                    Slider(value = dynaMass, onValueChange = { dynaMass = it },
                        valueRange = 0f..1f)
                    Text("阻力：${(dynaDrag * 100).toInt()}%")
                    Slider(value = dynaDrag, onValueChange = { dynaDrag = it },
                        valueRange = 0f..1f)
                    Text("按 Krita 动态工具的质量与阻力公式平滑轨迹；同一笔画保留绘制时参数。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (tool == "zoom") {
                    FilterChip(selected = viewOptions.zoomToolMode == "in",
                        onClick = { ArtStudioViewControl.setZoomToolMode("in") }, label = { Text("放大") })
                    FilterChip(selected = viewOptions.zoomToolMode == "out",
                        onClick = { ArtStudioViewControl.setZoomToolMode("out") }, label = { Text("缩小") })
                }
                if (tool !in setOf("select_contiguous", "select_similar", "select_magnetic", "comic_panel", "select_bezier", "enclose_fill", "colorize_mask", "smart_patch", "assistant", "vector_bezier", "reference_images", "vector_calligraphy",
                    "vector_freehand", "shape_select", "svg_text", "sampler", "fill", "mirror",
                    "rectangle", "ellipse", "polygon", "bezier", "gradient", "transform", "calligraphy", "dyna", "zoom")) {
                    Text("此工具暂无独立参数。")
                    Text(ArtToolCatalog.usage(tool), style = MaterialTheme.typography.bodySmall)
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
    resizeRequest?.let { plan ->
        AlertDialog(onDismissRequest = { resizeRequest = null; resizeAction = null },
            title = { Text("图片需要缩小") },
            text = { Text(plan.getString("message")) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val action = requireNotNull(resizeAction)
                val confirmation = plan.getJSONObject("confirmation")
                resizeRequest = null; resizeAction = null
                perform(confirmation = confirmation, action = action)
            }) { Text("按建议尺寸缩小后打开") } },
            dismissButton = { TextButton(onClick = { resizeRequest = null; resizeAction = null }) { Text("取消") } })
    }
    if (newCanvas) {
        val chosenWidth = canvasWidth.toIntOrNull()
        val chosenHeight = canvasHeight.toIntOrNull()
        val dimensionsValid = chosenWidth != null && chosenHeight != null &&
            ArtImagePolicy.dimensionsValid(chosenWidth, chosenHeight)
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
                        OutlinedTextField(canvasWidth, { canvasWidth = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("宽度 (px)") })
                        OutlinedTextField(canvasHeight, { canvasHeight = it.filter(Char::isDigit).take(5) },
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
                        else "宽高须在 1–16384 像素之间；创建时检查内存预算",
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
    textDialog?.let { captured ->
        StudioTextEditor(captured, textFonts, busy,
            onDismiss = { textDialog = null },
            onSubmit = { fields ->
                perform(onSuccess = { textDialog = null }) {
                    store.writeText("AWEI", fields, fields.has("id"))
                }
            })
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
        text = { Column {
            OutlinedTextField(saveAsName, { saveAsName = it.take(100) },
                label = { Text("新工程名称") }, singleLine = true)
            StudioSaveLocationOptions(remainingContext.optJSONObject("storage"),"projectsDirectory",
                saveAsChooseLocation) { saveAsChooseLocation=it }
        } },
        confirmButton = { TextButton(onClick = {
            val name = saveAsName.trim()
            if (name.isNotBlank() && !busy) {
                saveAsDialog = false
                if(remainingContext.optJSONObject("storage")?.optBoolean("custom")==true && !saveAsChooseLocation) {
                    perform { store.saveAs(name) }
                } else {
                    pendingSaveAsName = name
                    awaitingExport = true
                    busy = true
                    saveAsFile.launch(name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".ailart")
                }
            }
        }, enabled = saveAsName.isNotBlank() && !busy) {
            Text(if(remainingContext.optJSONObject("storage")?.optBoolean("custom")==true && !saveAsChooseLocation)
                "另存到默认目录" else "选择保存位置")
        } },
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
            StudioSaveLocationOptions(remainingContext.optJSONObject("storage"),"imagesDirectory",
                exportChooseLocation) { exportChooseLocation=it }
        } },
        confirmButton = { TextButton(onClick = {
            exportDialog = false; publish(exportFormat,chooseLocation=exportChooseLocation)
        }) {
            Text(if(remainingContext.optJSONObject("storage")?.optBoolean("custom")==true && !exportChooseLocation)
                "保存到默认目录" else "选择保存位置")
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
            StudioSaveLocationOptions(remainingContext.optJSONObject("storage"),"imagesDirectory",
                exportChooseLocation) { exportChooseLocation=it }
        } },
        confirmButton = { TextButton(onClick = {
            val x = cropX.toIntOrNull(); val y = cropY.toIntOrNull()
            val w = cropWidth.toIntOrNull(); val h = cropHeight.toIntOrNull()
            val outW = outputWidth.toIntOrNull(); val outH = outputHeight.toIntOrNull()
            val canvasW = state?.optInt("width") ?: 0
            val canvasH = state?.optInt("height") ?: 0
            if (x == null || y == null || w == null || h == null || outW == null || outH == null ||
                x < 0 || y < 0 || w <= 0 || h <= 0 || x.toLong() + w > canvasW ||
                y.toLong() + h > canvasH || !ArtImagePolicy.dimensionsValid(outW, outH)) {
                Toast.makeText(context, "裁切范围或输出尺寸无效", Toast.LENGTH_LONG).show()
            } else {
                advancedExportDialog = false
                publish(exportFormat, JSONObject().put("x", x).put("y", y)
                    .put("cropWidth", w).put("cropHeight", h)
                    .put("width", outW).put("height", outH),chooseLocation=exportChooseLocation)
            }
        }) {
            Text(if(remainingContext.optJSONObject("storage")?.optBoolean("custom")==true && !exportChooseLocation)
                "保存到默认目录" else "选择保存位置")
        } },
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
    if (imageBackgroundDialog && current != null) {
        val validColor = imageBackgroundColor.matches(Regex("#[A-Fa-f0-9]{8}"))
        AlertDialog(onDismissRequest = { imageBackgroundDialog = false },
            title = { Text("图像背景色与透明度") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("背景色采用 #AARRGGBB，前两位是透明度。修改会进入工程历史，不会覆盖绘画图层。")
                OutlinedTextField(imageBackgroundColor,
                    { imageBackgroundColor = it.take(9) },
                    singleLine = true, label = { Text("背景色") })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("透明背景", Modifier.weight(1f))
                    Switch(checked = imageBackgroundColor.startsWith("#00"),
                        onCheckedChange = { transparentBackground ->
                            if (imageBackgroundColor.length == 9)
                                imageBackgroundColor = if (transparentBackground)
                                    "#00" + imageBackgroundColor.drop(3)
                                else "#FF" + imageBackgroundColor.drop(3)
                        })
                }
                if (!validColor) Text("请输入 8 位十六进制颜色，例如 #FFFFFFFF 或 #00000000",
                    color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                val color = imageBackgroundColor
                imageBackgroundDialog = false
                perform { store.apply("AWEI", "IMAGE_BACKGROUND", JSONObject().put("color", color)) }
            }, enabled = validColor && !busy) { Text("确定") } },
            dismissButton = { TextButton(onClick = { imageBackgroundDialog = false }) {
                Text("取消")
            } })
    }
    if (canvasResizeDialog && current != null) {
        val widthPx = resizeWidth.toIntOrNull()
        val heightPx = resizeHeight.toIntOrNull()
        val offsetX = resizeOffsetX.toIntOrNull()
        val offsetY = resizeOffsetY.toIntOrNull()
        val valid = widthPx != null && heightPx != null && ArtImagePolicy.dimensionsValid(widthPx, heightPx) &&
            offsetX != null && offsetX in -ArtImagePolicy.MAX_EDGE..ArtImagePolicy.MAX_EDGE &&
            offsetY != null && offsetY in -ArtImagePolicy.MAX_EDGE..ArtImagePolicy.MAX_EDGE
        AlertDialog(onDismissRequest = { canvasResizeDialog = false },
            title = { Text("更改画布大小") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("调整画布边界，不缩放图层。偏移表示旧图像左上角在新画布中的位置；负值会裁掉边缘。")
                OutlinedTextField(resizeWidth, { resizeWidth = it.filter(Char::isDigit).take(5) },
                    singleLine = true, label = { Text("新宽度 (px)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(resizeHeight, { resizeHeight = it.filter(Char::isDigit).take(5) },
                    singleLine = true, label = { Text("新高度 (px)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(resizeOffsetX,
                    { resizeOffsetX = it.filterIndexed { index, c -> c.isDigit() || (index == 0 && c == '-') }.take(6) },
                    singleLine = true, label = { Text("水平偏移 (px)") })
                OutlinedTextField(resizeOffsetY,
                    { resizeOffsetY = it.filterIndexed { index, c -> c.isDigit() || (index == 0 && c == '-') }.take(6) },
                    singleLine = true, label = { Text("垂直偏移 (px)") })
                if (!valid) Text("边长须在 1–16384 px，偏移须在 -16384–16384 px；处理时检查内存预算",
                    color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                val params = JSONObject().put("width", widthPx!!).put("height", heightPx!!)
                    .put("x", -offsetX!!).put("y", -offsetY!!)
                canvasResizeDialog = false
                perform { store.apply("AWEI", "CANVAS_RESIZE", params) }
            }, enabled = valid && !busy) { Text("确定") } },
            dismissButton = { TextButton(onClick = { canvasResizeDialog = false }) {
                Text("取消")
            } })
    }
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
    panelCloseDialog?.let { pane ->
        val label = when (pane) {
            RightPane.COLOR -> "多功能拾色器"
            RightPane.LAYERS -> "图层"
            RightPane.BRUSHES -> "笔刷预设"
            RightPane.FOOTPRINTS -> "足迹"
        }
        AlertDialog(onDismissRequest = { panelCloseDialog = null },
            title = { Text("是否关闭“$label”面板？") },
            text = {
                Column {
                    Text("关闭后，可通过「设置 → 停靠面板 → $label」重新显示。")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = suppressPanelCloseConfirmation,
                            onCheckedChange = { suppressPanelCloseConfirmation = it })
                        Text("以后关闭面板时不再提示")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    changeDock("set_visible", pane, false, suppressPanelCloseConfirmation)
                    panelCloseDialog = null
                }) { Text("关闭面板") }
            },
            dismissButton = {
                TextButton(onClick = { panelCloseDialog = null }) { Text("取消") }
            })
    }
    remainingDialog?.let { item ->
        StudioMenuParameters(item,remainingDefaults,onDismiss={remainingDialog=null}) { parameters ->
            val captured=remainingCaptured
            remainingDialog=null
            executeRemaining(item,captured,parameters)
        }
    }
    remainingResult?.let { result -> StudioMenuResult(result) { remainingResult=null } }
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

}

private class StudioCanvas(context: Context) : View(context) {
    init { contentDescription = "画室画布，可使用所选工具绘画";isFocusableInTouchMode = true }
    var documentId: String = ""; set(value) {
        if (field != value) {
            field = value
            shapeInteraction.cancel(); freehandInteraction.cancel(); bezierInteraction.cancel(); calligraphyInteraction.cancel(); referenceInteraction.cancel();smartPatchInteraction.cancel();colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel(); assistantInteraction.cancel(); assistedBrushInteraction.cancel(); rasterBrushInteraction.cancel(); assistedStrokeRouting=false; shapeCreationContext = null
            points = JSONArray(); pathVertices = JSONArray()
            fitToWindow()
        }
    }
    var image: Bitmap? = null; set(value) {
        field = value
        publishZoom()
        invalidate()
    }
    var horizontalFitBias: Float = 0f
        set(value) {
            val normalized = value.coerceIn(-1f, 1f)
            if (field != normalized) {
                field = normalized
                invalidate()
            }
        }
    var scene: JSONObject? = null
    var sceneRevision: Int = 0
        set(value) {if(field!=value){field=value;rasterBrushInteraction.cancel();assistedBrushInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel()}}
    var shapeMultiple: Boolean = false
    var shapeBusy: Boolean = false
        set(value) {field=value;if(value){rasterBrushInteraction.cancel();assistedBrushInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel()}}
    var onShapeEdit: (String, JSONObject) -> Unit = { _, _ -> }
    var shapeCreationContext: JSONObject? = null
        private set
    private val shapeInteraction = StudioShapeInteraction(this)
    private val selectionBezierInteraction=StudioBezierSelectionInteraction(this)
    val selectionBezierHasDraft get()=selectionBezierInteraction.hasDraft
    var selectionBezierEditing=false
        set(value) {if(field!=value){field=value;selectionBezierInteraction.cancel();invalidate()}}
    var selectionBezierComponent=0
        set(value) {if(field!=value){field=value;selectionBezierInteraction.cancel();invalidate()}}
    var selectionBezierNode=0
    var selectionBezierMode="replace"
    var selectionBezierSmooth=false
    var onSelectionBezierNode: (Int)->Unit = {}
    var onSelectionBezierCreate: (JSONObject)->Unit = {}
    var onSelectionBezierEdit: (JSONObject)->Unit = {}
    private val colorSelectionInteraction=StudioColorSelectionInteraction()
    var colorSelectionOptions=ArtColorSelection.defaults()
    var onColorSelection: (JSONObject)->Unit = {}
    private val magneticSelectionInteraction=StudioMagneticSelectionInteraction(this)
    var magneticOptions=ArtMagneticSelection.defaults()
    var magneticSource: ArtMagneticSelection.Image?=null
        set(value) {field=value;magneticSelectionInteraction.source(value)}
    var onMagneticDraft: (Boolean)->Unit = {}
        set(value) {field=value;magneticSelectionInteraction.onDraft=value}
    var onMagneticComplete: (JSONObject)->Unit = {}
    private val comicPanelInteraction=StudioComicPanelInteraction()
    var comicPanelOptions=ArtComicPanels.defaults()
    var onComicPanel: (String,JSONObject)->Unit = {_,_->}
    private val encloseFillInteraction=StudioEncloseFillInteraction()
    var encloseFillOptions=ArtEncloseFill.defaults()
    var onEncloseFill: (JSONObject)->Unit = {}
    private val colorizeInteraction=StudioColorizeInteraction()
    var colorizeWidth=16f
    var colorizeErase=false
    var onColorizeCreate: (JSONObject)->Unit = {}
    var onColorizeStroke: (JSONObject)->Unit = {}
    private val smartPatchInteraction=StudioSmartPatchInteraction()
    var smartPatchOptions=ArtSmartPatch.info().getJSONObject("defaults")
    var onSmartPatch: (JSONObject)->Unit = {}
    private val assistantInteraction = StudioAssistantInteraction().apply { density=context.resources.displayMetrics.density }
    private val assistedBrushInteraction = StudioAssistedBrushInteraction(this).apply { density=context.resources.displayMetrics.density }
    private val rasterBrushInteraction=StudioAssistedBrushInteraction(this)
    var brushSettings=ArtBrush.defaults("ink")
    var brushAssetFile:((String)->java.io.File)?=null
    private val brushBitmapCache=linkedMapOf<String,Bitmap>()
    private val brushReader:(String)->Bitmap = {id ->
        brushBitmapCache[id] ?: run {
            val file=requireNotNull(brushAssetFile)(id)
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
            require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
            if(brushBitmapCache.size>=8) {val key=brushBitmapCache.keys.first();brushBitmapCache.remove(key)!!.recycle()}
            ArtImagePolicy.decodeAsset(file).also {brushBitmapCache[id]=it}
        }
    }
    var assistantAdding: Boolean = true
        set(value) { if(field!=value){field=value;assistantInteraction.cancel();invalidate()} }
    var assistantType: String = "ruler"
        set(value) { if(field!=value){field=value;assistantInteraction.cancel();invalidate()} }
    var onAssistantCreated: ()->Unit = {}
    var onAssistantEdit: (String,JSONObject)->Unit = {_,_->}
    var onAssistedStroke: (JSONObject)->Unit = {}
    private val referenceInteraction = StudioReferenceInteraction(this)
    var referenceBitmaps:Map<String,Bitmap> = emptyMap()
    var referenceMultiple = false
    var onReferenceEdit:(String,JSONObject)->Unit = {_,_->}
    private val calligraphyInteraction = StudioCalligraphyInteraction(this)
    var calligraphyOptions = JSONObject()
    var onCalligraphy: (JSONObject) -> Unit = {}
    private val freehandInteraction = StudioFreehandInteraction(this)
    private val bezierInteraction = StudioBezierInteraction(this)
    val bezierHasDraft get() = bezierInteraction.hasDraft
    var bezierEditing: Boolean = false
        set(value) { if(field != value) { field = value; bezierInteraction.cancel(); invalidate() } }
    var bezierNode: Int = 0
    var bezierNodeType: String = "symmetric"
    var bezierClosed: Boolean = false
    var onBezierNode: (Int) -> Unit = {}
    var onBezierCreate: (JSONObject) -> Unit = {}
    var freehandMode: String = "curve"
    var freehandPrecision: Float = 2f
    var freehandClosed: Boolean = false
    var onFreehand: (JSONObject) -> Unit = {}
    var layers: JSONArray? = null
    var selectedId: String = ""
        set(value) {if(field!=value){field=value;rasterBrushInteraction.cancel();assistedBrushInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel()}}
    var selection: JSONObject? = null
    var selectionVisible = true
    var tool: String = "ink"
        set(value) {
            if (field != value) {
                field = value
                rasterBrushInteraction.cancel();assistedBrushInteraction.cancel()
                shapeInteraction.cancel()
                freehandInteraction.cancel()
                calligraphyInteraction.cancel()
                referenceInteraction.cancel();smartPatchInteraction.cancel();colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel()
                assistantInteraction.cancel();assistedBrushInteraction.cancel();rasterBrushInteraction.cancel();assistedStrokeRouting=false
                bezierInteraction.cancel()
                shapeCreationContext = null
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
    var onText: (Double, Double, Boolean) -> Unit = { _, _, _ -> }
    var onStroke: (JSONArray) -> Unit = {}
    var onSelection: (JSONObject) -> Unit = {}
    var onCrop: (JSONObject) -> Unit = {}
    var onSampleColor: (Int) -> Unit = {}
    var onFill: (Int, Int) -> Unit = { _, _ -> }
    var onCursor: (Int, Int) -> Unit = { _, _ -> }
    var onMove: (Float, Float) -> Unit = { _, _ -> }
    private var zoom = 1f
        set(value) { field = value; publishZoom() }
    private var angle = 0f
    private var mirrored = false
    var gridVisible: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var pixelGridVisible: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }
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
    private var assistedStrokeRouting = false
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
    private fun fitScale(): Float {
        val bitmap = image ?: return 1f
        if (width == 0 || height == 0) return 1f
        return minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * 0.98f
    }
    private fun fittedCenterX(bitmap: Bitmap, fit: Float): Float {
        val fittedWidth = bitmap.width * fit
        return when {
            horizontalFitBias < 0f -> fittedWidth / 2f
            horizontalFitBias > 0f -> width - fittedWidth / 2f
            else -> width / 2f
        }
    }

    private fun publishZoom() {
        if (image == null || width <= 0 || height <= 0 || documentId.isBlank()) {
            ArtStudioViewControl.canvasZoom.value = null
            return
        }
        ArtStudioViewControl.canvasZoom.value = StudioCanvasZoom(documentId, zoom, fitScale())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        publishZoom()
    }

    private fun setZoomAt(target: Float, x: Float, y: Float) {
        val bitmap = image ?: return
        if (width <= 0 || height <= 0) return
        require(target.isFinite() && target > 0f)
        val previous = zoom
        zoom = target.coerceIn(0.1f, 16f)
        val factor = zoom / previous
        // Click, wheel and percentage inputs share anchor math for dock-biased canvases.
        val centerX = fittedCenterX(bitmap, fitScale())
        panX = x - centerX - factor * (x - centerX - panX)
        panY = y - height / 2f - factor * (y - height / 2f - panY)
        invalidate()
    }

    private fun zoomAt(x: Float, y: Float) {
        val mode = ArtStudioViewControl.state.value.zoomToolMode
        setZoomAt(if (mode == "in") zoom * 1.5f else zoom / 1.5f, x, y)
    }

    fun zoomIn() { setZoomAt(zoom * 1.25f, width / 2f, height / 2f) }
    fun zoomOut() { setZoomAt(zoom / 1.25f, width / 2f, height / 2f) }
    fun zoomTo100Percent() { zoomToPercent(100.0) }

    fun zoomToPercent(percent: Double) {
        require(percent.isFinite() && percent > 0.0)
        setZoomAt((percent / 100.0 / fitScale()).toFloat(), width / 2f, height / 2f)
    }

    fun zoomToSlider(position: Float) {
        require(position.isFinite())
        setZoomAt(0.1f * 160f.pow(position.coerceIn(0f, 1f)), width / 2f, height / 2f)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
            image != null && width > 0 && height > 0 &&
            event.x >= 0f && event.x < width && event.y >= 0f && event.y < height) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll.isFinite() && scroll != 0f) {
                // Positive VSCROLL is wheel-forward. Keep fractional/high-resolution deltas.
                setZoomAt(zoom * 1.25f.pow(scroll.coerceIn(-32f, 32f)), event.x, event.y)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
    private fun rotatedBounds(bitmap: Bitmap): Pair<Float, Float> {
        val radians = Math.toRadians(angle.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
        val s = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
        return Pair(bitmap.width * c + bitmap.height * s,
            bitmap.width * s + bitmap.height * c)
    }
    fun fitViewport() {
        val bitmap = image ?: return
        val (w, h) = rotatedBounds(bitmap)
        zoom = (minOf(width.toFloat() / w, height.toFloat() / h) * 0.98f /
            fitScale()).coerceIn(0.1f, 16f)
        panX = 0f; panY = 0f
        invalidate()
    }
    fun fitWidth() {
        val bitmap = image ?: return
        zoom = (width.toFloat() / rotatedBounds(bitmap).first / fitScale())
            .coerceIn(0.1f, 16f)
        panX = 0f; panY = 0f
        invalidate()
    }
    fun fitHeight() {
        val bitmap = image ?: return
        zoom = (height.toFloat() / rotatedBounds(bitmap).second / fitScale())
            .coerceIn(0.1f, 16f)
        panX = 0f; panY = 0f
        invalidate()
    }
    fun rotateBy(degrees: Float) { angle += degrees; invalidate() }
    fun resetRotation() { angle = 0f; invalidate() }
    fun toggleMirror() { mirrored = !mirrored; invalidate() }
    fun fitReferences(state:JSONObject?=scene) {
        val bitmap=image ?: return
        if(state==null || width<=0 || height<=0) return
        val bounds=android.graphics.RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat())
        val virtual=ArtReferences.selectionState(state)
        if(state.optBoolean("referencesVisible",true)) ArtShapes.items(ArtShapes.layer(virtual,ArtReferences.LAYER))
            .forEach { bounds.union(ArtShapes.bounds(it)) }
        angle=0f;mirrored=false
        val desired=minOf(width/bounds.width(),height/bounds.height())*0.98f
        zoom=(desired/fitScale()).coerceIn(0.1f,16f)
        val actual=fitScale()*zoom
        panX=width/2f-fittedCenterX(bitmap,fitScale())+(bitmap.width/2f-bounds.centerX())*actual
        panY=(bitmap.height/2f-bounds.centerY())*actual
        referenceInteraction.cancel();smartPatchInteraction.cancel();colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel();invalidate();publishZoom()
    }

    fun fitToWindow() {
        encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel()
        zoom = 1f
        angle = 0f
        panX = 0f
        panY = 0f
        invalidate()
    }
    fun resetDisplay() {
        mirrored = false
        fitToWindow()
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
        val fittedCenterX = fittedCenterX(bitmap, fit)
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postScale(fit * zoom, fit * zoom)
        matrix.postRotate(angle)
        matrix.postTranslate(fittedCenterX + panX, height / 2f + panY)
        if (mirrored) matrix.postScale(-1f, 1f, fittedCenterX + panX, height / 2f + panY)
        canvas.save(); canvas.concat(matrix)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), checkerPaint)
        canvas.restore()
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        if (gridVisible || (pixelGridVisible && fit * zoom >= 8f)) {
            canvas.save()
            canvas.concat(matrix)
            canvas.clipRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            val line = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f / (fit * zoom)
            }
            if (gridVisible) {
                line.color = Color.argb(145, 80, 160, 210)
                for (x in 0..bitmap.width step 64)
                    canvas.drawLine(x.toFloat(), 0f, x.toFloat(), bitmap.height.toFloat(), line)
                for (y in 0..bitmap.height step 64)
                    canvas.drawLine(0f, y.toFloat(), bitmap.width.toFloat(), y.toFloat(), line)
            }
            if (pixelGridVisible && fit * zoom >= 8f) {
                line.color = Color.argb(90, 70, 70, 70)
                for (x in 0..bitmap.width)
                    canvas.drawLine(x.toFloat(), 0f, x.toFloat(), bitmap.height.toFloat(), line)
                for (y in 0..bitmap.height)
                    canvas.drawLine(0f, y.toFloat(), bitmap.width.toFloat(), y.toFloat(), line)
            }
            canvas.restore()
        }
        scene?.let { referenceInteraction.draw(canvas,it,referenceBitmaps,matrix,tool=="reference_images") }
        scene?.let { ArtAssistants.draw(canvas,it,matrix,tool=="assistant",assistantInteraction.preview,
            resources.displayMetrics.density) }
        if(tool=="assistant")assistantInteraction.drawDraft(canvas,matrix)
        if(tool=="select_bezier")scene?.let {selectionBezierInteraction.draw(canvas,it,documentId,sceneRevision,matrix,
            selectionBezierEditing,selectionBezierComponent,selectionBezierNode)}
        if(tool=="select_magnetic")magneticSelectionInteraction.draw(canvas)
        if(tool=="comic_panel")comicPanelInteraction.draw(canvas)
        encloseFillInteraction.draw(canvas)
        colorizeInteraction.draw(canvas)
        smartPatchInteraction.draw(canvas)
        try {assistedBrushInteraction.draw(canvas,brushReader);rasterBrushInteraction.draw(canvas,brushReader)}
        catch(error:Exception) {assistedBrushInteraction.cancel();rasterBrushInteraction.cancel()
            Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show()}
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
        (selectionPreview ?: selection)?.takeIf { selectionVisible }?.let { selectedArea ->
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
        if (tool == "shape_select") {
            val state = scene
            val active = state?.let { ArtMenuOperations.layers(it).firstOrNull { l -> l.getString("id") == selectedId } }
            if (state != null && active?.getString("kind") == "vector" && ArtShapes.visible(state, active)) {
                val ids = ArtShapes.selected(state, selectedId)
                val editable = !ArtMenuOperations.isLocked(state, active) &&
                    ArtShapes.items(active).filter { it.getString("id") in ids }.none { it.getBoolean("locked") }
                val toScreen = Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state, active)) }
                shapeInteraction.draw(canvas, active, ids, toScreen, editable)
            }
        }
        if (tool == "vector_bezier") {
            val state = scene
            val active = state?.let { ArtMenuOperations.layers(it).firstOrNull { l -> l.getString("id") == selectedId } }
            if (state != null && active?.getString("kind") == "vector") {
                val toScreen = Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state, active)) }
                bezierInteraction.draw(canvas,state,documentId,sceneRevision,selectedId,toScreen,bezierEditing,bezierNode,bezierClosed)
            }
        }
        if (tool == "vector_calligraphy") {
            val state=scene
            val active=state?.let { ArtMenuOperations.layers(it).firstOrNull { l -> l.getString("id")==selectedId } }
            if(state!=null && active?.getString("kind")=="vector") {
                val toScreen=Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state,active)) }
                calligraphyInteraction.draw(canvas,toScreen,documentId,sceneRevision,selectedId)
            }
        }
        if (tool == "vector_freehand") {
            val state = scene
            val active = state?.let { ArtMenuOperations.layers(it).firstOrNull { l -> l.getString("id") == selectedId } }
            if (state != null && active?.getString("kind") == "vector") {
                val toScreen = Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state, active)) }
                freehandInteraction.draw(canvas, toScreen, documentId, sceneRevision, selectedId)
            }
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

    fun bezierCommand(command: String) {
        if (shapeBusy) return
        try { bezierInteraction.command(command,documentId,sceneRevision,selectedId,bezierClosed,onBezierCreate) }
        catch (error: Exception) {
            android.util.Log.e("ArtStudio","Bezier command failed",error)
            Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show()
        }
    }

    fun selectionBezierCommand(command: String) {
        if(shapeBusy)return
        try {selectionBezierInteraction.command(command,documentId,sceneRevision,onSelectionBezierCreate)}
        catch(error:Exception) {
            android.util.Log.e("ArtStudio","Bezier selection command failed",error)
            Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show()
        }
    }

    fun magneticCommand(action: String) {
        if(shapeBusy && action!="cancel")return
        try {magneticSelectionInteraction.command(action)}
        catch(error: Exception) {
            android.util.Log.e("ArtStudio","Magnetic selection command failed",error)
            Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show()
        }
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if(tool!="select_magnetic")return super.onHoverEvent(event)
        try {return magneticSelectionInteraction.touch(event,documentId,sceneRevision,selectedId,matrix,shapeBusy,magneticOptions,onMagneticComplete)}
        catch(error: Exception) {
            magneticSelectionInteraction.cancel()
            android.util.Log.e("ArtStudio","Magnetic selection hover failed",error)
            return true
        }
    }
    override fun onDetachedFromWindow() {
        rasterBrushInteraction.cancel();assistedBrushInteraction.cancel();brushBitmapCache.values.forEach {it.recycle()};brushBitmapCache.clear()
        magneticSelectionInteraction.dispose();colorSelectionInteraction.cancel()
        super.onDetachedFromWindow()
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if(tool=="select_magnetic") {
            when(keyCode) {
                android.view.KeyEvent.KEYCODE_ESCAPE->{magneticCommand("cancel");return true}
                android.view.KeyEvent.KEYCODE_ENTER->{magneticCommand("finish");return true}
                android.view.KeyEvent.KEYCODE_DEL,android.view.KeyEvent.KEYCODE_FORWARD_DEL->{magneticCommand("back");return true}
            }
        }
        if(tool in setOf("select_contiguous","select_similar") && keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {
            colorSelectionInteraction.cancel();invalidate();return true
        }
        if(tool=="comic_panel" && keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {
            comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();invalidate();return true
        }
        if(tool=="select_bezier") {
            if(keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {selectionBezierCommand("cancel");return true}
            if(!selectionBezierEditing && keyCode==android.view.KeyEvent.KEYCODE_ENTER) {selectionBezierCommand("finish");return true}
            if(!selectionBezierEditing && keyCode==android.view.KeyEvent.KEYCODE_DEL) {selectionBezierCommand("back");return true}
        }
        if(tool=="enclose_fill" && keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {
            encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel();invalidate();return true
        }
        if(tool=="colorize_mask" && keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {
            colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel();invalidate();return true
        }
        if(tool=="smart_patch" && keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {
            smartPatchInteraction.cancel();colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel();invalidate();return true
        }
        if(tool=="assistant") {
            if(keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {assistantInteraction.cancel();invalidate();return true}
            if(keyCode in setOf(android.view.KeyEvent.KEYCODE_DEL,android.view.KeyEvent.KEYCODE_FORWARD_DEL)) {
                val state=scene
                if(state!=null && !shapeBusy && ArtAssistants.selected(state).isNotEmpty())
                    onAssistantEdit("ASSISTANT_DELETE",JSONObject().put("documentId",documentId)
                        .put("expectedRevision",sceneRevision).put("id",ArtAssistants.selected(state)))
                return true
            }
        }
        if(tool=="reference_images" && keyCode in setOf(android.view.KeyEvent.KEYCODE_DEL,android.view.KeyEvent.KEYCODE_FORWARD_DEL)) {
            val state=scene
            if(state!=null && !shapeBusy && ArtReferences.ids(state).isNotEmpty()) onReferenceEdit("REFERENCE_DELETE",
                JSONObject().put("documentId",documentId).put("expectedRevision",sceneRevision)
                    .put("ids",JSONArray(ArtReferences.ids(state))))
            return true
        }
        if (tool == "vector_bezier" && keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            bezierCommand("cancel");return true
        }
        if (tool == "vector_bezier" && !bezierEditing) {
            when(keyCode) {
                android.view.KeyEvent.KEYCODE_ENTER -> { bezierCommand("finish");return true }
                android.view.KeyEvent.KEYCODE_ESCAPE -> { bezierCommand("cancel");return true }
                android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_FORWARD_DEL -> { bezierCommand("back");return true }
            }
        }
        return super.onKeyDown(keyCode,event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return true
        if (event.pointerCount >= 2) {
            multitouch = true
            shapeInteraction.cancel()
            freehandInteraction.cancel()
            calligraphyInteraction.cancel()
            referenceInteraction.cancel();smartPatchInteraction.cancel();colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel()
            assistantInteraction.cancel();assistedBrushInteraction.cancel();rasterBrushInteraction.cancel();assistedStrokeRouting=false
            bezierInteraction.interrupt()
            shapeCreationContext = null
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
        if(tool=="select_bezier") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus()
            val state=scene ?: return true
            try {
                return selectionBezierInteraction.touch(event,state,documentId,sceneRevision,matrix,shapeBusy,
                    selectionBezierEditing,selectionBezierComponent,selectionBezierNode,selectionBezierMode,selectionBezierSmooth,
                    onSelectionBezierNode,onSelectionBezierCreate,onSelectionBezierEdit)
            } catch(error:Exception) {
                selectionBezierInteraction.cancel()
                android.util.Log.e("ArtStudio","Bezier selection gesture failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool in setOf("select_contiguous","select_similar")) {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus()
            val state=scene ?: return true
            try {return colorSelectionInteraction.touch(event,state,documentId,sceneRevision,selectedId,matrix,shapeBusy,colorSelectionOptions,onColorSelection)}
            catch(error: Exception) {
                colorSelectionInteraction.cancel()
                android.util.Log.e("ArtStudio","Color selection gesture failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool=="select_magnetic") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus()
            try {return magneticSelectionInteraction.touch(event,documentId,sceneRevision,selectedId,matrix,shapeBusy,magneticOptions,onMagneticComplete)}
            catch(error: Exception) {
                magneticSelectionInteraction.cancel()
                android.util.Log.e("ArtStudio","Magnetic selection gesture failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool=="comic_panel") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus()
            val state=scene ?: return true
            try {
                return comicPanelInteraction.touch(event,state,documentId,sceneRevision,selectedId,matrix,shapeBusy,
                    comicPanelOptions,onComicPanel)
            } catch(error:Exception) {
                comicPanelInteraction.cancel()
                android.util.Log.e("ArtStudio","Comic panel gesture failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool=="enclose_fill") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus()
            val state=scene ?: return true
            try {
                return encloseFillInteraction.touch(event,state,documentId,sceneRevision,selectedId,matrix,shapeBusy,
                    encloseFillOptions,color,onEncloseFill)
            } catch(error:Exception) {
                encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel()
                android.util.Log.e("ArtStudio","Enclose fill gesture failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool=="colorize_mask") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus()
            val state=scene ?: return true
            try {
                return colorizeInteraction.touch(event,state,documentId,sceneRevision,selectedId,matrix,shapeBusy,
                    color,colorizeWidth,colorizeErase,onColorizeCreate,onColorizeStroke)
            } catch(error:Exception) {
                colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel()
                android.util.Log.e("ArtStudio","Colorize key stroke failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool=="smart_patch") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus()
            val state=scene ?: return true
            try {
                return smartPatchInteraction.touch(event,state,documentId,sceneRevision,selectedId,matrix,
                    shapeBusy,smartPatchOptions,onSmartPatch)
            } catch(error:Exception) {
                smartPatchInteraction.cancel();colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel()
                android.util.Log.e("ArtStudio","Smart patch mask failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool=="assistant") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN) requestFocus()
            val state=scene ?: return true
            try {
                return assistantInteraction.touch(event,state,documentId,sceneRevision,matrix,
                    assistantAdding,assistantType,shapeBusy,onAssistantEdit,onAssistantCreated)
            } catch(error:Exception) {
                assistantInteraction.cancel()
                android.util.Log.e("ArtStudio","Assistant edit failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        val assistantState=scene
        if(event.actionMasked==MotionEvent.ACTION_DOWN) assistedStrokeRouting =
            tool in ArtAssistants.brushTools && assistantState!=null && ArtAssistants.settings(assistantState).getBoolean("snapping")
        if(assistedStrokeRouting && assistantState!=null) {
            try {
                val options=JSONObject().put("tool",tool).put("color",color)
                    .put("width",brushWidth.toDouble()).put("opacity",opacity.toDouble())
                if(tool in ArtBrush.tools)options.put("brush",brushSettings)
                return assistedBrushInteraction.touch(event,assistantState,documentId,sceneRevision,selectedId,
                    matrix,shapeBusy,options,onAssistedStroke)
            } catch(error:Exception) {
                assistedBrushInteraction.cancel()
                android.util.Log.e("ArtStudio","Assisted stroke failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {
                if(event.actionMasked in setOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL))assistedStrokeRouting=false
                invalidate()
            }
        }
        if(tool in ArtBrush.tools) {
            val state=scene ?: return true
            try {
                return rasterBrushInteraction.touch(event,state,documentId,sceneRevision,selectedId,matrix,shapeBusy,
                    JSONObject().put("tool",tool).put("color",color).put("width",brushWidth.toDouble())
                        .put("opacity",opacity.toDouble()).put("brush",brushSettings),onAssistedStroke,false)
            } catch(error:Exception) {rasterBrushInteraction.cancel()
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            } finally {invalidate()}
        }
        if(tool=="reference_images") {
            if(event.actionMasked==MotionEvent.ACTION_DOWN) requestFocus()
            val state=scene ?: return true
            try {
                return referenceInteraction.touch(event,state,documentId,sceneRevision,matrix,
                    referenceMultiple,shapeBusy,onReferenceEdit)
            } catch(error:Exception) {
                referenceInteraction.cancel();smartPatchInteraction.cancel();colorizeInteraction.cancel();encloseFillInteraction.cancel();comicPanelInteraction.cancel();colorSelectionInteraction.cancel();magneticSelectionInteraction.cancel();selectionBezierInteraction.cancel()
                android.util.Log.e("ArtStudio","Reference interaction failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            }
        }
        if (tool == "vector_bezier") {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
            val state=scene
            if (state==null) { bezierInteraction.cancel();return true }
            try {
                val active=ArtShapes.layer(state,selectedId)
                val toScreen=Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state,active)) }
                return bezierInteraction.touch(event,state,documentId,sceneRevision,selectedId,toScreen,
                    bezierEditing,bezierNode,bezierNodeType,bezierClosed,shapeBusy,color,brushWidth,opacity,fillShape,
                    onBezierNode,{ onShapeEdit("SHAPE_SELECT",it) },onBezierCreate,{ onShapeEdit("SHAPE_PATH_EDIT",it) })
            } catch(error:Exception) {
                bezierInteraction.cancel()
                android.util.Log.e("ArtStudio","Bezier interaction failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            }
        }
        if (tool == "vector_calligraphy") {
            val state=scene
            if(state==null) { calligraphyInteraction.cancel();return true }
            try {
                val active=ArtShapes.layer(state,selectedId)
                val toScreen=Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state,active)) }
                return calligraphyInteraction.touch(event,toScreen,state,documentId,sceneRevision,selectedId,
                    shapeBusy,calligraphyOptions,onCalligraphy)
            } catch(error: Exception) {
                calligraphyInteraction.cancel()
                android.util.Log.e("ArtStudio","Vector calligraphy failed",error)
                Toast.makeText(context,error.message,Toast.LENGTH_SHORT).show();return true
            }
        }
        if (tool == "vector_freehand") {
            val state = scene
            if (state == null) { freehandInteraction.cancel(); return true }
            try {
                val active = ArtShapes.layer(state, selectedId)
                val toScreen = Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state, active)) }
                return freehandInteraction.touch(event, toScreen, state, documentId, sceneRevision, selectedId,
                    shapeBusy, freehandMode, freehandPrecision, freehandClosed, fillShape, color, brushWidth, opacity, onFreehand)
            } catch (error: Exception) {
                freehandInteraction.cancel()
                android.util.Log.e("ArtStudio", "Freehand path failed", error)
                Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
                return true
            }
        }
        if (tool == "shape_select") {
            val state = scene
            val active = state?.let { ArtMenuOperations.layers(it).firstOrNull { l -> l.getString("id") == selectedId } }
            if (state == null || active?.getString("kind") != "vector") {
                if (event.actionMasked == MotionEvent.ACTION_DOWN)
                    Toast.makeText(context, "请先新建或选择矢量图层，再选择形状", Toast.LENGTH_SHORT).show()
                return true
            }
            try {
                val toScreen = Matrix(matrix).apply { preConcat(ArtShapes.layerMatrix(state, active)) }
                return shapeInteraction.touch(event, local, toScreen, state, documentId,
                    sceneRevision, selectedId, shapeMultiple, shapeBusy, onShapeEdit)
            } catch (error: Exception) {
                shapeInteraction.cancel()
                android.util.Log.e("ArtStudio", "Shape interaction failed", error)
                Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
                return true
            }
        }
        if (tool == "svg_text") {
            if (event.actionMasked == MotionEvent.ACTION_UP && image != null &&
                xy[0] >= 0 && xy[1] >= 0 && xy[0] <= image!!.width && xy[1] <= image!!.height) {
                val all = layers
                val active = all?.let { (0 until it.length()).map { n -> it.getJSONObject(n) }
                    .firstOrNull { it.getString("id") == selectedId } }
                val source = active?.optJSONObject("text")
                val hit = active != null && active.optString("kind") == "text" && active.getBoolean("visible") &&
                    source != null && local[0] >= 0 && local[1] >= 0 &&
                    local[0] <= source.getInt("cacheWidth") && local[1] <= source.getInt("cacheHeight")
                onText(xy[0].toDouble(), xy[1].toDouble(), hit)
            }
            return true
        }
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
                val vectorLayer = scene?.let { ArtMenuOperations.layers(it).firstOrNull { l ->
                    l.getString("id") == selectedId && l.getString("kind") == "vector"
                } }
                if (vectorLayer != null && tool in ArtShapes.kinds) {
                    if (tool != "polygon" || pathVertices.length() == 0) {
                        // Capture at the first point, not release; another collaborator may edit meanwhile.
                        shapeCreationContext = JSONObject().put("documentId", documentId)
                            .put("expectedRevision", sceneRevision).put("layerId", selectedId)
                    }
                } else shapeCreationContext = null
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
                    "zoom" -> zoomAt(event.x, event.y)
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
                shapeCreationContext = null
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
