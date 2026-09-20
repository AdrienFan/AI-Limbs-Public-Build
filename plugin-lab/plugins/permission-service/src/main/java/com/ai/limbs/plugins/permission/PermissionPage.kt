package com.ai.limbs.plugins.permission

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    private val controller: PermissionPageController
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { Content(host, controller) } }
        }
}

@Composable
private fun Content(host: InProcessPluginUiHost, controller: PermissionPageController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var stopConfirm by remember { mutableStateOf(false) }

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

    LaunchedEffect(controller) {
        while (true) {
            if (!controller.state.value.busy) {
                try { controller.refresh() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    host.logger.w("PermissionService", "Status unavailable: " + error.message)
                    Toast.makeText(context, "请先安装支持权限服务的基座，并在插件中心授权", Toast.LENGTH_LONG).show()
                    break
                }
            }
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Security, contentDescription = null)
            Text("AI Limbs 权限服务", style = MaterialTheme.typography.headlineSmall)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.running) "服务已连接" else "服务未运行", style = MaterialTheme.typography.titleLarge)
                Text(state.message)
                if (state.running) Text(if (state.uid == 0) "运行权限：root" else "运行权限：ADB")
                Text("当前执行后端：" + when (state.backend) {
                    "ai_limbs" -> "AI Limbs 权限服务"
                    "shizuku" -> "外部 Shizuku / Sui"
                    else -> "尚未读取"
                })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !state.busy, onClick = { perform { controller.refresh() } }) { Text("刷新") }
                    Button(enabled = !state.busy && state.running, onClick = { stopConfirm = true }) { Text("停止服务") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("无线调试", style = MaterialTheme.typography.titleMedium)
                Text("在开发者选项中打开无线调试。首次使用请选“使用配对码配对设备”，填写该窗口中的端口和六位配对码。建议分屏保持配对窗口打开。")
                OutlinedButton(enabled = !state.busy, onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (error: Exception) {
                        host.logger.w("PermissionService", "Open developer settings failed: " + error.message)
                        Toast.makeText(context, "请手动打开系统设置中的开发者选项", Toast.LENGTH_LONG).show()
                    }
                }) { Text("打开开发者选项") }
                OutlinedTextField(value = pairPort, onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("配对端口") }, singleLine = true, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pairCode, onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("六位配对码") }, singleLine = true, enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Button(enabled = !state.busy && pairCode.length == 6 && pairPort.isNotBlank(), onClick = {
                    val code = pairCode
                    pairCode = ""
                    perform { controller.pair(pairPort.toInt(), code) }
                }) { Text("配对") }
                HorizontalDivider()
                Text("配对成功后，使用无线调试主页上的连接端口启动。连接端口与配对端口不同，重启后可能变化。")
                OutlinedTextField(value = connectPort, onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("连接端口") }, singleLine = true, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Button(enabled = !state.busy && !state.running && connectPort.isNotBlank(), onClick = {
                    perform { controller.startAdb(connectPort.toInt()) }
                }) { Text("启动权限服务") }
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
                Text("服务仅供 AI Limbs 使用。设备重启后需重新启动；各插件仍按 AI Limbs 授权执行。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("v0.1.4 · 基于 Shizuku 开源技术，采用 Apache-2.0 许可。", style = MaterialTheme.typography.bodySmall)
    }
    if (stopConfirm) AlertDialog(
        onDismissRequest = { stopConfirm = false },
        title = { Text("停止权限服务？") },
        text = { Text("依赖此服务的 Android 操作将暂时不可用。") },
        confirmButton = { TextButton(onClick = { stopConfirm = false; perform { controller.stop() } }) { Text("停止") } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("取消") } }
    )
}
