package com.ai.limbs.plugins.systemenvironment

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiComponentIds
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdleMode
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdlePolicy
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimePhase
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class SystemEnvironmentCenterPageProvider(
    private val host: InProcessPluginHost,
    private val registry: SystemEnvironmentSubsystemRegistry
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SystemEnvironmentCenterPage(host, registry, sharedUi)
                }
            }
        }
}

private enum class CenterRoute { DISPLAY, ENVIRONMENT_CONFIG }

@Composable
private fun SystemEnvironmentCenterPage(
    host: InProcessPluginHost,
    registry: SystemEnvironmentSubsystemRegistry,
    sharedUi: InProcessSharedUiHost
) {
    val mounted by registry.mounted.collectAsState()
    val foregroundId by registry.foregroundExtensionId.collectAsState()
    var route by remember { mutableStateOf(CenterRoute.DISPLAY) }

    when (route) {
        CenterRoute.DISPLAY -> SystemEnvironmentDisplayPage(
            host = host,
            current = mounted.firstOrNull { it.extensionId == foregroundId },
            onOpenConfig = { route = CenterRoute.ENVIRONMENT_CONFIG }
        )
        CenterRoute.ENVIRONMENT_CONFIG -> EnvironmentConfigPage(
            mounted = mounted,
            foregroundId = foregroundId,
            sharedUi = sharedUi,
            onSelect = registry::setForeground,
            onBack = { route = CenterRoute.DISPLAY }
        )
    }
}

private const val CHASSIS_RUNTIME_CONTROLS_SLOT_TAG =
    "ai_limbs.system_environment.chassis.runtime_controls@1"
private const val CHASSIS_PRESENTATION_CONTROL_SLOT_TAG =
    "ai_limbs.system_environment.chassis.presentation_control@1"
private const val CHASSIS_RUNTIME_CONTROLS_VIEW_TAG =
    "ai_limbs.system_environment.chassis.runtime_controls.bound@1"
private const val CHASSIS_PRESENTATION_CONTROL_VIEW_TAG =
    "ai_limbs.system_environment.chassis.presentation_control.bound@1"

@Composable
private fun SystemEnvironmentDisplayPage(
    host: InProcessPluginHost,
    current: MountedSystemEnvironment?,
    onOpenConfig: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有前台系统环境", color = Color.White)
                    Text(
                        "请在环境配置中安装或选择一个已激活环境。",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onOpenConfig, modifier = Modifier.padding(top = 12.dp)) {
                        Text("打开环境配置")
                    }
                }
            }
        } else {
            key(current.extensionId) {
                ActiveEnvironment(host, current, onOpenConfig)
            }
        }
    }
}

@Composable
private fun ActiveEnvironment(
    host: InProcessPluginHost,
    current: MountedSystemEnvironment,
    onOpenConfig: () -> Unit
) {
    AndroidView(
        factory = { context ->
            current.contribution.display.createView(context).also { displayView ->
                attachChassisControlsWhenReady(
                    root = displayView,
                    host = host,
                    current = current,
                    onOpenConfig = onOpenConfig
                )
            }
        },
        update = { displayView ->
            attachChassisControlsWhenReady(
                root = displayView,
                host = host,
                current = current,
                onOpenConfig = onOpenConfig
            )
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun attachChassisControlsWhenReady(
    root: View,
    host: InProcessPluginHost,
    current: MountedSystemEnvironment,
    onOpenConfig: () -> Unit,
    attempt: Int = 0
) {
    val runtimeSlot = root.findViewWithTag<View>(CHASSIS_RUNTIME_CONTROLS_SLOT_TAG) as? FrameLayout
    val presentationSlot =
        root.findViewWithTag<View>(CHASSIS_PRESENTATION_CONTROL_SLOT_TAG) as? FrameLayout
    if (runtimeSlot == null || presentationSlot == null) {
        if (attempt < 60) {
            root.postDelayed(
                {
                    attachChassisControlsWhenReady(
                        root,
                        host,
                        current,
                        onOpenConfig,
                        attempt + 1
                    )
                },
                16L
            )
        }
        return
    }
    if (runtimeSlot.findViewWithTag<View>(CHASSIS_RUNTIME_CONTROLS_VIEW_TAG) == null) {
        val controls = ComposeView(host.createPluginContext(runtimeSlot.context)).apply {
            tag = CHASSIS_RUNTIME_CONTROLS_VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ChassisRuntimeControls(current, onOpenConfig)
                }
            }
        }
        runtimeSlot.removeAllViews()
        runtimeSlot.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }
    if (presentationSlot.findViewWithTag<View>(CHASSIS_PRESENTATION_CONTROL_VIEW_TAG) == null) {
        val control = ComposeView(host.createPluginContext(presentationSlot.context)).apply {
            tag = CHASSIS_PRESENTATION_CONTROL_VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ChassisPresentationControl(host)
                }
            }
        }
        presentationSlot.removeAllViews()
        presentationSlot.addView(
            control,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }
}

