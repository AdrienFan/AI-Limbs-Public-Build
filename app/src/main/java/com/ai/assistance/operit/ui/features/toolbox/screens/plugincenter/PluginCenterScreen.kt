package com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.center.PluginCenterKernel
import com.ai.assistance.operit.plugins.center.PluginControlSnapshot
import com.ai.assistance.operit.plugins.center.PluginHealthState
import com.ai.assistance.operit.plugins.center.PluginInstallOptions
import com.ai.assistance.operit.plugins.center.PluginLifecycleState
import com.ai.assistance.operit.plugins.center.PluginManifest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PluginImportCandidate(
    val file: File,
    val manifest: PluginManifest,
    val updateTargetId: String? = null
)
@Composable
fun PluginCenterScreen() {
    val context = LocalContext.current
    val controlPlane = remember { PluginCenterKernel.controlPlane }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var snapshots by remember { mutableStateOf<List<PluginControlSnapshot>>(emptyList()) }
    var candidate by remember { mutableStateOf<PluginImportCandidate?>(null) }
    var updateTargetId by remember { mutableStateOf<String?>(null) }
    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    var uninstallTargetId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        snapshots = withContext(Dispatchers.IO) { controlPlane.snapshots() }
    }

    fun showError(error: Throwable) {
        scope.launch {
            snackbarHostState.showSnackbar(error.message ?: error::class.java.simpleName)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val imported = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "plugin_center_import").apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.ailp")
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取所选插件文件" }
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    val manifest = controlPlane.inspectPackage(target)
                    PluginImportCandidate(target, manifest, updateTargetId)
                }
                candidate?.file?.delete()
                candidate = imported
            }.onFailure(::showError)
            busy = false
            updateTargetId = null
        }
    }

    fun choosePlugin(targetPluginId: String? = null) {
        updateTargetId = targetPluginId
        fileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    fun runMutation(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { refresh() }
                .onFailure(::showError)
            busy = false
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val selected = snapshots.firstOrNull { it.plugin.pluginId == selectedPluginId }
            if (selected != null) {
                PluginDetail(
                    snapshot = selected,
                    busy = busy,
                    onBack = { selectedPluginId = null },
                    onEnable = { runMutation { controlPlane.enable(selected.plugin.pluginId) } },
                    onDisable = { runMutation { controlPlane.disable(selected.plugin.pluginId) } },
                    onUpdate = { choosePlugin(selected.plugin.pluginId) },
                    onUninstall = { uninstallTargetId = selected.plugin.pluginId },
                    onRollback = { runMutation { controlPlane.rollback(selected.plugin.pluginId) } }
                )
            } else {
                PluginCenterHome(
                    snapshots = snapshots,
                    candidate = candidate,
                    busy = busy,
                    onChoose = { choosePlugin() },
                    onInstall = {
                        val current = candidate
                        if (current != null) {
                            if (current.updateTargetId != null && current.updateTargetId != current.manifest.pluginId) {
                                showError(IllegalArgumentException("更新包的 plugin_id 与目标插件不一致"))
                            } else {
                                runMutation {
                                    controlPlane.install(current.file, PluginInstallOptions())
                                    current.updateTargetId?.let { target ->
                                        controlPlane.activateVersion(target, current.manifest.version)
                                    }
                                    withContext(Dispatchers.Main) {
                                        current.file.delete()
                                        candidate = null
                                    }
                                }
                            }
                        }
                    },
                    onOpen = { selectedPluginId = it.plugin.pluginId },
                    onEnable = { snapshot -> runMutation { controlPlane.enable(snapshot.plugin.pluginId) } },
                    onDisable = { snapshot -> runMutation { controlPlane.disable(snapshot.plugin.pluginId) } },
                    onUpdate = { snapshot -> choosePlugin(snapshot.plugin.pluginId) },
                    onUninstall = { snapshot -> uninstallTargetId = snapshot.plugin.pluginId }
                )
            }
            if (busy) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    uninstallTargetId?.let { pluginId ->
        AlertDialog(
            onDismissRequest = { uninstallTargetId = null },
            title = { Text("卸载插件") },
            text = { Text("确定卸载 $pluginId？插件长期数据默认保留。") },
            confirmButton = {
                TextButton(onClick = {
                    uninstallTargetId = null
                    if (selectedPluginId == pluginId) selectedPluginId = null
                    runMutation { controlPlane.uninstall(pluginId, removeData = false) }
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { uninstallTargetId = null }) { Text("取消") } }
        )
    }
}
@Composable
private fun PluginCenterHome(
    snapshots: List<PluginControlSnapshot>,
    candidate: PluginImportCandidate?,
    busy: Boolean,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onOpen: (PluginControlSnapshot) -> Unit,
    onEnable: (PluginControlSnapshot) -> Unit,
    onDisable: (PluginControlSnapshot) -> Unit,
    onUpdate: (PluginControlSnapshot) -> Unit,
    onUninstall: (PluginControlSnapshot) -> Unit
) {
    val systemPlugins = snapshots.filter(::isSystemPlugin)
    val installedPlugins = snapshots.filterNot(::isSystemPlugin)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Plugin Center", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ImportPanel(candidate = candidate, busy = busy, onChoose = onChoose, onInstall = onInstall)
        PluginSection(
            title = "系统插件",
            emptyText = "当前为空",
            items = systemPlugins,
            onOpen = onOpen,
            onEnable = onEnable,
            onDisable = onDisable,
            onUpdate = onUpdate,
            onUninstall = onUninstall
        )
        PluginSection(
            title = "已安装插件",
            emptyText = "当前为空",
            items = installedPlugins,
            onOpen = onOpen,
            onEnable = onEnable,
            onDisable = onDisable,
            onUpdate = onUpdate,
            onUninstall = onUninstall
        )
        if (snapshots.isEmpty()) {
            Divider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(10.dp))
                Text("尚未安装任何插件", fontWeight = FontWeight.Medium)
                Text(
                    "点击“添加插件”导入 .ailp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ImportPanel(
    candidate: PluginImportCandidate?,
    busy: Boolean,
    onChoose: () -> Unit,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onChoose, enabled = !busy) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" 添加插件")
            }
            if (candidate == null) {
                Text(
                    "选择 .ailp 后将在这里显示插件名称和功能说明",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(candidate.manifest.display.name, fontWeight = FontWeight.Bold)
                Text(
                    candidate.manifest.display.description ?: "未提供功能说明",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "v${candidate.manifest.version} · ${candidate.manifest.activationMode.wireName} · ${candidate.manifest.runtime.kind}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onInstall, enabled = !busy) { Text(if (candidate.updateTargetId == null) "安装" else "保存更新") }
            }
        }
    }
}

