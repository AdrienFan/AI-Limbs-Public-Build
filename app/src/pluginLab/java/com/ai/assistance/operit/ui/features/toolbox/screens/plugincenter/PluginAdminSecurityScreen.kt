package com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ai.assistance.operit.plugins.center.AdminAuthFrequency
import com.ai.assistance.operit.plugins.center.AdminSecurityManager
import com.ai.assistance.operit.plugins.center.HostSurfaceKind
import com.ai.assistance.operit.plugins.center.HostSurfaceSnapshot
import com.ai.assistance.operit.plugins.center.InactivityThresholdMode
import com.ai.assistance.operit.plugins.center.PluginBackupPolicyStore
import com.ai.assistance.operit.plugins.center.PluginBackupSnapshot
import com.ai.assistance.operit.plugins.center.PluginControlPlane
import com.ai.assistance.operit.plugins.center.PluginInactivityPolicyStore
import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import java.text.DateFormat
import java.util.Date
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
internal fun PluginAdminSecurityScreen(
    controlPlane: PluginControlPlane,
    adminSecurity: AdminSecurityManager,
    onBack: () -> Unit,
    onError: (Throwable) -> Unit,
    onPolicyChanged: () -> Unit
) {
    var developerMode by remember { mutableStateOf(controlPlane.developerModeEnabled()) }
    var surfaces by remember { mutableStateOf(controlPlane.hostSurfaceSnapshots()) }
    var surfacesExpanded by remember { mutableStateOf(false) }
    val initialInactivity = remember { controlPlane.inactivityPolicySnapshot() }
    val initialBackupPolicy = remember { controlPlane.backupPolicySnapshot() }
    var inactivityEnabled by remember { mutableStateOf(initialInactivity.enabled) }
    var inactivityTestMode by remember { mutableStateOf(initialInactivity.mode == InactivityThresholdMode.TEST_SECONDS) }
    var inactivityDays by remember { mutableStateOf(initialInactivity.days.toString()) }
    var inactivitySeconds by remember { mutableStateOf(initialInactivity.testSeconds.toString()) }
    var backupAutoEnabled by remember { mutableStateOf(initialBackupPolicy.enabled) }
    var backups by remember { mutableStateOf<List<PluginBackupSnapshot>>(emptyList()) }
    var backupsExpanded by remember { mutableStateOf(false) }
    var backupQuery by remember { mutableStateOf("") }
    var selectedBackupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var childBackups by remember { mutableStateOf<List<ChildExtensionBackupSnapshot>>(emptyList()) }
    var childBackupsExpanded by remember { mutableStateOf(false) }
    var childBackupQuery by remember { mutableStateOf("") }
    var selectedChildBackupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showRegenerateRecovery by remember { mutableStateOf(false) }
    var authFrequency by remember { mutableStateOf(adminSecurity.authFrequency()) }
    var authFrequencyExpanded by remember { mutableStateOf(false) }
    var pendingAuthFrequency by remember { mutableStateOf<AdminAuthFrequency?>(null) }
    var surfaceQuery by remember { mutableStateOf("") }
    var newRecoveryKey by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun runAdminMutation(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    block()
                    controlPlane.backupSnapshots() to controlPlane.childBackupSnapshots()
                }
            }.onSuccess { (refreshedBackups, refreshedChildBackups) ->
                developerMode = controlPlane.developerModeEnabled()
                surfaces = controlPlane.hostSurfaceSnapshots()
                backupAutoEnabled = controlPlane.backupPolicySnapshot().enabled
                backups = refreshedBackups
                childBackups = refreshedChildBackups
                selectedBackupIds = selectedBackupIds.intersect(refreshedBackups.mapTo(mutableSetOf()) { it.pluginId })
                selectedChildBackupIds = selectedChildBackupIds.intersect(refreshedChildBackups.mapTo(mutableSetOf()) { it.extensionId })
                onPolicyChanged()
            }.onFailure(onError)
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) {
            controlPlane.backupSnapshots() to controlPlane.childBackupSnapshots()
        }
        backups = initial.first
        childBackups = initial.second
    }

    val normalizedSurfaceQuery = surfaceQuery.trim().lowercase()
    val filteredSurfaces = remember(surfaces, normalizedSurfaceQuery) {
        if (normalizedSurfaceQuery.isBlank()) {
            surfaces
        } else {
            surfaces.filter { item ->
                val definition = item.definition
                listOf(
                    definition.title,
                    definition.id,
                    definition.detail,
                    definition.kind.name,
                    definition.requiredScope.orEmpty(),
                    definition.publicContracts.joinToString(" ")
                ).any { it.lowercase().contains(normalizedSurfaceQuery) }
            }
        }
    }
    val allFilteredSurfacesAllowed = filteredSurfaces.isNotEmpty() && filteredSurfaces.all { it.allowed }

    val normalizedBackupQuery = backupQuery.trim().lowercase()
    val filteredBackups = remember(backups, normalizedBackupQuery) {
        backups.filter { backup ->
            val manifest = backup.manifest
            val queryMatch = normalizedBackupQuery.isBlank() || listOf(
                manifest.display.name, manifest.display.description.orEmpty(),
                backup.pluginId, backup.version, manifest.runtime.kind, manifest.roles.joinToString(" ")
            ).any { it.lowercase().contains(normalizedBackupQuery) }
            queryMatch
        }
    }
    val filteredBackupIds = filteredBackups.mapTo(linkedSetOf()) { it.pluginId }
    val selectedRestorableBackupIds = filteredBackups
        .filter { !it.installed && it.pluginId in selectedBackupIds }
        .map { it.pluginId }
    val allFilteredBackupsSelected =
        filteredBackups.isNotEmpty() && filteredBackups.all { it.pluginId in selectedBackupIds }

    val normalizedChildBackupQuery = childBackupQuery.trim().lowercase()
    val filteredChildBackups = remember(childBackups, normalizedChildBackupQuery) {
        childBackups.filter { backup ->
            val queryMatch = normalizedChildBackupQuery.isBlank() || listOf(
                backup.displayName, backup.description.orEmpty(), backup.extensionId, backup.version,
                backup.target.parentPluginId, backup.target.point, backup.roles.joinToString(" ")
            ).any { it.lowercase().contains(normalizedChildBackupQuery) }
            queryMatch
        }
    }
    val filteredChildBackupIds = filteredChildBackups.mapTo(linkedSetOf()) { it.extensionId }
    val selectedRestorableChildBackupIds = filteredChildBackups
        .filter { !it.installed && it.extensionId in selectedChildBackupIds }
        .map { it.extensionId }
    val allFilteredChildBackupsSelected =
        filteredChildBackups.isNotEmpty() && filteredChildBackups.all { it.extensionId in selectedChildBackupIds }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 16.dp, top = 16.dp, end = 22.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回 Plugin Center") }
        }
        Text("管理员安全中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1.15f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("管理员凭据", fontWeight = FontWeight.Bold)
                        Text("管理员密码：已设置")
                        Text("恢复密钥：${if (adminSecurity.snapshot().recoveryConfigured) "已配置" else "未配置"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showChangePassword = true }, enabled = !busy) { Text("修改密码") }
                            OutlinedButton(onClick = { showRegenerateRecovery = true }, enabled = !busy) { Text("重新生成恢复密钥") }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(0.85f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("普通插件验证频率", fontWeight = FontWeight.Medium)
                        Box {
                            OutlinedButton(
                                onClick = { authFrequencyExpanded = true },
                                enabled = !busy
                            ) {
                                Text(adminAuthFrequencyLabel(authFrequency))
                            }
                            DropdownMenu(
                                expanded = authFrequencyExpanded,
                                onDismissRequest = { authFrequencyExpanded = false }
                            ) {
                                AdminAuthFrequency.entries.forEach { frequency ->
                                    DropdownMenuItem(
                                        text = { Text(adminAuthFrequencyLabel(frequency)) },
                                        onClick = {
                                            authFrequencyExpanded = false
                                            if (frequency != authFrequency) pendingAuthFrequency = frequency
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "验证频率仅影响普通插件卸载。系统插件禁用/卸载始终每次验证；管理员安全设置也不会被豁免。",
                    style = MaterialTheme.typography.bodySmall
                )
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
                            Text("仅用于开发验证，最低 ${PluginInactivityPolicyStore.MIN_TEST_SECONDS} 秒", style = MaterialTheme.typography.bodySmall)
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
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("自动备份高频和系统插件", fontWeight = FontWeight.Bold)
                        Text(
                            "系统插件始终属于自动备份对象；普通插件累计使用达到 ${PluginBackupPolicyStore.HIGH_FREQUENCY_USE_COUNT} 次后视为高频。只在当前版本尚未备份时创建备份；更新后会自动备份新版本。配置后即使关闭开发模式也会继续生效。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = backupAutoEnabled,
                        enabled = !busy,
                        onCheckedChange = { enabled ->
                            runAdminMutation { controlPlane.configureBackupPolicy(enabled) }
                        }
                    )
                }
            }

            PluginCollectionSection(
                title = "内接口",
                totalCount = surfaces.size,
                matchedCount = filteredSurfaces.size,
                query = surfaceQuery,
                onQueryChange = { surfaceQuery = it },
                expanded = surfacesExpanded,
                onExpandedChange = { surfacesExpanded = it },
                searchPlaceholder = "搜索 Host Surface"
            ) {
                Text("Host Surface Policy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "只有允许的宿主扩展面才能被插件使用。关闭接口后，相关已启用插件会转为 BLOCKED 并立即撤销运行；重新开放后会自动尝试恢复。",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "显示 ${filteredSurfaces.size} / ${surfaces.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        enabled = !busy && filteredSurfaces.isNotEmpty(),
                        onClick = {
                            val targetIds = filteredSurfaces.map { it.definition.id }
                            runAdminMutation {
                                controlPlane.setHostSurfacesAllowed(targetIds, !allFilteredSurfacesAllowed)
                            }
                        }
                    ) {
                        Text(
                            when {
                                normalizedSurfaceQuery.isBlank() && allFilteredSurfacesAllowed -> "取消全选"
                                normalizedSurfaceQuery.isBlank() -> "全选"
                                allFilteredSurfacesAllowed -> "取消选择结果"
                                else -> "全选结果"
                            }
                        )
                    }
                    if (surfaceQuery.isNotBlank()) {
                        TextButton(onClick = { surfaceQuery = "" }) { Text("清除搜索") }
                    }
                }
                if (filteredSurfaces.isEmpty()) {
                    Text("没有匹配的宿主接口", style = MaterialTheme.typography.bodySmall)
                } else {
                    SurfaceGroups(filteredSurfaces, busy) { item, allowed ->
                        runAdminMutation { controlPlane.setHostSurfaceAllowed(item.definition.id, allowed) }
                    }
                }
            }

            Divider()
            PluginCollectionSection(
                title = "已备份插件",
                totalCount = backups.size,
                matchedCount = filteredBackups.size,
                query = backupQuery,
                onQueryChange = { backupQuery = it },
                expanded = backupsExpanded,
                onExpandedChange = { backupsExpanded = it },
                searchPlaceholder = "搜索备份插件"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "已选择 ${selectedBackupIds.size} 个",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        enabled = !busy && filteredBackups.isNotEmpty(),
                        onClick = {
                            selectedBackupIds = if (allFilteredBackupsSelected) {
                                selectedBackupIds - filteredBackupIds
                            } else {
                                selectedBackupIds + filteredBackupIds
                            }
                        }
                    ) {
                        Text(
                            when {
                                normalizedBackupQuery.isBlank() && allFilteredBackupsSelected -> "取消全选"
                                normalizedBackupQuery.isBlank() -> "全选"
                                allFilteredBackupsSelected -> "取消选择结果"
                                else -> "全选结果"
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && selectedRestorableBackupIds.isNotEmpty(),
                        onClick = {
                            val ids = selectedRestorableBackupIds.toList()
                            runAdminMutation { ids.forEach { controlPlane.restoreBackup(it) } }
                        }
                    ) { Text("恢复所选") }
                    OutlinedButton(
                        enabled = !busy && selectedBackupIds.isNotEmpty(),
                        onClick = {
                            val ids = selectedBackupIds.toList()
                            runAdminMutation { ids.forEach { controlPlane.deleteBackup(it) } }
                        }
                    ) { Text("删除所选") }
                }
                if (filteredBackups.isEmpty()) {
                    Text(
                        if (backupQuery.isBlank()) "当前没有已备份插件" else "没有匹配的备份插件",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    filteredBackups.forEach { backup ->
                        BackupPluginCard(
                            backup = backup,
                            selected = backup.pluginId in selectedBackupIds,
                            busy = busy,
                            onSelectedChange = { selected ->
                                selectedBackupIds = if (selected) {
                                    selectedBackupIds + backup.pluginId
                                } else {
                                    selectedBackupIds - backup.pluginId
                                }
                            },
                            onRestore = { runAdminMutation { controlPlane.restoreBackup(backup.pluginId) } },
                            onDelete = { runAdminMutation { controlPlane.deleteBackup(backup.pluginId) } }
                        )
                    }
                }
            }

            Divider()
            PluginCollectionSection(
                title = "已备份子插件",
                totalCount = childBackups.size,
                matchedCount = filteredChildBackups.size,
                query = childBackupQuery,
                onQueryChange = { childBackupQuery = it },
                expanded = childBackupsExpanded,
                onExpandedChange = { childBackupsExpanded = it },
                searchPlaceholder = "搜索备份子插件"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "已选择 ${selectedChildBackupIds.size} 个",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        enabled = !busy && filteredChildBackups.isNotEmpty(),
                        onClick = {
                            selectedChildBackupIds = if (allFilteredChildBackupsSelected) {
                                selectedChildBackupIds - filteredChildBackupIds
                            } else {
                                selectedChildBackupIds + filteredChildBackupIds
                            }
                        }
                    ) {
                        Text(
                            when {
                                normalizedChildBackupQuery.isBlank() && allFilteredChildBackupsSelected -> "取消全选"
                                normalizedChildBackupQuery.isBlank() -> "全选"
                                allFilteredChildBackupsSelected -> "取消选择结果"
                                else -> "全选结果"
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && selectedRestorableChildBackupIds.isNotEmpty(),
                        onClick = {
                            val ids = selectedRestorableChildBackupIds.toList()
                            runAdminMutation { ids.forEach { controlPlane.restoreChildBackup(it) } }
                        }
                    ) { Text("恢复所选") }
                    OutlinedButton(
                        enabled = !busy && selectedChildBackupIds.isNotEmpty(),
                        onClick = {
                            val ids = selectedChildBackupIds.toList()
                            runAdminMutation { ids.forEach { controlPlane.deleteChildBackup(it) } }
                        }
                    ) { Text("删除所选") }
                }
                if (filteredChildBackups.isEmpty()) {
                    Text(
                        if (childBackupQuery.isBlank()) "当前没有已备份子插件" else "没有匹配的备份子插件",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    filteredChildBackups.forEach { backup ->
                        BackupChildExtensionCard(
                            backup = backup,
                            selected = backup.extensionId in selectedChildBackupIds,
                            busy = busy,
                            onSelectedChange = { selected ->
                                selectedChildBackupIds = if (selected) {
                                    selectedChildBackupIds + backup.extensionId
                                } else {
                                    selectedChildBackupIds - backup.extensionId
                                }
                            },
                            onRestore = { runAdminMutation { controlPlane.restoreChildBackup(backup.extensionId) } },
                            onDelete = { runAdminMutation { controlPlane.deleteChildBackup(backup.extensionId) } }
                        )
                    }
                }
            }
        }
    }
        ScrollStateScrollIndicator(
            state = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
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
    pendingAuthFrequency?.let { targetFrequency ->
        ChangeAuthFrequencyDialog(
            adminSecurity = adminSecurity,
            targetFrequency = targetFrequency,
            onDismiss = { pendingAuthFrequency = null },
            onChanged = {
                authFrequency = targetFrequency
                pendingAuthFrequency = null
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
                        if (item.definition.publicContracts.isNotEmpty()) {
                            Text(
                                "Public Contract：" + item.definition.publicContracts.joinToString(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        Divider()
    }
}

@Composable
private fun BackupPluginCard(
    backup: PluginBackupSnapshot,
    selected: Boolean,
    busy: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, enabled = !busy, onCheckedChange = onSelectedChange)
                Column(Modifier.weight(1f)) {
                    Text(backup.manifest.display.name, fontWeight = FontWeight.Bold)
                    Text(backup.pluginId, style = MaterialTheme.typography.bodySmall)
                    Text("v${backup.version} · ${backup.manifest.runtime.kind}", style = MaterialTheme.typography.bodySmall)
                    Text("备份时间：${formatBackupTime(backup.backedUpAtEpochMs)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (backup.installed) "当前已安装：v${backup.installedVersion ?: "未知"}" else "当前未安装，可从备份恢复",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRestore, enabled = !busy && !backup.installed) { Text("恢复") }
                OutlinedButton(onClick = onDelete, enabled = !busy) { Text("删除") }
            }
        }
    }
}

@Composable
private fun BackupChildExtensionCard(
    backup: ChildExtensionBackupSnapshot,
    selected: Boolean,
    busy: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, enabled = !busy, onCheckedChange = onSelectedChange)
                Column(Modifier.weight(1f)) {
                    Text(backup.displayName, fontWeight = FontWeight.Bold)
                    Text("${backup.extensionId} · 子插件 · .ailx", style = MaterialTheme.typography.bodySmall)
                    Text("v${backup.version}", style = MaterialTheme.typography.bodySmall)
                    Text("父插件：${backup.target.parentPluginId}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Extension Point：${backup.target.point}@${backup.target.apiVersion}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("备份时间：${formatBackupTime(backup.backedUpAtEpochMs)}", style = MaterialTheme.typography.bodySmall)
                    if (backup.installed) {
                        Text("当前已安装：v${backup.installedVersion ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("当前未安装；恢复时会重新校验父插件与 Extension Point", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRestore, enabled = !busy && !backup.installed) { Text("恢复") }
                OutlinedButton(onClick = onDelete, enabled = !busy) { Text("删除") }
            }
        }
    }
}

private fun formatBackupTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

private fun surfaceKindTitle(kind: HostSurfaceKind): String = when (kind) {
    HostSurfaceKind.EXTENSION_POINT -> "Extension Points"
    HostSurfaceKind.HOST_CAPABILITY -> "Host Capabilities"
    HostSurfaceKind.HOST_PROVIDER -> "Host Providers"
    HostSurfaceKind.PLUGIN_CAPABILITY_BUS -> "Plugin Capability Bus"
    HostSurfaceKind.PLUGIN_SERVICE_BUS -> "Plugin Service Bus"
    HostSurfaceKind.PLUGIN_PROVIDER_BUS -> "Plugin Provider Bus"
}

private fun adminAuthFrequencyLabel(frequency: AdminAuthFrequency): String = when (frequency) {
    AdminAuthFrequency.EVERY_ACTION -> "每次都询问"
    AdminAuthFrequency.ONCE_PER_APP_SESSION -> "每次启动验证一次"
    AdminAuthFrequency.NEVER -> "普通插件不再询问"
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
private fun ChangeAuthFrequencyDialog(
    adminSecurity: AdminSecurityManager,
    targetFrequency: AdminAuthFrequency,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改普通插件验证频率") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("将普通插件卸载验证改为：${adminAuthFrequencyLabel(targetFrequency)}")
                Text(
                    "系统插件禁用/卸载始终保持每次验证，不受此设置影响。",
                    style = MaterialTheme.typography.bodySmall
                )
                PasswordField("当前管理员密码", password) { password = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.Default) {
                        adminSecurity.changeAuthFrequency(password, targetFrequency)
                    }
                    busy = false
                    if (ok) onChanged() else error = "管理员密码不正确"
                }
            }) { Text(if (busy) "验证中…" else "确认修改") }
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
