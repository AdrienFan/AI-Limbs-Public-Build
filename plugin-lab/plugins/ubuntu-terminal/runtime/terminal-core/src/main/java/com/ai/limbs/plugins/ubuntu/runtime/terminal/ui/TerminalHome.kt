package com.ai.limbs.plugins.ubuntu.runtime.terminal.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.CommandHistoryItem
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuIdleMode
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuIdlePolicy
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.plugins.ubuntu.runtime.terminal.data.UbuntuRuntimeState
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.canvas.CanvasTerminalOutput
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.canvas.CanvasTerminalScreen
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.canvas.RenderConfig
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.canvas.SHARED_TERMINAL_TAB_ID
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.canvas.TerminalTabRenderItem
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.limbs.plugins.ubuntu.runtime.terminal.TerminalEnv
import com.ai.limbs.plugins.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.plugins.ubuntu.runtime.terminal.utils.TerminalFontConfigManager
import com.ai.limbs.plugins.ubuntu.runtime.terminal.utils.VirtualKeyAction
import com.ai.limbs.plugins.ubuntu.runtime.terminal.utils.VirtualKeyboardButtonConfig
import com.ai.limbs.plugins.ubuntu.runtime.terminal.utils.VirtualKeyboardConfigManager
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.SyntaxColors
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.SyntaxHighlightingVisualTransformation
import com.ai.limbs.plugins.ubuntu.runtime.terminal.view.highlight
import androidx.compose.material.icons.filled.Settings
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalHome(
    env: TerminalEnv,
    useLocalImeHandling: Boolean = true,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestPortraitFullscreen: () -> Unit = {},
    onRequestLandscapeFullscreen: () -> Unit = {},
    onRequestExitFullscreen: () -> Unit = {}
) {
    val context = LocalContext.current
    val terminalManager = remember(context) { TerminalManager.getInstance(context) }
    val sharedHiddenState by terminalManager.sharedHiddenTerminalState.collectAsState()
    var sharedTerminalTabOpen by remember { mutableStateOf(false) }
    var sharedTerminalTabSelected by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    val density = LocalDensity.current
    val fontConfigManager = remember { TerminalFontConfigManager.getInstance(context) }
    val virtualKeyboardConfigManager = remember { VirtualKeyboardConfigManager.getInstance(context) }
    
    // 字体配置状态
    var fontConfig by remember { 
        mutableStateOf(fontConfigManager.loadRenderConfig())
    }
    var virtualKeyboardLayout by remember {
        mutableStateOf(virtualKeyboardConfigManager.loadLayout())
    }
    
    // 监听字体配置变化（当从设置界面返回时）
    LaunchedEffect(Unit) {
        // 每次进入时重新读取配置
        fontConfig = fontConfigManager.loadRenderConfig()
    }
    
    // 当组件重新组合时，检查配置是否变化并更新
    DisposableEffect(Unit) {
        val newConfig = fontConfigManager.loadRenderConfig()
        
        if (fontConfig != newConfig) {
            fontConfig = newConfig
        }
        
        onDispose { }
    }

    DisposableEffect(terminalManager) {
        terminalManager.registerUbuntuUiClient()
        onDispose {
            terminalManager.unregisterUbuntuUiClient()
        }
    }

    DisposableEffect(context, virtualKeyboardConfigManager) {
        val settingsPrefs =
            context.getSharedPreferences(VirtualKeyboardConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == VirtualKeyboardConfigManager.PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT) {
                virtualKeyboardLayout = virtualKeyboardConfigManager.loadLayout()
            }
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val rawImeBottomPx = if (useLocalImeHandling) WindowInsets.ime.getBottom(density) else 0
    val navigationBottomPx = WindowInsets.navigationBars.getBottom(density)
    val fullscreenImeBottomPx = rawImeBottomPx
    val standardImeBottomPx = (rawImeBottomPx - navigationBottomPx).coerceAtLeast(0)
    val committedFullscreenImeBottomPx = rememberSettledImeBottomPx(fullscreenImeBottomPx)
    val committedStandardImeBottomPx = rememberSettledImeBottomPx(standardImeBottomPx)

    // 命令输入框焦点控制
    val inputFocusRequester = remember { FocusRequester() }
    var pendingShowIme by remember { mutableStateOf(false) }

    LaunchedEffect(pendingShowIme) {
        if (pendingShowIme) {
            pendingShowIme = false
            // 模仿全屏模式，在焦点切换后稍微延迟再请求输入法
            delay(100)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(rootView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // 语法高亮
    val visualTransformation = remember { SyntaxHighlightingVisualTransformation() }

    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }

    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    
    // 非全屏模式下虚拟键盘显示状态
    var showVirtualKeyboard by remember { mutableStateOf(false) }
    var isDirectInputMode by remember { mutableStateOf(false) }

    val isSharedTerminalSelected = sharedTerminalTabOpen && sharedTerminalTabSelected

    LaunchedEffect(isSharedTerminalSelected) {
        if (isSharedTerminalSelected) {
            showVirtualKeyboard = false
            pendingShowIme = false
            keyboardController?.hide()
        }
    }

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    fun applyCtrlModifierChar(ch: Char): String? {
        return when {
            ch in 'a'..'z' || ch in 'A'..'Z' -> {
                val code = ch.uppercaseChar().code - 64
                code.toChar().toString()
            }
            ch == ' ' || ch == '@' -> "\u0000"
            ch == '[' -> "\u001b"
            ch == '\\' -> "\u001c"
            ch == ']' -> "\u001d"
            ch == '^' -> "\u001e"
            ch == '_' -> "\u001f"
            else -> null
        }
    }

    fun applyCtrlModifierToText(input: String): String {
        if (!ctrlActive) return input
        val sb = StringBuilder()
        input.forEach { ch ->
            val mapped = applyCtrlModifierChar(ch)
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString()
    }

    fun applyModifiers(input: String): String {
        var output = applyCtrlModifierToText(input)
        if (altActive) {
            output = if (output.startsWith("\u001b")) output else "\u001b$output"
        }
        return output
    }

    fun consumeModifiers() {
        if (ctrlActive) ctrlActive = false
        if (altActive) altActive = false
    }

    fun decodeVirtualKeyValue(rawValue: String): String {
        val output = StringBuilder()
        var index = 0

        while (index < rawValue.length) {
            val char = rawValue[index]
            if (char == '\\' && index + 1 < rawValue.length) {
                val next = rawValue[index + 1]
                when (next) {
                    'e' -> output.append('\u001b')
                    't' -> output.append('\t')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    '\\' -> output.append('\\')
                    else -> {
                        output.append('\\')
                        output.append(next)
                    }
                }
                index += 2
                continue
            }

            output.append(char)
            index++
        }

        return output.toString()
    }

    fun sendDirectInput(input: String) {
        if (isSharedTerminalSelected) return
        val output = if (ctrlActive || altActive) applyModifiers(input) else input
        env.onSendInput(output, false)
        if (ctrlActive || altActive) {
            consumeModifiers()
        }
    }

    fun sendCommandFromInput() {
        if (isSharedTerminalSelected) return
        val commandText = env.command
        if (ctrlActive || altActive) {
            val output = applyModifiers(commandText)
            env.onSendInput(output, false)
            env.onCommandChange("")
            consumeModifiers()
        } else {
            env.onSendInput(commandText, true)
        }
    }

    fun toggleDirectInputMode() {
        if (isSharedTerminalSelected) return
        isDirectInputMode = !isDirectInputMode
        if (isDirectInputMode) {
            // 进入直接输入模式：展开虚拟键盘，清空命令并收起系统键盘
            showVirtualKeyboard = true
            env.onCommandChange("")
            keyboardController?.hide()
        } else {
            // 退出直接输入模式：关闭虚拟键盘面板并恢复系统键盘
            showVirtualKeyboard = false
            keyboardController?.show()
        }
    }

    // 计算基于缩放因子的字体大小和间距
    val baseFontSize = 14.sp
    val fontSize = with(LocalDensity.current) {
        (baseFontSize.toPx() * scaleFactor).toSp()
    }
    val basePadding = 8.dp
    val padding = basePadding * scaleFactor

    // 获取当前 session 的 PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }
    val visibleTerminalSessions = remember(env.sessions) {
        env.sessions.filterNot { it.isBackground }
    }
    val terminalTabItems = remember(visibleTerminalSessions) {
        val closable = visibleTerminalSessions.size > 1
        visibleTerminalSessions.map { session ->
            TerminalTabRenderItem(
                id = session.id,
                title = session.title,
                canClose = closable
            )
        }
    }
    val tabItems = remember(terminalTabItems, sharedTerminalTabOpen, context) {
        if (sharedTerminalTabOpen) {
            terminalTabItems +
                TerminalTabRenderItem(
                    id = SHARED_TERMINAL_TAB_ID,
                    title = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.shared_terminal_tab_title),
                    canClose = true
                )
        } else {
            terminalTabItems
        }
    }
    val displayedTabId =
        if (isSharedTerminalSelected) SHARED_TERMINAL_TAB_ID else env.currentSessionId
    val onTabClickRequest: (String) -> Unit = { tabId ->
        if (tabId == SHARED_TERMINAL_TAB_ID) {
            sharedTerminalTabSelected = true
        } else {
            sharedTerminalTabSelected = false
            env.onSwitchSession(tabId)
        }
    }
    val onTabCloseRequest: (String) -> Unit = { sessionId ->
        if (sessionId == SHARED_TERMINAL_TAB_ID) {
            sharedTerminalTabOpen = false
            sharedTerminalTabSelected = false
        } else if (visibleTerminalSessions.size > 1) {
            sessionToDelete = sessionId
            showDeleteConfirmDialog = true
        }
    }
    val onNewTabRequest: () -> Unit = {
        sharedTerminalTabSelected = false
        env.onNewSession()
    }
    val onSharedViewRequest: () -> Unit = {
        sharedTerminalTabOpen = true
        sharedTerminalTabSelected = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (env.isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                if (env.ubuntuRuntimeState.phase == UbuntuRuntimePhase.RUNNING) {
                    CanvasTerminalScreen(
                        emulator = env.terminalEmulator,
                        modifier = Modifier.weight(1f),
                        config = fontConfig,
                        pty = currentPty,
                        imeAnimationOffsetPx = fullscreenImeBottomPx,
                        committedImeBottomInsetPx = committedFullscreenImeBottomPx,
                        onInput = { sendDirectInput(it) },
                        sessionId = env.currentSessionId,
                        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                        getScrollOffset = { id -> env.getScrollOffset(id) },
                        tabs = tabItems,
                        currentTabId = displayedTabId,
                        onTabClick = onTabClickRequest,
                        onTabClose = onTabCloseRequest,
                        onNewTab = onNewTabRequest,
                        onSharedView = onSharedViewRequest,
                        sharedViewActive = sharedHiddenState.isActive,
                        sharedViewSelected = isSharedTerminalSelected,
                        sharedViewState = sharedHiddenState
                    )
                } else {
                    UbuntuRuntimePanel(
                        runtimeState = env.ubuntuRuntimeState,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!isSharedTerminalSelected) {
                    Box(
                        modifier =
                            Modifier.graphicsLayer {
                                translationY = -fullscreenImeBottomPx.toFloat()
                            }
                    ) {
                        VirtualKeyboard(
                            onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                            onToggleCtrl = { ctrlActive = !ctrlActive },
                            onToggleAlt = { altActive = !altActive },
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            keyRows = virtualKeyboardLayout.rows,
                            fontSize = fontSize * 0.7f,
                            padding = padding * 0.5f
                        )
                    }
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
            ) {
                // Canvas输出区域（占满剩余空间）
                if (env.ubuntuRuntimeState.phase != UbuntuRuntimePhase.RUNNING) {
                    UbuntuRuntimePanel(
                        runtimeState = env.ubuntuRuntimeState,
                        modifier = Modifier.weight(1f)
                    )
                } else if (isDirectInputMode) {
                    // 直接输入模式：使用与全屏相同的 CanvasTerminalScreen，点击画布时由 CanvasTerminalView 自己弹出输入法
                    CanvasTerminalScreen(
                        emulator = env.terminalEmulator,
                        modifier = Modifier.weight(1f),
                        config = fontConfig,
                        pty = currentPty,
                        imeAnimationOffsetPx = standardImeBottomPx,
                        committedImeBottomInsetPx = committedStandardImeBottomPx,
                        onInput = { sendDirectInput(it) },
                        sessionId = env.currentSessionId,
                        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                        getScrollOffset = { id -> env.getScrollOffset(id) },
                        tabs = tabItems,
                        currentTabId = displayedTabId,
                        onTabClick = onTabClickRequest,
                        onTabClose = onTabCloseRequest,
                        onNewTab = onNewTabRequest,
                        onSharedView = onSharedViewRequest,
                        sharedViewActive = sharedHiddenState.isActive,
                        sharedViewSelected = isSharedTerminalSelected,
                        sharedViewState = sharedHiddenState
                    )
                } else {
                    // 嵌入聊天页时，外层已统一处理 IME；独立终端页则继续使用本地 IME 位移来驱动画布 viewport。
                    CanvasTerminalOutput(
                        emulator = env.terminalEmulator,
                        modifier = Modifier.weight(1f),
                        config = fontConfig,
                        pty = currentPty,
                        imeAnimationOffsetPx = standardImeBottomPx,
                        committedImeBottomInsetPx = committedStandardImeBottomPx,
                        onRequestShowKeyboard = {
                            inputFocusRequester.requestFocus()
                            pendingShowIme = true
                        },
                        sessionId = env.currentSessionId,
                        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                        getScrollOffset = { id -> env.getScrollOffset(id) },
                        tabs = tabItems,
                        currentTabId = displayedTabId,
                        onTabClick = onTabClickRequest,
                        onTabClose = onTabCloseRequest,
                        onNewTab = onNewTabRequest,
                        onSharedView = onSharedViewRequest,
                        sharedViewActive = sharedHiddenState.isActive,
                        sharedViewSelected = isSharedTerminalSelected,
                        sharedViewState = sharedHiddenState
                    )
                }

                Column(
                    modifier =
                        Modifier.graphicsLayer {
                            translationY = -standardImeBottomPx.toFloat()
                        }
                ) {
                    // 终端工具栏
                    TerminalToolbar(
                        onInterrupt = env::onInterrupt,
                        fontSize = fontSize * 0.8f,
                        padding = padding,
                        onNavigateToSetup = onNavigateToSetup,
                        onNavigateToSettings = onNavigateToSettings,
                        onRequestPortraitFullscreen = onRequestPortraitFullscreen,
                        onRequestLandscapeFullscreen = onRequestLandscapeFullscreen,
                        onRequestExitFullscreen = onRequestExitFullscreen,
                        runtimeState = env.ubuntuRuntimeState,
                        idlePolicy = env.ubuntuIdlePolicy,
                        onStartUbuntu = env::onStartUbuntu,
                        onStopUbuntu = {
                            env.onStopUbuntu { message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                        onIdlePolicyChange = env::onUbuntuIdlePolicyChange,
                        isDirectInputMode = isDirectInputMode,
                        readOnlySharedView = isSharedTerminalSelected,
                        showVirtualKeyboard = showVirtualKeyboard,
                        onToggleVirtualKeyboard = { showVirtualKeyboard = !showVirtualKeyboard },
                        onToggleInputMode = { toggleDirectInputMode() }
                    )

                    // 当前输入行：直接输入映射模式下隐藏（按图示仅保留工具栏右侧快捷按钮）
                    if (
                        !isDirectInputMode &&
                            !isSharedTerminalSelected &&
                            env.ubuntuRuntimeState.phase == UbuntuRuntimePhase.RUNNING
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(padding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.padding(end = padding * 0.5f),
                                color = Color(0xFF006400), // DarkGreen
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = getTruncatedPrompt(env.currentDirectory.ifEmpty { "$ " }),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize,
                                    modifier = Modifier.padding(horizontal = padding * 0.5f, vertical = padding * 0.1f)
                                )
                            }
                            BasicTextField(
                                value = env.command,
                                onValueChange = env::onCommandChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(inputFocusRequester),
                                enabled = true,
                                textStyle = TextStyle(
                                    color = SyntaxColors.commandDefault,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize
                                ),
                                cursorBrush = SolidColor(Color.Green),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    sendCommandFromInput()
                                })
                            )
                            Surface(
                                modifier = Modifier
                                    .padding(start = padding * 0.5f)
                                    .clickable { showVirtualKeyboard = !showVirtualKeyboard },
                                color = if (showVirtualKeyboard) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "⌨",
                                    color = Color.White,
                                    fontFamily = FontFamily.Default,
                                    fontSize = fontSize * 1.2f,
                                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .padding(start = padding * 0.5f)
                                    .clickable { toggleDirectInputMode() },
                                color = if (isDirectInputMode) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "⇄",
                                    color = Color.White,
                                    fontFamily = FontFamily.Default,
                                    fontSize = fontSize * 1.1f,
                                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                                )
                            }
                        }
                    }

                    if (showVirtualKeyboard && !isSharedTerminalSelected) {
                        VirtualKeyboard(
                            onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                            onToggleCtrl = { ctrlActive = !ctrlActive },
                            onToggleAlt = { altActive = !altActive },
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            keyRows = virtualKeyboardLayout.rows,
                            fontSize = fontSize * 0.7f,
                            padding = padding * 0.5f
                        )
                    }
                }
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val context = LocalContext.current
        val sessionTitle = env.sessions.find { it.id == sessionToDelete }?.title ?: context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.unknown_session)

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.confirm_delete_session),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.delete_session_message, sessionTitle),
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            env.onCloseSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.delete),
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.cancel),
                        color = Color.White
                    )
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }
}