@Composable
private fun PluginSection(
    title: String,
    emptyText: String,
    items: List<PluginControlSnapshot>,
    onOpen: (PluginControlSnapshot) -> Unit,
    onEnable: (PluginControlSnapshot) -> Unit,
    onDisable: (PluginControlSnapshot) -> Unit,
    onUpdate: (PluginControlSnapshot) -> Unit,
    onUninstall: (PluginControlSnapshot) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (items.isEmpty()) {
        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        items.forEach { snapshot ->
            PluginCard(
                snapshot = snapshot,
                onOpen = { onOpen(snapshot) },
                onEnable = { onEnable(snapshot) },
                onDisable = { onDisable(snapshot) },
                onUpdate = { onUpdate(snapshot) },
                onUninstall = { onUninstall(snapshot) }
            )
        }
    }
}

@Composable
private fun PluginCard(
    snapshot: PluginControlSnapshot,
    onOpen: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit
) {
    val manifest = snapshot.plugin.activeManifest
    val state = snapshot.plugin.persistentState
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(snapshot)
                Spacer(Modifier.size(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(manifest?.display?.name ?: snapshot.plugin.pluginId, fontWeight = FontWeight.Bold)
                    manifest?.display?.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Text(
                "v${state?.activeVersion ?: "-"} · ${manifest?.activationMode?.wireName ?: "-"} · ${manifest?.runtime?.kind ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state?.enabled == true) {
                    TextButton(onClick = onDisable) { Text("禁用") }
                } else {
                    TextButton(onClick = onEnable) { Text("启用") }
                }
                TextButton(onClick = onUpdate) { Text("更新") }
                TextButton(onClick = onUninstall) { Text("卸载") }
            }
        }
    }
}

