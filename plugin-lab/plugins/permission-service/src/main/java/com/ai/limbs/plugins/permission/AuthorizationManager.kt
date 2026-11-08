package com.ai.limbs.plugins.permission

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun AuthorizationShortcut(state: PermissionState, open: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        Text(if (state.externalEnabled) "应用授权" else "第三方接入已关闭", style = MaterialTheme.typography.titleSmall)
        Text(if (state.managementAvailable) "已授权 ${state.apps.count { it.decision == "allow" || it.decision == "session" }} 个" else "状态暂不可读",
            style = MaterialTheme.typography.bodyMedium)
        if (state.pendingCount > 0) Text("${state.pendingCount} 个申请待处理", color = MaterialTheme.colorScheme.tertiary)
        FilledTonalButton(onClick = open) { Text("授权管理") }
    }
}

@Composable
internal fun AuthorizationManager(
    host: InProcessPluginUiHost,
    controller: PermissionPageController,
    state: PermissionState,
    close: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<ExternalApp?>(null) }
    var disableConfirm by remember { mutableStateOf(false) }

    fun perform(block: suspend () -> Unit) {
        host.scope.launch(Dispatchers.Main) {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                host.logger.e("PermissionService", "Authorization action failed: " + error.message)
                Toast.makeText(context, error.message ?: "授权操作失败，请查看日志中心", Toast.LENGTH_LONG).show()
            }
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("应用授权管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = close) { Text("返回") }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("允许第三方 App 接入", style = MaterialTheme.typography.titleMedium)
                                Text(if (!state.managementAvailable) "状态暂不可读" else if (state.externalEnabled) "已开启 · 每个 App 仍需单独授权" else "已关闭",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = state.externalEnabled, enabled = state.managementAvailable && !state.busy,
                                onCheckedChange = { value ->
                                    if (!value) disableConfirm = true else perform { controller.setExternalEnabled(true) }
                                })
                        }
                        if (state.managementError.isNotBlank()) Text(state.managementError, color = MaterialTheme.colorScheme.error)
                        Text("支持当前 Android 用户中采用 Shizuku API v13 的 App。先点“连接”，再在目标 App 发起权限申请，回到此处处理。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("提供当前服务的 ${if (state.uid == 0) "root" else "ADB"} 能力。已有 Shizuku / Sui 连接时，请先停止它再连接本服务。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("搜索应用名称或包名") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("全部") })
                    FilterChip(selected = filter == "pending", onClick = { filter = "pending" }, label = { Text("待处理 ${state.pendingCount}") })
                    FilterChip(selected = filter == "allowed", onClick = { filter = "allowed" }, label = { Text("已授权") })
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val labels = remember(state.apps, context) {
                    state.apps.associate { app ->
                        val label = try {
                            val info = context.packageManager.getApplicationInfo(app.packageName, 0)
                            context.packageManager.getApplicationLabel(info).toString()
                        } catch (error: Exception) {
                            host.logger.w("PermissionService", "App label unavailable: " + app.packageName + ": " + error.message)
                            "应用名称不可读"
                        }
                        app.uid to label
                    }
                }
                val visible = state.apps.filter { app ->
                    (app.packages.any { it.contains(query, ignoreCase = true) } || labels.getValue(app.uid).contains(query, ignoreCase = true)) &&
                        when (filter) {
                            "pending" -> app.pending > 0
                            "allowed" -> app.decision == "allow" || app.decision == "session"
                            else -> true
                        }
                }.sortedWith(compareByDescending<ExternalApp> { it.pending > 0 }.thenBy { labels.getValue(it.uid) })
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (visible.isEmpty()) item {
                        Text(if (!state.managementAvailable) "服务可用后会显示兼容应用。" else "没有符合条件的兼容应用。",
                            modifier = Modifier.padding(vertical = 20.dp))
                    }
                    items(visible, key = { it.uid }) { app ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(labels.getValue(app.uid), style = MaterialTheme.typography.titleMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                Text("UID ${app.uid} · " + when (app.decision) {
                                    "allow" -> "已允许"
                                    "session" -> "本次服务运行期间允许"
                                    "deny" -> "已拒绝 / 已撤销"
                                    else -> "尚未授权"
                                })
                                if (app.pending > 0) Text("${app.pending} 个权限申请待处理", color = MaterialTheme.colorScheme.tertiary)
                                if (app.packages.size > 1) Text("共享 UID：" + app.packages.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                                Text(when {
                                    app.connected -> "客户端已连接"
                                    app.delivery == "connecting" -> "正在连接客户端"
                                    app.delivery == "sent" -> "已发送连接信息；请打开目标 App"
                                    app.delivery == "idle" -> "客户端尚未连接"
                                    else -> app.delivery
                                }, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = state.externalEnabled && !state.busy,
                                        onClick = { perform { controller.connectExternal(app.packageName) } }) { Text("连接") }
                                    Button(enabled = state.managementAvailable && !state.busy,
                                        onClick = { selected = app }) { Text(if (app.pending > 0) "处理申请" else "管理授权") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { app ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("应用授权") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(app.packages.joinToString("\n"))
                    Text("授权将允许该 UID 的 App 使用当前服务的 ${if (state.uid == 0) "root" else "ADB"} 能力。")
                    if (app.packages.size > 1) Text("这些 App 共享 UID，本次决定会影响整个 UID。", color = MaterialTheme.colorScheme.error)
                    Text("拒绝或撤销会关闭该 UID 的 App，并终止由本服务启动的特权任务。", style = MaterialTheme.typography.bodySmall)
                    listOf("allow" to "允许并记住", "session" to "仅本次服务运行期间允许", "deny" to "拒绝并记住", "revoke" to "撤销授权").forEach { (mode, title) ->
                        OutlinedButton(modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && (state.externalEnabled || mode == "deny" || mode == "revoke"),
                            onClick = { selected = null; perform { controller.decideExternal(app.uid, app.identity, mode) } }) { Text(title) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
    if (disableConfirm) AlertDialog(onDismissRequest = { disableConfirm = false },
        title = { Text("关闭第三方接入？") },
        text = { Text("会关闭已接入的第三方 App，并终止由本服务启动的特权任务。已记住的授权决定保留。") },
        confirmButton = { TextButton(onClick = { disableConfirm = false; perform { controller.setExternalEnabled(false) } }) { Text("关闭接入") } },
        dismissButton = { TextButton(onClick = { disableConfirm = false }) { Text("取消") } })
}