@Composable
private fun ChassisRuntimeControls(
    current: MountedSystemEnvironment,
    onOpenConfig: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by current.contribution.runtime.state.collectAsState()
    val idlePolicy by current.contribution.runtime.idlePolicy.collectAsState()
    var showIdleDialog by remember { mutableStateOf(false) }
    val enabled =
        state.phase != SystemEnvironmentRuntimePhase.STARTING &&
            state.phase != SystemEnvironmentRuntimePhase.STOPPING
    val actionText = when (state.phase) {
        SystemEnvironmentRuntimePhase.STOPPED -> "启动 ${current.displayName}"
        SystemEnvironmentRuntimePhase.STARTING -> "启动中"
        SystemEnvironmentRuntimePhase.RUNNING -> "停止 ${current.displayName}"
        SystemEnvironmentRuntimePhase.STOPPING -> "停止中"
        SystemEnvironmentRuntimePhase.FAILED -> "重试 ${current.displayName}"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.clickable(enabled = enabled) {
                scope.launch {
                    report(context) {
                        if (state.phase == SystemEnvironmentRuntimePhase.RUNNING) {
                            current.contribution.runtime.stop()
                        } else {
                            current.contribution.runtime.start()
                        }
                    }
                }
            },
            color = when (state.phase) {
                SystemEnvironmentRuntimePhase.RUNNING -> Color(0xFF7A3434)
                SystemEnvironmentRuntimePhase.FAILED -> Color(0xFF6D4C41)
                SystemEnvironmentRuntimePhase.STARTING,
                SystemEnvironmentRuntimePhase.STOPPING -> Color(0xFF303030)
                SystemEnvironmentRuntimePhase.STOPPED -> Color(0xFF245B3A)
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ) {
            Text(
                text = actionText,
                color = if (enabled) Color.White else Color.Gray,
                fontFamily = FontFamily.Default,
                fontSize = 11.2.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.2.dp)
            )
        }
        Surface(
            modifier = Modifier.clickable { showIdleDialog = true },
            color = if (idlePolicy.timeoutMinutes != null) Color(0xFF245B3A) else Color(0xFF7A3434),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.8.dp, vertical = 3.2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "空闲策略",
                    tint = Color.White,
                    modifier = Modifier.size(12.8.dp)
                )
                Text(
                    text = idlePolicy.timeoutMinutes?.let { "${it}m" } ?: "∞",
                    color = Color.White,
                    fontSize = 11.2.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Surface(
            modifier = Modifier.clickable { onOpenConfig() },
            color = Color(0xFF4A4A4A),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ) {
            Text(
                text = "环境配置",
                color = Color.White,
                fontSize = 11.2.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.2.dp)
            )
        }
    }
    if (showIdleDialog) {
        ChassisIdlePolicyDialog(
            currentPolicy = idlePolicy,
            onDismiss = { showIdleDialog = false },
            onApply = { policy ->
                showIdleDialog = false
                scope.launch { report(context) { current.contribution.runtime.setIdlePolicy(policy) } }
            }
        )
    }
}

@Composable
private fun ChassisPresentationControl(host: InProcessPluginHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.clickable { showDialog = true },
        color = Color(0xFF3A3A3A),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "⛶",
            color = Color.White,
            fontFamily = FontFamily.Default,
            fontSize = 13.4.sp,
            modifier = Modifier.padding(horizontal = 5.6.dp, vertical = 2.8.dp)
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("页面显示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresentationDialogAction("竖屏全屏") {
                        showDialog = false
                        scope.launch { requestPresentationMode(host, context, "fullscreen_portrait") }
                    }
                    PresentationDialogAction("横屏全屏") {
                        showDialog = false
                        scope.launch { requestPresentationMode(host, context, "fullscreen_landscape") }
                    }
                    PresentationDialogAction("退出全屏") {
                        showDialog = false
                        scope.launch { requestPresentationMode(host, context, "normal") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            },
            containerColor = Color(0xFF2D2D2D)
        )
    }
}