@Composable
private fun StatusDot(snapshot: PluginControlSnapshot) {
    val state = snapshot.plugin.persistentState
    val color = when {
        snapshot.health == PluginHealthState.FAILED -> Color(0xFFE65100)
        state?.lastState == PluginLifecycleState.ACTIVE -> Color(0xFF00C853)
        state?.enabled == false -> Color(0xFFD32F2F)
        else -> Color(0xFFFFB300)
    }
    Box(modifier = Modifier.size(11.dp).background(color, CircleShape))
}

private fun isSystemPlugin(snapshot: PluginControlSnapshot): Boolean {
    val roles = snapshot.plugin.activeManifest?.roles.orEmpty()
    return roles.any { it == "system" || it == "system_plugin" || it == "system_service" }
}

@Composable
private fun PluginDetail(
    snapshot: PluginControlSnapshot,
    busy: Boolean,
    onBack: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onRollback: () -> Unit
) {
    val manifest = snapshot.plugin.activeManifest
    val state = snapshot.plugin.persistentState
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 返回 Plugin Center") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(snapshot)
            Spacer(Modifier.size(10.dp))
            Text(manifest?.display?.name ?: snapshot.plugin.pluginId, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        manifest?.display?.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        DetailLine("版本", state?.activeVersion ?: "-")
        DetailLine("状态", state?.lastState?.name ?: "-")
        DetailLine("运行时", manifest?.runtime?.kind ?: "-")
        DetailLine("激活模式", manifest?.activationMode?.wireName ?: "-")
        DetailLine("安装位置", "AI Limbs Plugin Store")
        DetailLine("插件 ID", snapshot.plugin.pluginId)
        DetailLine("已挂载版本", snapshot.plugin.mountedVersion ?: "未挂载")
        Divider()
        Text("权限", fontWeight = FontWeight.Bold)
        val scopes = manifest?.permissions?.requestedScopes.orEmpty()
        Text(if (scopes.isEmpty()) "未声明权限" else scopes.joinToString("\n"))
        Text("依赖", fontWeight = FontWeight.Bold)
        val pluginDeps = manifest?.dependencies?.plugins.orEmpty()
        val serviceDeps = manifest?.dependencies?.services.orEmpty()
        if (pluginDeps.isEmpty() && serviceDeps.isEmpty()) {
            Text("无")
        } else {
            pluginDeps.forEach { Text("插件：${it.pluginId}${it.minVersion?.let { v -> " >= $v" } ?: ""}") }
            serviceDeps.forEach { Text("服务：${it.serviceId}${it.minApi?.let { api -> " API >= $api" } ?: ""}") }
        }
        Divider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state?.enabled == true) {
                Button(onClick = onDisable, enabled = !busy) { Text("禁用") }
            } else {
                Button(onClick = onEnable, enabled = !busy) { Text("启用") }
            }
            OutlinedButton(onClick = onUpdate, enabled = !busy) { Text("更新") }
            OutlinedButton(onClick = onUninstall, enabled = !busy) { Text("卸载") }
        }
        Text("版本管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DetailLine("当前版本", state?.activeVersion ?: "-")
        DetailLine("上一版本", state?.previousVersion ?: "无")
        if (state?.previousVersion != null) {
            OutlinedButton(onClick = onRollback, enabled = !busy) { Text("回滚") }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.65f))
    }
}
