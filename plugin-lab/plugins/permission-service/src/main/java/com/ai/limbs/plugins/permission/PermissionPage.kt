package com.ai.limbs.plugins.permission

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PermissionPage(
    private val host: InProcessPluginUiHost,
    private val controller: PermissionPageController,
    private val pairing: PermissionPairingOverlay
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { Content(host, controller, pairing) } }
        }
}

private data class BuiltInAccessibilityStatus(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val queryError: String? = null
)

private const val AI_LIMBS_ACCESSIBILITY_SERVICE_CLASS =
    "com.ai.assistance.ailimbs.accessibility.AiLimbsAccessibilityService"

private fun readBuiltInAccessibilityStatus(context: Context): BuiltInAccessibilityStatus {
    val appContext = context.applicationContext
    return runCatching {
        val manager = appContext.getSystemService(AccessibilityManager::class.java)
            ?: return@runCatching BuiltInAccessibilityStatus(
                queryError = "AccessibilityManager unavailable"
            )
        val available = manager.installedAccessibilityServiceList.orEmpty().any { info ->
            info.resolveInfo?.serviceInfo?.name == AI_LIMBS_ACCESSIBILITY_SERVICE_CLASS
        }
        val enabled = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).orEmpty().any { info ->
            info.resolveInfo?.serviceInfo?.name == AI_LIMBS_ACCESSIBILITY_SERVICE_CLASS
        }
        BuiltInAccessibilityStatus(available = available, enabled = enabled)
    }.getOrElse { error ->
        BuiltInAccessibilityStatus(
            queryError = error.message ?: error.javaClass.simpleName
        )
    }
}

@Composable
private fun Content(host: InProcessPluginUiHost, controller: PermissionPageController, pairing: PermissionPairingOverlay) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var stopConfirm by remember { mutableStateOf(false) }
    var authorizationOpen by remember { mutableStateOf(false) }
    var accessibilityStatus by remember { mutableStateOf(BuiltInAccessibilityStatus()) }

    fun perform(block: suspend () -> Unit) {
        // A pairing/start action belongs to the plugin runtime, so opening Settings does not cancel it.
        host.scope.launch(Dispatchers.Main) {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                host.logger.e("PermissionService", "Operation failed: " + error.message)
                Toast.makeText(context, error.message ?: "操作失败，请查看日志中心", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(context) {
        var lastAccessibilityError: String? = null
        while (true) {
            val next = readBuiltInAccessibilityStatus(context)
            accessibilityStatus = next
            if (next.queryError != null && next.queryError != lastAccessibilityError) {
                host.logger.w(
                    "PermissionService",
                    "Accessibility status query unavailable: " + next.queryError
                )
            }
            lastAccessibilityError = next.queryError
            delay(1500)
        }
    }


    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Security, contentDescription = null)
            Text("AI Limbs 权限服务", style = MaterialTheme.typography.headlineSmall)
        }
        Card(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                if (maxWidth >= 440.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            ConnectionDetails(state, { perform { controller.refresh() } }, { stopConfirm = true })
                        }
                        AuthorizationShortcut(state) { authorizationOpen = true }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConnectionDetails(state, { perform { controller.refresh() } }, { stopConfirm = true })
                        HorizontalDivider()
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            AuthorizationShortcut(state) { authorizationOpen = true }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("无线调试", style = MaterialTheme.typography.titleMedium)
                Text("打开开发者选项时会同时打开配对浮窗，可对照系统窗口填写，无需分屏。浮窗顶部可拖动，也可以收起或关闭。")
                OutlinedButton(enabled = !state.busy, onClick = {
                    perform { pairing.openDeveloperOptions(context) }
                }) { Text("打开开发者选项") }
                WirelessDebuggingForm(host, controller, pairing.form)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("其他启动与后端选择", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(enabled = !state.busy && !state.running, onClick = { perform { controller.startRoot() } }) {
                    Text("使用 root 启动")
                }
                if (state.running && state.backend != "ai_limbs") {
                    TextButton(enabled = !state.busy, onClick = { perform { controller.select("ai_limbs") } }) {
                        Text("使用 AI Limbs 权限服务")
                    }
                }
                if (state.backend == "ai_limbs") {
                    TextButton(enabled = !state.busy, onClick = { perform { controller.select("shizuku") } }) {
                        Text("切换到外部 Shizuku / Sui")
                    }
                }
                Text("可在连接状态右侧管理第三方 App 授权。设备重启后需重新启动服务；AI Limbs 插件仍按各自授权执行。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("无障碍服务", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        accessibilityStatus.queryError != null -> "无障碍服务状态暂不可读"
                        !accessibilityStatus.available -> "当前基座未内置 AI Limbs 无障碍服务"
                        accessibilityStatus.enabled -> "AI Limbs 无障碍服务已启用"
                        else -> "AI Limbs 无障碍服务已内置，尚未启用"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    when {
                        accessibilityStatus.queryError != null ->
                            "当前页面无法读取系统无障碍状态；权限服务其他功能仍可正常使用。"
                        accessibilityStatus.available ->
                            "无障碍能力已经集成到 AI Limbs 主程序，无需额外安装独立无障碍应用。"
                        else ->
                            "请使用 build67 或更新版本的 AI Limbs 基座后再启用此功能。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    enabled = accessibilityStatus.available && accessibilityStatus.queryError == null,
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (error: Exception) {
                            host.logger.w(
                                "PermissionService",
                                "Open accessibility settings failed: " + error.message
                            )
                            Toast.makeText(
                                context,
                                "请手动打开系统设置中的无障碍服务",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) {
                    Text(if (accessibilityStatus.enabled) "管理无障碍服务" else "打开无障碍设置")
                }
            }
        }
        Text(
            "v0.2.3 · 权限服务基于 Shizuku 开源技术（Apache-2.0）；无障碍功能由 AI Limbs 基座提供。",
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (authorizationOpen) AuthorizationManager(host, controller, state) { authorizationOpen = false }
    if (stopConfirm) AlertDialog(
        onDismissRequest = { stopConfirm = false },
        title = { Text("停止权限服务？") },
        text = { Text("依赖此服务的 Android 操作将暂时不可用。") },
        confirmButton = { TextButton(onClick = { stopConfirm = false; perform { controller.stop() } }) { Text("停止") } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("取消") } }
    )
}

@Composable
private fun ConnectionDetails(state: PermissionState, refresh: () -> Unit, stop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (state.statusError.isNotBlank()) "连接状态待确认" else if (state.running) "服务已连接" else "服务未运行", style = MaterialTheme.typography.titleLarge)
        Text(state.message)
        if (state.running) Text(if (state.uid == 0) "运行权限：root" else "运行权限：ADB")
        Text("当前执行后端：" + when (state.backend) {
            "ai_limbs" -> "AI Limbs 权限服务"
            "shizuku" -> "外部 Shizuku / Sui"
            else -> "尚未读取"
        })
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !state.busy, onClick = refresh) { Text("刷新") }
            Button(enabled = !state.busy && state.running, onClick = stop) { Text("停止服务") }
        }
    }
}