@Composable
private fun PresentationDialogAction(label: String, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

private suspend fun requestPresentationMode(
    host: InProcessPluginHost,
    context: Context,
    mode: String
) {
    report(context) {
        host.invokeHostCapability(
            "host.ui.presentation@1",
            JSONObject()
                .put("operation", "set_mode")
                .put("screen_id", SystemEnvironmentContract.SCREEN_ID)
                .put("mode", mode)
                .toString()
        )
    }
}

@Composable
private fun ChassisIdlePolicyDialog(
    currentPolicy: SystemEnvironmentIdlePolicy,
    onDismiss: () -> Unit,
    onApply: (SystemEnvironmentIdlePolicy) -> Unit
) {
    var selectedMode by remember(currentPolicy) { mutableStateOf(currentPolicy.mode) }
    var customMinutesText by remember(currentPolicy) {
        mutableStateOf(currentPolicy.customMinutes.toString())
    }
    val customMinutes = customMinutesText.toIntOrNull()
    val customIsValid = customMinutes != null && customMinutes in 1..1440
    val canApply = selectedMode != SystemEnvironmentIdleMode.CUSTOM || customIsValid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("空闲关机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SystemEnvironmentIdleMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMode = mode }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode }
                        )
                        Text(idleModeLabel(mode))
                    }
                }
                if (selectedMode == SystemEnvironmentIdleMode.CUSTOM) {
                    OutlinedTextField(
                        value = customMinutesText,
                        onValueChange = { customMinutesText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("自定义分钟数（1-1440）") },
                        isError = !customIsValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canApply,
                onClick = {
                    onApply(
                        SystemEnvironmentIdlePolicy(
                            mode = selectedMode,
                            customMinutes = customMinutes ?: currentPolicy.customMinutes
                        )
                    )
                }
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Color(0xFF2D2D2D)
    )
}

private fun idleModeLabel(mode: SystemEnvironmentIdleMode): String = when (mode) {
    SystemEnvironmentIdleMode.KEEP_RUNNING -> "保持运行"
    SystemEnvironmentIdleMode.MINUTES_10 -> "10 分钟"
    SystemEnvironmentIdleMode.MINUTES_15 -> "15 分钟"
    SystemEnvironmentIdleMode.MINUTES_30 -> "30 分钟"
    SystemEnvironmentIdleMode.MINUTES_60 -> "60 分钟"
    SystemEnvironmentIdleMode.CUSTOM -> "自定义"
}

@Composable
private fun EnvironmentConfigPage(
    mounted: List<MountedSystemEnvironment>,
    foregroundId: String?,
    sharedUi: InProcessSharedUiHost,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit
) {
    val installerId = InProcessSharedUiComponentIds.CHILD_EXTENSION_INSTALLER
    val listId = InProcessSharedUiComponentIds.CHILD_EXTENSION_LIST
    val installerParameters = remember {
        JSONObject()
            .put("label", "+ 添加系统环境")
            .put("parent_plugin_id", SystemEnvironmentContract.PARENT_PLUGIN_ID)
            .put("point", SystemEnvironmentContract.EXTENSION_POINT)
            .put("api", SystemEnvironmentContract.API_VERSION)
            .toString()
    }
    val listParameters = remember {
        JSONObject()
            .put("parent_plugin_id", SystemEnvironmentContract.PARENT_PLUGIN_ID)
            .put("point", SystemEnvironmentContract.EXTENSION_POINT)
            .put("api", SystemEnvironmentContract.API_VERSION)
            .toString()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Text(
                "环境配置",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }

        Text(
            "已激活系统环境",
            color = Color.White,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )
        if (mounted.isEmpty()) {
            Text("当前没有运行中的子系统插件。", color = Color.Gray)
        } else {
            mounted.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName)
                            Text(
                                "${item.extensionId} · ${item.version}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (foregroundId == item.extensionId) {
                            Text("正在显示", color = MaterialTheme.colorScheme.primary)
                        } else {
                            OutlinedButton(onClick = { onSelect(item.extensionId) }) {
                                Text("设为显示")
                            }
                        }
                    }
                }
            }
        }

        Text(
            "安装与生命周期",
            color = Color.White,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )
        if (sharedUi.supports(installerId)) {
            AndroidView(
                factory = { sharedUi.createComponent(installerId, installerParameters) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            )
        } else {
            Text("Plugin Center 未提供子插件安装组件。", color = Color.Red)
        }
        if (sharedUi.supports(listId)) {
            AndroidView(
                factory = { sharedUi.createComponent(listId, listParameters) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Text("Plugin Center 未提供子插件列表组件。", color = Color.Red)
        }
    }
}

private suspend fun report(context: Context, action: suspend () -> Unit) {
    runCatching { action() }.onFailure { error ->
        Toast.makeText(
            context,
            error.message ?: error.javaClass.simpleName,
            Toast.LENGTH_LONG
        ).show()
    }
}
