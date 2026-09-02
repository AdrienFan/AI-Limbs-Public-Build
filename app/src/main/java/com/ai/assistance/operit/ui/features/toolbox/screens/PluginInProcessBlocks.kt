package com.ai.assistance.operit.ui.features.toolbox.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.center.PluginContributionKind
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.plugins.center.PluginScreenBlock
import com.ai.limbs.plugin.runtime.ChildExtensionLifecycle
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessDynamicPanelProvider
import com.ai.limbs.plugin.runtime.InProcessPanelFieldKind
import com.ai.limbs.plugin.runtime.InProcessSelectionProvider
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun activeExtensionHub(): ExtensionHubService? =
    PluginPlatformKernel.contributions
        .find(PluginContributionKind.PROVIDER, InProcessSystemIds.EXTENSION_HUB_PROVIDER)
        ?.payload as? ExtensionHubService

@Composable
internal fun ChildExtensionInstallerBlock(
    block: PluginScreenBlock.ChildExtensionInstaller
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = activeExtensionHub()
    var feedback by remember(block.point) { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (hub == null) {
                feedback = "Plugin Extension Hub 未启用"
            } else {
                scope.launch {
                    val temporary = File(context.cacheDir, "extension-import-${UUID.randomUUID()}.ailx")
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri).use { input ->
                                requireNotNull(input) { "无法读取选择的子插件" }
                                temporary.outputStream().use(input::copyTo)
                            }
                        }
                        val snapshot = withContext(Dispatchers.IO) {
                            hub.install(temporary, block.ownerPluginId, block.point)
                        }
                        feedback = "已安装 ${snapshot.displayName} ${snapshot.version} · ${snapshot.lifecycle}"
                    } catch (error: Throwable) {
                        feedback = "子插件安装失败：${error.message ?: "未知错误"}"
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = hub != null, onClick = {
            launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }) { Text(block.label) }
        Text(if (hub == null) "Plugin Extension Hub 未启用" else "仅接受 AIL_EXTENSION_V1 / .ailx", style = MaterialTheme.typography.bodySmall)
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun ChildExtensionListBlock(point: String) {
    val hub = activeExtensionHub()
    if (hub == null) {
        Text("Plugin Extension Hub 未启用")
        return
    }
    val snapshots by hub.snapshotsForPoint(point).collectAsState()
    val backups by hub.backupSnapshots().collectAsState()
    val scope = rememberCoroutineScope()
    var feedback by remember(point) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("已安装子插件 · ${snapshots.size}", style = MaterialTheme.typography.titleSmall)
        if (snapshots.isEmpty()) Text("尚未安装子插件", style = MaterialTheme.typography.bodySmall)
        snapshots.forEach { snapshot ->
            val backup = backups.firstOrNull { it.extensionId == snapshot.extensionId }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${snapshot.displayName} · ${snapshot.version}", style = MaterialTheme.typography.titleSmall)
                    Text("${snapshot.extensionId} · ${snapshot.lifecycle}", style = MaterialTheme.typography.bodySmall)
                    Text("使用 ${snapshot.useCount} 次 · 备份：${backup?.version ?: "未备份"}", style = MaterialTheme.typography.bodySmall)
                    snapshot.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                runCatching { hub.setEnabled(snapshot.extensionId, !snapshot.enabled) }
                                    .onSuccess { feedback = "${it.displayName} → ${it.lifecycle}" }
                                    .onFailure { feedback = "操作失败：${it.message}" }
                            }
                        }) { Text(if (snapshot.enabled) "禁用" else "启用") }
                        Button(onClick = {
                            scope.launch {
                                runCatching { hub.backup(snapshot.extensionId) }
                                    .onSuccess { feedback = "已备份 ${it.displayName} ${it.version}" }
                                    .onFailure { feedback = "备份失败：${it.message}" }
                            }
                        }) { Text("备份") }
                        Button(onClick = {
                            scope.launch {
                                runCatching { hub.uninstall(snapshot.extensionId) }
                                    .onSuccess { feedback = if (it) "已卸载 ${snapshot.displayName}" else "子插件不存在" }
                                    .onFailure { feedback = "卸载失败：${it.message}" }
                            }
                        }) { Text("卸载") }
                    }
                }
            }
        }
        val restorable = backups.filter { backup ->
            backup.target.point == point && snapshots.none { it.extensionId == backup.extensionId }
        }
        if (restorable.isNotEmpty()) {
            Text("可恢复备份 · ${restorable.size}", style = MaterialTheme.typography.titleSmall)
        }
        restorable.forEach { backup ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${backup.displayName} · ${backup.version}", style = MaterialTheme.typography.titleSmall)
                    Text("${backup.extensionId} · backup", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                runCatching { hub.restoreBackup(backup.extensionId) }
                                    .onSuccess { feedback = "已恢复 ${it.displayName} ${it.version}" }
                                    .onFailure { feedback = "恢复失败：${it.message}" }
                            }
                        }) { Text("恢复") }
                        Button(onClick = {
                            scope.launch {
                                runCatching { hub.deleteBackup(backup.extensionId) }
                                    .onSuccess { feedback = if (it) "已删除备份" else "备份不存在" }
                                    .onFailure { feedback = "删除备份失败：${it.message}" }
                            }
                        }) { Text("删除备份") }
                    }
                }
            }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun DynamicPanelBlock(providerId: String) {
    val provider = PluginPlatformKernel.contributions
        .find(PluginContributionKind.PROVIDER, providerId)?.payload as? InProcessDynamicPanelProvider
    if (provider == null) {
        Text("当前 Provider 控制面板不可用", style = MaterialTheme.typography.bodySmall)
        return
    }
    val panel by provider.state.collectAsState()
    val current = panel
    if (current == null) {
        Text("尚未选择可用的 Provider", style = MaterialTheme.typography.bodySmall)
        return
    }
    val scope = rememberCoroutineScope()
    val values = remember(providerId) { mutableStateMapOf<String, String>() }
    val initialValues = remember(providerId) { mutableStateMapOf<String, String>() }
    var feedback by remember(providerId) { mutableStateOf<String?>(null) }
    var busyAction by remember(providerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        val activeIds = current.fields.map { it.id }.toSet()
        values.keys.filter { it !in activeIds }.toList().forEach(values::remove)
        initialValues.keys.filter { it !in activeIds }.toList().forEach(initialValues::remove)
        current.fields.forEach { field ->
            val previousInitial = initialValues[field.id]
            if (field.id !in values || values[field.id] == previousInitial) values[field.id] = field.value
            initialValues[field.id] = field.value
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current.title, style = MaterialTheme.typography.titleMedium)
            current.description.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            current.statusLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            current.fields.forEach { field ->
                val fieldValue = values[field.id] ?: field.value
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { values[field.id] = it },
                    label = { Text(field.label) },
                    placeholder = { Text(field.placeholder) },
                    enabled = field.enabled,
                    visualTransformation = if (field.kind == InProcessPanelFieldKind.SECRET) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            current.actions.forEach { action ->
                val requiredReady = action.requiredFieldIds.all { values[it].orEmpty().isNotBlank() }
                Button(
                    enabled = action.enabled && requiredReady && busyAction == null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busyAction = action.id
                            try {
                                val result = provider.perform(action.id, values.toMap())
                                result.fieldValues.forEach { (key, value) -> values[key] = value }
                                feedback = result.message.takeIf { it.isNotBlank() }
                            } catch (error: Throwable) {
                                feedback = "操作失败：${error.message ?: "未知错误"}"
                            } finally {
                                busyAction = null
                            }
                        }
                    }
                ) { Text(if (busyAction == action.id) "处理中…" else action.label) }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun ChildExtensionSelectorBlock(block: PluginScreenBlock.ChildExtensionSelector) {
    val hub = activeExtensionHub()
    if (hub == null) {
        Text("Plugin Extension Hub 未启用")
        return
    }
    val snapshots by hub.snapshotsForPoint(block.point).collectAsState()
    val active = snapshots.filter { it.lifecycle == ChildExtensionLifecycle.ACTIVE }
    val selectionProvider = block.selectionProviderId?.let { providerId ->
        PluginPlatformKernel.contributions
            .find(PluginContributionKind.PROVIDER, providerId)?.payload as? InProcessSelectionProvider
    }
    val selectedExtensionId = selectionProvider?.selectedId?.collectAsState()?.value
    val selectedSnapshot = active.firstOrNull { it.extensionId == selectedExtensionId }
    val scope = rememberCoroutineScope()
    var expanded by remember(block.point) { mutableStateOf(false) }
    var localSelectedName by remember(block.point) { mutableStateOf<String?>(null) }
    var feedback by remember(block.point) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(block.label, style = MaterialTheme.typography.titleSmall)
        Box {
            Button(enabled = active.isNotEmpty(), onClick = { expanded = true }) {
                Text(selectedSnapshot?.displayName ?: localSelectedName ?: active.firstOrNull()?.displayName ?: "暂无 Provider")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                active.forEach { snapshot ->
                    DropdownMenuItem(text = { Text(snapshot.displayName) }, onClick = {
                        expanded = false
                        scope.launch {
                            try {
                                val value = withContext(Dispatchers.IO) {
                                    PluginPlatformKernel.capabilities.invokePlugin(
                                        block.selectCapabilityId,
                                        JSONObject().put("extension_id", snapshot.extensionId)
                                    )
                                }
                                localSelectedName = snapshot.displayName
                                feedback = value.optString("content").ifBlank { value.toString(2) }
                            } catch (error: Throwable) {
                                feedback = "选择失败：${error.message ?: "未知错误"}"
                            }
                        }
                    })
                }
            }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}