private fun getTruncatedPrompt(prompt: String, maxLength: Int = 16): String {
    val trimmed = prompt.trimEnd()
    return if (trimmed.length > maxLength) {
        "..." + trimmed.takeLast(maxLength - 3)
    } else {
        trimmed
    }
}

@Composable
private fun rememberSettledImeBottomPx(targetBottomPx: Int, settleDelayMs: Long = 160L): Int {
    var settledBottomPx by remember { mutableStateOf(targetBottomPx) }
    var previousTargetBottomPx by remember { mutableStateOf(targetBottomPx) }

    LaunchedEffect(targetBottomPx) {
        val previousTarget = previousTargetBottomPx
        previousTargetBottomPx = targetBottomPx

        if (targetBottomPx < previousTarget) {
            // 键盘收起时反过来处理：先释放 viewport，再让内容跟随原始 inset 下移。
            settledBottomPx = 0
            return@LaunchedEffect
        }

        delay(settleDelayMs)
        settledBottomPx = targetBottomPx
    }

    return settledBottomPx
}

@Composable
private fun UbuntuRuntimePanel(
    runtimeState: UbuntuRuntimeState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val statusText =
        when (runtimeState.phase) {
            UbuntuRuntimePhase.STOPPED ->
                context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_stopped_message)
            UbuntuRuntimePhase.STARTING ->
                context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_starting_message)
            UbuntuRuntimePhase.RUNNING ->
                context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_running_message)
            UbuntuRuntimePhase.STOPPING ->
                context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_stopping_message)
            UbuntuRuntimePhase.ERROR ->
                runtimeState.error
                    ?: context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_error_message)
        }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_title),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = statusText,
                color = if (runtimeState.phase == UbuntuRuntimePhase.ERROR) Color(0xFFFF8A80) else Color.Gray,
                fontSize = 15.sp
            )
            if (
                runtimeState.phase == UbuntuRuntimePhase.STARTING ||
                    runtimeState.phase == UbuntuRuntimePhase.STOPPING
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = Color(0xFF3D7EFF)
                )
            }
        }
    }
}

