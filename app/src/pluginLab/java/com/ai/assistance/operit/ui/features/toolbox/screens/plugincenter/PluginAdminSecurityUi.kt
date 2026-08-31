package com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.center.AdminSecurityManager
import com.ai.assistance.operit.plugins.center.HostSurfaceKind
import com.ai.assistance.operit.plugins.center.HostSurfaceSnapshot
import com.ai.assistance.operit.plugins.center.InactivityThresholdMode
import com.ai.assistance.operit.plugins.center.PluginControlPlane
import com.ai.assistance.operit.plugins.center.PluginInactivityPolicyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AdminSetupDialog(
    adminSecurity: AdminSecurityManager,
    onDismiss: () -> Unit,
    onConfigured: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("设置管理员密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("管理员密码用于保护插件卸载、开发模式和宿主接口策略。")
                PasswordField("管理员密码（至少 8 个字符）", password) { password = it }
                PasswordField("再次输入密码", confirm) { confirm = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (password != confirm) {
                    error = "两次输入的密码不一致"
                    return@TextButton
                }
                scope.launch {
                    busy = true
                    val result = runCatching {
                        withContext(Dispatchers.Default) { adminSecurity.setup(password) }
                    }
                    busy = false
                    result.onSuccess { onConfigured(it.recoveryKey) }
                        .onFailure { error = it.message ?: "设置失败" }
                }
            }) { Text(if (busy) "处理中…" else "设置") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun AdminPasswordDialog(
    title: String,
    adminSecurity: AdminSecurityManager,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    onForgotPassword: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordField("管理员密码", password) { password = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onForgotPassword, enabled = !busy) { Text("忘记密码？使用恢复密钥") }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val valid = withContext(Dispatchers.Default) { adminSecurity.verifyPassword(password) }
                    busy = false
                    if (valid) onVerified() else error = "管理员密码不正确"
                }
            }) { Text(if (busy) "验证中…" else "验证") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun AdminRecoveryDialog(
    adminSecurity: AdminSecurityManager,
    onDismiss: () -> Unit,
    onRecovered: () -> Unit
) {
    var recoveryKey by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("恢复管理员访问") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入首次设置或最近重新生成的恢复密钥，然后设置新的管理员密码。旧密码不需要。")
                OutlinedTextField(
                    value = recoveryKey,
                    onValueChange = { recoveryKey = it },
                    label = { Text("恢复密钥") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                PasswordField("新管理员密码", newPassword) { newPassword = it }
                PasswordField("再次输入新密码", confirm) { confirm = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (newPassword != confirm) {
                    error = "两次输入的新密码不一致"
                    return@TextButton
                }
                scope.launch {
                    busy = true
                    val result = runCatching {
                        withContext(Dispatchers.Default) {
                            adminSecurity.recoverPassword(recoveryKey, newPassword)
                        }
                    }
                    busy = false
                    result.onSuccess { ok -> if (ok) onRecovered() else error = "恢复密钥不正确" }
                        .onFailure { error = it.message ?: "恢复失败" }
                }
            }) { Text(if (busy) "恢复中…" else "重设密码") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun RecoveryKeyDialog(recoveryKey: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("保存管理员恢复密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请把下面的恢复密钥保存在安全位置。它不会再次自动显示；忘记管理员密码时可用它重设密码。")
                SelectionContainer {
                    Text(recoveryKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text("重新生成恢复密钥后，旧密钥会立即失效。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("我已保存") } }
    )
}

@Composable
internal fun PluginAdminSettingsScreen(
    controlPlane: PluginControlPlane,
    adminSecurity: AdminSecurityManager,
    onBack: () -> Unit,
    onError: (Throwable) -> Unit,
    onPolicyChanged: () -> Unit
) {
    var developerMode by remember { mutableStateOf(controlPlane.developerModeEnabled()) }
    var surfaces by remember { mutableStateOf(controlPlane.hostSurfaceSnapshots()) }
    val initialInactivity = remember { controlPlane.inactivityPolicySnapshot() }
    var inactivityEnabled by remember { mutableStateOf(initialInactivity.enabled) }
    var inactivityTestMode by remember { mutableStateOf(initialInactivity.mode == InactivityThresholdMode.TEST_SECONDS) }
    var inactivityDays by remember { mutableStateOf(initialInactivity.days.toString()) }
    var inactivitySeconds by remember { mutableStateOf(initialInactivity.testSeconds.toString()) }
    var busy by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showRegenerateRecovery by remember { mutableStateOf(false) }
    var newRecoveryKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runAdminMutation(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    developerMode = controlPlane.developerModeEnabled()
                    surfaces = controlPlane.hostSurfaceSnapshots()
                    onPolicyChanged()
                }
                .onFailure(onError)
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回 Plugin Center") }
        }
        Text("管理员安全中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("管理员凭据", fontWeight = FontWeight.Bold)
                Text("管理员密码：已设置")
                Text("恢复密钥：${if (adminSecurity.snapshot().recoveryConfigured) "已配置" else "未配置"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showChangePassword = true }, enabled = !busy) { Text("修改密码") }
                    OutlinedButton(onClick = { showRegenerateRecovery = true }, enabled = !busy) { Text("重新生成恢复密钥") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开发模式", fontWeight = FontWeight.Bold)
                        Text("开启后才能修改宿主暴露给插件的接口。关闭开发模式不会自动重置已经保存的接口策略。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = developerMode,
                        enabled = !busy,
                        onCheckedChange = { enabled -> runAdminMutation { controlPlane.setDeveloperMode(enabled) } }
                    )
                }
            }
        }
        if (developerMode) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("未使用插件自动禁用", fontWeight = FontWeight.Bold)
                    Text(
                        "只处理普通 ACTIVE 插件；系统插件与当前正在提供全局主题的插件自动豁免。策略设置后即使关闭开发模式也会继续生效。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用自动禁用", modifier = Modifier.weight(1f))
                        Switch(
                            checked = inactivityEnabled,
                            enabled = !busy,
                            onCheckedChange = { inactivityEnabled = it }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("秒级测试模式")
                            Text("仅用于 Plugin Lab 验证，最低 ${PluginInactivityPolicyStore.MIN_TEST_SECONDS} 秒", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = inactivityTestMode,
                            enabled = !busy,
                            onCheckedChange = { inactivityTestMode = it }
                        )
                    }
                    if (inactivityTestMode) {
                        OutlinedTextField(
                            value = inactivitySeconds,
                            onValueChange = { value -> inactivitySeconds = value.filter(Char::isDigit) },
                            label = { Text("未使用秒数（${PluginInactivityPolicyStore.MIN_TEST_SECONDS}-${PluginInactivityPolicyStore.MAX_TEST_SECONDS}）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            value = inactivityDays,
                            onValueChange = { value -> inactivityDays = value.filter(Char::isDigit) },
                            label = { Text("未使用天数（${PluginInactivityPolicyStore.MIN_DAYS}-${PluginInactivityPolicyStore.MAX_DAYS}）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                val days = inactivityDays.toIntOrNull() ?: initialInactivity.days
                                val seconds = inactivitySeconds.toIntOrNull() ?: initialInactivity.testSeconds
                                runAdminMutation {
                                    controlPlane.configureInactivityPolicy(
                                        enabled = inactivityEnabled,
                                        mode = if (inactivityTestMode) InactivityThresholdMode.TEST_SECONDS else InactivityThresholdMode.DAYS,
                                        days = days,
                                        testSeconds = seconds
                                    )
                                }
                            }
                        ) { Text("保存并应用") }
                        OutlinedButton(
                            enabled = !busy && inactivityEnabled,
                            onClick = { runAdminMutation { controlPlane.runInactivityCheck() } }
                        ) { Text("立即检查") }
                    }
                }
            }
            Text("Host Surface Policy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("只有允许的宿主扩展面才能被插件使用。关闭接口后，相关已启用插件会转为 BLOCKED 并立即撤销运行；重新开放后会自动尝试恢复。", style = MaterialTheme.typography.bodySmall)
            SurfaceGroups(surfaces, busy) { item, allowed ->
                runAdminMutation { controlPlane.setHostSurfaceAllowed(item.definition.id, allowed) }
            }
        }
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            adminSecurity = adminSecurity,
            onDismiss = { showChangePassword = false },
            onChanged = { showChangePassword = false }
        )
    }
    if (showRegenerateRecovery) {
        RegenerateRecoveryDialog(
            adminSecurity = adminSecurity,
            onDismiss = { showRegenerateRecovery = false },
            onGenerated = {
                showRegenerateRecovery = false
                newRecoveryKey = it
            }
        )
    }
    newRecoveryKey?.let { key -> RecoveryKeyDialog(key) { newRecoveryKey = null } }
}

@Composable
private fun SurfaceGroups(
    surfaces: List<HostSurfaceSnapshot>,
    busy: Boolean,
    onToggle: (HostSurfaceSnapshot, Boolean) -> Unit
) {
    HostSurfaceKind.entries.forEach { kind ->
        val items = surfaces.filter { it.definition.kind == kind }
        if (items.isEmpty()) return@forEach
        Text(surfaceKindTitle(kind), fontWeight = FontWeight.Bold)
        items.forEach { item ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.allowed,
                        enabled = !busy,
                        onCheckedChange = { onToggle(item, it) }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.definition.title, fontWeight = FontWeight.Medium)
                        Text(item.definition.id, style = MaterialTheme.typography.bodySmall)
                        Text(item.definition.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Divider()
    }
}

private fun surfaceKindTitle(kind: HostSurfaceKind): String = when (kind) {
    HostSurfaceKind.EXTENSION_POINT -> "Extension Points"
    HostSurfaceKind.HOST_CAPABILITY -> "Host Capabilities"
    HostSurfaceKind.PLUGIN_CAPABILITY_BUS -> "Plugin Capability Bus"
    HostSurfaceKind.PLUGIN_SERVICE_BUS -> "Plugin Service Bus"
    HostSurfaceKind.PLUGIN_PROVIDER_BUS -> "Plugin Provider Bus"
}

@Composable
private fun ChangePasswordDialog(
    adminSecurity: AdminSecurityManager,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改管理员密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordField("当前管理员密码", oldPassword) { oldPassword = it }
                PasswordField("新管理员密码", newPassword) { newPassword = it }
                PasswordField("再次输入新密码", confirm) { confirm = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (newPassword != confirm) {
                    error = "两次输入的新密码不一致"
                    return@TextButton
                }
                scope.launch {
                    busy = true
                    val result = runCatching {
                        withContext(Dispatchers.Default) { adminSecurity.changePassword(oldPassword, newPassword) }
                    }
                    busy = false
                    result.onSuccess { ok -> if (ok) onChanged() else error = "当前管理员密码不正确" }
                        .onFailure { error = it.message ?: "修改失败" }
                }
            }) { Text(if (busy) "处理中…" else "修改") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RegenerateRecoveryDialog(
    adminSecurity: AdminSecurityManager,
    onDismiss: () -> Unit,
    onGenerated: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("重新生成恢复密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("重新生成后旧恢复密钥会立即失效。")
                PasswordField("当前管理员密码", password) { password = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val key = withContext(Dispatchers.Default) { adminSecurity.regenerateRecoveryKey(password) }
                    busy = false
                    if (key != null) onGenerated(key) else error = "管理员密码不正确"
                }
            }) { Text(if (busy) "处理中…" else "重新生成") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
}