@Composable
private fun TerminalToolbar(
    onInterrupt: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestPortraitFullscreen: () -> Unit,
    onRequestLandscapeFullscreen: () -> Unit,
    onRequestExitFullscreen: () -> Unit,
    runtimeState: UbuntuRuntimeState,
    idlePolicy: UbuntuIdlePolicy,
    onStartUbuntu: () -> Unit,
    onStopUbuntu: () -> Unit,
    onIdlePolicyChange: (UbuntuIdlePolicy) -> Unit,
    isDirectInputMode: Boolean,
    readOnlySharedView: Boolean,
    showVirtualKeyboard: Boolean,
    onToggleVirtualKeyboard: () -> Unit,
    onToggleInputMode: () -> Unit
) {
    val context = LocalContext.current
    var showIdlePolicyDialog by remember { mutableStateOf(false) }
    var showPresentationDialog by remember { mutableStateOf(false) }
    val runtimeActionText =
        when (runtimeState.phase) {
            UbuntuRuntimePhase.STOPPED -> com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_start
            UbuntuRuntimePhase.STARTING -> com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_starting
            UbuntuRuntimePhase.RUNNING -> com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_stop
            UbuntuRuntimePhase.STOPPING -> com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_stopping
            UbuntuRuntimePhase.ERROR -> com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_runtime_retry
        }
    val runtimeActionEnabled =
        runtimeState.phase != UbuntuRuntimePhase.STARTING &&
            runtimeState.phase != UbuntuRuntimePhase.STOPPING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(padding * 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (
                !isDirectInputMode &&
                    !readOnlySharedView &&
                    runtimeState.phase == UbuntuRuntimePhase.RUNNING
            ) {
                // Ctrl+C 中断按钮
                Surface(
                    modifier = Modifier.clickable { onInterrupt() },
                    color = Color(0xFF4A4A4A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
                    ) {
                        Text(
                            text = "Ctrl+C",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.interrupt),
                            color = Color.Gray,
                            fontFamily = FontFamily.Default,
                            fontSize = fontSize * 0.9f
                        )
                    }
                }

                // 分隔线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(padding * 1.5f)
                        .background(Color(0xFF3A3A3A))
                )
            }

            Spacer(Modifier.weight(1f))

            Surface(
                modifier =
                    Modifier.clickable(enabled = runtimeActionEnabled) {
                        if (runtimeState.phase == UbuntuRuntimePhase.RUNNING) {
                            onStopUbuntu()
                        } else {
                            onStartUbuntu()
                        }
                    },
                color =
                    when (runtimeState.phase) {
                        UbuntuRuntimePhase.RUNNING -> Color(0xFF7A3434)
                        UbuntuRuntimePhase.ERROR -> Color(0xFF6D4C41)
                        UbuntuRuntimePhase.STARTING,
                        UbuntuRuntimePhase.STOPPING -> Color(0xFF303030)
                        UbuntuRuntimePhase.STOPPED -> Color(0xFF245B3A)
                    },
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = context.getString(runtimeActionText),
                    color = if (runtimeActionEnabled) Color.White else Color.Gray,
                    fontFamily = FontFamily.Default,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    modifier =
                        Modifier.padding(
                            horizontal = padding * 0.75f,
                            vertical = padding * 0.4f
                        )
                )
            }

            Surface(
                modifier = Modifier.clickable { showIdlePolicyDialog = true },
                color =
                    if (idlePolicy.timeoutMinutes != null) {
                        Color(0xFF245B3A)
                    } else {
                        Color(0xFF7A3434)
                    },
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.25f),
                    modifier =
                        Modifier.padding(
                            horizontal = padding * 0.6f,
                            vertical = padding * 0.4f
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription =
                            context.getString(
                                com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_button
                            ),
                        tint = Color.White,
                        modifier = Modifier.size(padding * 1.6f)
                    )
                    Text(
                        text = idlePolicy.timeoutMinutes?.let { "${it}m" } ?: "∞",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 环境配置按钮
            Surface(
                modifier = Modifier.clickable(enabled = !readOnlySharedView) {
                    onNavigateToSetup()
                },
                color = if (readOnlySharedView) Color(0xFF303030) else Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.environment_setup),
                        color = if (readOnlySharedView) Color.Gray else Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isDirectInputMode && !readOnlySharedView) {
                Surface(
                    modifier = Modifier.clickable { onToggleVirtualKeyboard() },
                    color = if (showVirtualKeyboard) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "⌨",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 1.15f,
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.35f)
                    )
                }
                Surface(
                    modifier = Modifier.clickable { onToggleInputMode() },
                    color = Color(0xFF4A4A4A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "⇄",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 1.05f,
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                    )
                }
            }

            // 页面显示模式：由 Ubuntu 自己提供交互，Host 只负责执行沉浸式显示。
            Surface(
                modifier = Modifier.clickable { showPresentationDialog = true },
                color = Color(0xFF3A3A3A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "⛶",
                    color = Color.White,
                    fontFamily = FontFamily.Default,
                    fontSize = fontSize * 1.2f,
                    modifier = Modifier.padding(horizontal = padding * 0.7f, vertical = padding * 0.35f)
                )
            }

            // 设置按钮
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.settings),
                tint = Color.Gray,
                modifier = Modifier
                    .clickable { onNavigateToSettings() }
                    .padding(start = padding)
                    .size(padding * 2.5f)
            )
        }
    }

    if (showIdlePolicyDialog) {
        UbuntuIdlePolicyDialog(
            currentPolicy = idlePolicy,
            onDismiss = { showIdlePolicyDialog = false },
            onApply = { policy ->
                onIdlePolicyChange(policy)
                showIdlePolicyDialog = false
            }
        )
    }

    if (showPresentationDialog) {
        AlertDialog(
            onDismissRequest = { showPresentationDialog = false },
            title = {
                Text(context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.fullscreen_dialog_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showPresentationDialog = false
                            onRequestPortraitFullscreen()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.fullscreen_portrait))
                    }
                    TextButton(
                        onClick = {
                            showPresentationDialog = false
                            onRequestLandscapeFullscreen()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.fullscreen_landscape))
                    }
                    TextButton(
                        onClick = {
                            showPresentationDialog = false
                            onRequestExitFullscreen()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.fullscreen_exit))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresentationDialog = false }) {
                    Text(context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.cancel))
                }
            },
            containerColor = Color(0xFF2D2D2D)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UbuntuIdlePolicyDialog(
    currentPolicy: UbuntuIdlePolicy,
    onDismiss: () -> Unit,
    onApply: (UbuntuIdlePolicy) -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember(currentPolicy) { mutableStateOf(currentPolicy.mode) }
    var customMinutesText by remember(currentPolicy) {
        mutableStateOf(currentPolicy.customMinutes.toString())
    }
    var expanded by remember { mutableStateOf(false) }
    val customMinutes = customMinutesText.toIntOrNull()
    val customIsValid =
        customMinutes != null &&
            customMinutes in
                UbuntuIdlePolicy.MIN_CUSTOM_MINUTES..UbuntuIdlePolicy.MAX_CUSTOM_MINUTES
    val canApply = selectedMode != UbuntuIdleMode.CUSTOM || customIsValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                context.getString(
                    com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_dialog_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = ubuntuIdleModeLabel(context, selectedMode),
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text(
                                context.getString(
                                    com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_policy_label
                                )
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        UbuntuIdleMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(ubuntuIdleModeLabel(context, mode)) },
                                onClick = {
                                    selectedMode = mode
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedMode == UbuntuIdleMode.CUSTOM) {
                    OutlinedTextField(
                        value = customMinutesText,
                        onValueChange = { value ->
                            customMinutesText = value.filter { it.isDigit() }
                        },
                        singleLine = true,
                        isError = !customIsValid,
                        label = {
                            Text(
                                context.getString(
                                    com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_custom_minutes
                                )
                            )
                        },
                        supportingText = {
                            Text(
                                context.getString(
                                    if (customIsValid) {
                                        com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_custom_hint
                                    } else {
                                        com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_custom_error
                                    }
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text =
                        context.getString(
                            com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_safety_note
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val appliedCustomMinutes =
                        if (selectedMode == UbuntuIdleMode.CUSTOM) {
                            requireNotNull(customMinutes)
                        } else {
                            currentPolicy.customMinutes
                        }
                    onApply(
                        UbuntuIdlePolicy(
                            mode = selectedMode,
                            customMinutes = appliedCustomMinutes
                        )
                    )
                },
                enabled = canApply
            ) {
                Text(
                    context.getString(
                        com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_apply
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    context.getString(
                        com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_cancel
                    )
                )
            }
        }
    )
}

private fun ubuntuIdleModeLabel(context: Context, mode: UbuntuIdleMode): String =
    when (mode) {
        UbuntuIdleMode.KEEP_RUNNING ->
            context.getString(
                com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_keep_running
            )
        UbuntuIdleMode.MINUTES_10 ->
            context.getString(
                com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_minutes,
                10
            )
        UbuntuIdleMode.MINUTES_15 ->
            context.getString(
                com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_minutes,
                15
            )
        UbuntuIdleMode.MINUTES_30 ->
            context.getString(
                com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_minutes,
                30
            )
        UbuntuIdleMode.MINUTES_60 ->
            context.getString(
                com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_minutes,
                60
            )
        UbuntuIdleMode.CUSTOM ->
            context.getString(com.ai.limbs.plugins.ubuntu.runtime.terminal.R.string.ubuntu_idle_custom)
    }

@Composable
private fun VirtualKeyboard(
    onKeyPress: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    keyRows: List<List<VirtualKeyboardButtonConfig>>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            verticalArrangement = Arrangement.spacedBy(padding * 0.5f)
        ) {
            keyRows.forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
                ) {
                    rowKeys.forEach { keyConfig ->
                        val isActive = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> ctrlActive
                            VirtualKeyAction.TOGGLE_ALT -> altActive
                            VirtualKeyAction.SEND_TEXT -> false
                        }
                        val clickOverride = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> onToggleCtrl
                            VirtualKeyAction.TOGGLE_ALT -> onToggleAlt
                            VirtualKeyAction.SEND_TEXT -> null
                        }

                        KeyButton(
                            label = keyConfig.label,
                            key = keyConfig.value,
                            fontSize = fontSize,
                            padding = padding,
                            onKeyPress = onKeyPress,
                            modifier = Modifier.weight(1f),
                            isActive = isActive,
                            onClickOverride = clickOverride
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    key: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClickOverride: (() -> Unit)? = null
) {
    val backgroundColor = if (isActive) Color(0xFF2563EB) else Color(0xFF3A3A3A)
    Surface(
        modifier = modifier
            .clickable { onClickOverride?.invoke() ?: onKeyPress(key) },
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = padding * 0.5f, vertical = padding * 0.8f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
} 
