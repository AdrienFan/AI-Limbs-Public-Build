package com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

private sealed interface AdminAction {
    data object OpenSettings : AdminAction
    data class DisableSystem(val pluginId: String) : AdminAction
    data class Uninstall(val pluginId: String) : AdminAction
}
@Composable
fun PluginCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controlPlane = remember { PluginCenterKernel.controlPlane }
    val adminSecurity = remember { PluginCenterKernel.adminSecurity }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var snapshots by remember { mutableStateOf<List<PluginControlSnapshot>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<PluginImportCandidate>>(emptyList()) }
    var updateTargetId by remember { mutableStateOf<String?>(null) }
    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    var disableSystemTargetId by remember { mutableStateOf<String?>(null) }
    var uninstallTargetId by remember { mutableStateOf<String?>(null) }
    var showAdminSettings by remember { mutableStateOf(false) }
    var pendingAdminAction by remember { mutableStateOf<AdminAction?>(null) }
    var showAdminSetup by remember { mutableStateOf(false) }
    var showAdminPassword by remember { mutableStateOf(false) }
    var showAdminRecovery by remember { mutableStateOf(false) }
    var recoveryKeyToShow by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        snapshots = withContext(Dispatchers.IO) { controlPlane.snapshots() }
    }

    fun showError(error: Throwable) {
        scope.launch {
            snackbarHostState.showSnackbar(error.message ?: error::class.java.simpleName)
        }
    }

    fun completeAdminAction(action: AdminAction) {
        when (action) {
            AdminAction.OpenSettings -> showAdminSettings = true
            is AdminAction.DisableSystem -> disableSystemTargetId = action.pluginId
            is AdminAction.Uninstall -> uninstallTargetId = action.pluginId
        }
    }

    fun actionRequiresFreshPassword(action: AdminAction): Boolean = when (action) {
        AdminAction.OpenSettings -> true
        is AdminAction.DisableSystem -> true
        is AdminAction.Uninstall -> {
            val target = snapshots.firstOrNull { it.plugin.pluginId == action.pluginId }
            target == null || isSystemPlugin(target)
        }
    }

    fun requestAdmin(action: AdminAction) {
        val security = adminSecurity.snapshot()
        if (!security.configured) {
            pendingAdminAction = action
            showAdminSetup = true
        } else if (actionRequiresFreshPassword(action) || adminSecurity.authorizationRequired()) {
            pendingAdminAction = action
            showAdminPassword = true
        } else {
            completeAdminAction(action)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    suspend fun importCandidate(uri: android.net.Uri, targetPluginId: String?): PluginImportCandidate {
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "plugin_center_import").apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID()}.ailp")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选插件文件" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val manifest = controlPlane.inspectPackage(target)
            PluginImportCandidate(target, manifest, targetPluginId)
        }
    }

    fun mergeCandidates(imported: List<PluginImportCandidate>) {
        var updated = candidates
        imported.forEach { candidate ->
            val previous = updated.firstOrNull { item ->
                item.manifest.pluginId == candidate.manifest.pluginId &&
                    item.manifest.version == candidate.manifest.version &&
                    item.updateTargetId == candidate.updateTargetId
            }
            previous?.file?.delete()
            updated = updated.filterNot { item ->
                item.manifest.pluginId == candidate.manifest.pluginId &&
                    item.manifest.version == candidate.manifest.version &&
                    item.updateTargetId == candidate.updateTargetId
            } + candidate
        }
        candidates = updated
    }

    val addFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val imported = uris.map { uri -> importCandidate(uri, null) }
                mergeCandidates(imported)
            }.onFailure(::showError)
            busy = false
        }
    }

    val updateFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetPluginId = updateTargetId
        if (uri == null || targetPluginId == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                mergeCandidates(listOf(importCandidate(uri, targetPluginId)))
            }.onFailure(::showError)
            busy = false
            updateTargetId = null
        }
    }

    fun choosePlugin(targetPluginId: String? = null) {
        val mimeTypes = arrayOf("application/zip", "application/octet-stream", "*/*")
        if (targetPluginId == null) {
            addFileLauncher.launch(mimeTypes)
        } else {
            updateTargetId = targetPluginId
            updateFileLauncher.launch(mimeTypes)
        }
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
            if (showAdminSettings) {
                PluginAdminSettingsScreen(
                    controlPlane = controlPlane,
                    adminSecurity = adminSecurity,
                    onBack = {
                        showAdminSettings = false
                        scope.launch { refresh() }
                    },
                    onError = ::showError,
                    onPolicyChanged = { scope.launch { refresh() } }
                )
            } else if (selected != null) {
                PluginDetail(
                    snapshot = selected,
                    busy = busy,
                    onBack = { selectedPluginId = null },
                    onEnable = { runMutation { controlPlane.enable(selected.plugin.pluginId) } },
                    onDisable = {
                        if (isSystemPlugin(selected)) {
                            requestAdmin(AdminAction.DisableSystem(selected.plugin.pluginId))
                        } else {
                            runMutation { controlPlane.disable(selected.plugin.pluginId) }
                        }
                    },
                    onUpdate = { choosePlugin(selected.plugin.pluginId) },
                    onUninstall = { requestAdmin(AdminAction.Uninstall(selected.plugin.pluginId)) },
                    onRollback = { runMutation { controlPlane.rollback(selected.plugin.pluginId) } }
                )
            } else {
                PluginCenterHome(
                    snapshots = snapshots,
                    candidates = candidates,
                    busy = busy,
                    onBack = onBack,
                    onOpenSettings = { requestAdmin(AdminAction.OpenSettings) },
                    onChoose = { choosePlugin() },
                    onClearCandidates = {
                        candidates.forEach { it.file.delete() }
                        candidates = emptyList()
                    },
                    onRemoveCandidate = { target ->
                        target.file.delete()
                        candidates = candidates.filterNot { it.file == target.file }
                    },
                    onInstall = {
                        val queue = candidates
                        val mismatch = queue.firstOrNull {
                            it.updateTargetId != null && it.updateTargetId != it.manifest.pluginId
                        }
                        if (mismatch != null) {
                            showError(IllegalArgumentException("更新包的 plugin_id 与目标插件不一致"))
                        } else if (queue.isNotEmpty()) {
                            runMutation {
                                queue.forEach { current ->
                                    controlPlane.install(
                                        current.file,
                                        PluginInstallOptions(
                                            allowUntrustedForDevelopment = true,
                                            enableAfterInstall = current.updateTargetId == null,
                                            approvedScopes = current.manifest.permissions.requestedScopes
                                        )
                                    )
                                    current.updateTargetId?.let { target ->
                                        controlPlane.activateVersion(target, current.manifest.version)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    queue.forEach { it.file.delete() }
                                    candidates = emptyList()
                                }
                            }
                        }
                    },
                    onOpen = { selectedPluginId = it.plugin.pluginId },
                    onEnable = { snapshot -> runMutation { controlPlane.enable(snapshot.plugin.pluginId) } },
                    onDisable = { snapshot ->
                        if (isSystemPlugin(snapshot)) {
                            requestAdmin(AdminAction.DisableSystem(snapshot.plugin.pluginId))
                        } else {
                            runMutation { controlPlane.disable(snapshot.plugin.pluginId) }
                        }
                    },
                    onUpdate = { snapshot -> choosePlugin(snapshot.plugin.pluginId) },
                    onUninstall = { snapshot -> requestAdmin(AdminAction.Uninstall(snapshot.plugin.pluginId)) }
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

    disableSystemTargetId?.let { pluginId ->
        AlertDialog(
            onDismissRequest = { disableSystemTargetId = null },
            title = { Text("禁用系统插件") },
            text = { Text("确定禁用 $pluginId？系统插件可能影响 AI Limbs 的基础运行能力。") },
            confirmButton = {
                TextButton(onClick = {
                    disableSystemTargetId = null
                    runMutation { controlPlane.disable(pluginId, adminAuthorized = true) }
                }) { Text("确认禁用") }
            },
            dismissButton = {
                TextButton(onClick = { disableSystemTargetId = null }) { Text("取消") }
            }
        )
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
                    val systemPlugin = snapshots.firstOrNull { it.plugin.pluginId == pluginId }
                        ?.let(::isSystemPlugin) == true
                    runMutation {
                        controlPlane.uninstall(
                            pluginId,
                            removeData = false,
                            adminAuthorized = systemPlugin
                        )
                    }
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { uninstallTargetId = null }) { Text("取消") } }
        )
    }

    if (showAdminSetup) {
        AdminSetupDialog(
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminSetup = false
                pendingAdminAction = null
            },
            onConfigured = { recoveryKey ->
                showAdminSetup = false
                recoveryKeyToShow = recoveryKey
            }
        )
    }

    if (showAdminPassword) {
        AdminPasswordDialog(
            title = when (pendingAdminAction) {
                AdminAction.OpenSettings -> "管理员验证"
                is AdminAction.DisableSystem -> "验证后允许禁用系统插件"
                is AdminAction.Uninstall -> "验证后允许卸载插件"
                null -> "管理员验证"
            },
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminPassword = false
                pendingAdminAction = null
            },
            onVerified = {
                showAdminPassword = false
                pendingAdminAction?.let(::completeAdminAction)
                pendingAdminAction = null
            },
            onForgotPassword = {
                showAdminPassword = false
                showAdminRecovery = true
            }
        )
    }

    if (showAdminRecovery) {
        AdminRecoveryDialog(
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminRecovery = false
                pendingAdminAction = null
            },
            onRecovered = {
                showAdminRecovery = false
                pendingAdminAction?.let(::completeAdminAction)
                pendingAdminAction = null
            }
        )
    }

    recoveryKeyToShow?.let { key ->
        RecoveryKeyDialog(key) {
            recoveryKeyToShow = null
            pendingAdminAction?.let(::completeAdminAction)
            pendingAdminAction = null
        }
    }
}
@Composable
private fun PluginCenterHome(
    snapshots: List<PluginControlSnapshot>,
    candidates: List<PluginImportCandidate>,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onClearCandidates: () -> Unit,
    onRemoveCandidate: (PluginImportCandidate) -> Unit,
    onOpen: (PluginControlSnapshot) -> Unit,
    onEnable: (PluginControlSnapshot) -> Unit,
    onDisable: (PluginControlSnapshot) -> Unit,
    onUpdate: (PluginControlSnapshot) -> Unit,
    onUninstall: (PluginControlSnapshot) -> Unit
) {
    var searchInput by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(PluginSortMode.NAME_ASC) }
    var systemExpanded by remember { mutableStateOf(true) }
    var installedExpanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val allSystemPlugins = remember(snapshots) { snapshots.filter(::isSystemPlugin) }
    val allInstalledPlugins = remember(snapshots) { snapshots.filterNot(::isSystemPlugin) }
    val systemPlugins = remember(allSystemPlugins, appliedQuery, sortMode) {
        filterAndSortPlugins(allSystemPlugins, appliedQuery, sortMode)
    }
    val installedPlugins = remember(allInstalledPlugins, appliedQuery, sortMode) {
        filterAndSortPlugins(allInstalledPlugins, appliedQuery, sortMode)
    }
    val searching = appliedQuery.isNotBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 23.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "plugin-center-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        "Plugin Center",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "管理员安全与开发设置")
                    }
                }
            }
            item(key = "plugin-import") {
                ImportPanel(
                    candidates = candidates,
                    busy = busy,
                    onChoose = onChoose,
                    onInstall = onInstall,
                    onClear = onClearCandidates,
                    onRemove = onRemoveCandidate
                )
            }
            item(key = "plugin-search-sort") {
                PluginSearchSortControls(
                    input = searchInput,
                    appliedQuery = appliedQuery,
                    sortMode = sortMode,
                    onInputChange = { searchInput = it },
                    onApplySearch = { appliedQuery = searchInput.trim() },
                    onClearSearch = {
                        searchInput = ""
                        appliedQuery = ""
                    },
                    onSortModeChange = { sortMode = it }
                )
            }
            item(key = "system-header") {
                CollapsiblePluginSectionHeader(
                    title = "系统插件",
                    expanded = systemExpanded,
                    matchedCount = systemPlugins.size,
                    totalCount = allSystemPlugins.size,
                    searching = searching,
                    onToggle = { systemExpanded = !systemExpanded }
                )
            }
            if (systemExpanded) {
                if (systemPlugins.isEmpty()) {
                    item(key = "system-empty") {
                        Text(if (searching) "系统插件中没有搜索结果" else "当前为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(systemPlugins, key = { "system:${it.plugin.pluginId}" }) { snapshot ->
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
            item(key = "installed-header") {
                CollapsiblePluginSectionHeader(
                    title = "已安装插件",
                    expanded = installedExpanded,
                    matchedCount = installedPlugins.size,
                    totalCount = allInstalledPlugins.size,
                    searching = searching,
                    onToggle = { installedExpanded = !installedExpanded }
                )
            }
            if (installedExpanded) {
                if (installedPlugins.isEmpty()) {
                    item(key = "installed-empty") {
                        Text(if (searching) "已安装插件中没有搜索结果" else "当前为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(installedPlugins, key = { "installed:${it.plugin.pluginId}" }) { snapshot ->
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
            if (snapshots.isEmpty()) {
                item(key = "all-empty") {
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
        LazyListScrollIndicator(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun ImportPanel(
    candidates: List<PluginImportCandidate>,
    busy: Boolean,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onClear: () -> Unit,
    onRemove: (PluginImportCandidate) -> Unit
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
            if (candidates.isEmpty()) {
                Text(
                    "选择 .ailp 后将在这里形成待安装队列；可以连续添加多个插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("待安装插件（${candidates.size}）", fontWeight = FontWeight.Bold)
                candidates.forEachIndexed { index, candidate ->
                    if (index > 0) Divider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                candidate.manifest.display.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { onRemove(candidate) }, enabled = !busy) {
                                Text("移除")
                            }
                        }
                        Text(
                            candidate.manifest.display.description ?: "未提供功能说明",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "v${candidate.manifest.version} · ${candidate.manifest.activationMode.wireName} · ${candidate.manifest.runtime.kind}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val scopes = candidate.manifest.permissions.requestedScopes
                        Text(
                            "请求权限：" + if (scopes.isEmpty()) "无" else scopes.joinToString(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    "批准后将按队列顺序安装，并明确批准各插件上方列出的权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall, enabled = !busy) {
                        Text(if (candidates.size == 1) "批准并安装" else "批准并安装全部")
                    }
                    OutlinedButton(onClick = onClear, enabled = !busy) {
                        Text("清除全部")
                    }
                }
            }
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
            Text(
                usageSummary(snapshot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state?.lastState == PluginLifecycleState.BLOCKED && !state.lastError.isNullOrBlank()) {
                Text(
                    state.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
    return roles.any { it == "system" || it == "system_plugin" || it == "system_service" || it == "system_extension_hub" || it == "system_bridge" }
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
        state?.lastError?.takeIf { it.isNotBlank() }?.let { DetailLine("状态说明", it) }
        DetailLine("运行时", manifest?.runtime?.kind ?: "-")
        DetailLine("激活模式", manifest?.activationMode?.wireName ?: "-")
        DetailLine("安装位置", "AI Limbs Plugin Store")
        DetailLine("插件 ID", snapshot.plugin.pluginId)
        DetailLine("已挂载版本", snapshot.plugin.mountedVersion ?: "未挂载")
        DetailLine("使用次数", snapshot.plugin.usage.useCount.toString())
        DetailLine("最近使用", usageSummary(snapshot).substringAfter("最近使用："))
        Divider()
        Text("权限", fontWeight = FontWeight.Bold)
        val scopes = manifest?.permissions?.requestedScopes.orEmpty()
        Text(if (scopes.isEmpty()) "未声明权限" else scopes.joinToString("\n"))
        Text("提供的能力", fontWeight = FontWeight.Bold)
        val capabilities = manifest?.provides?.capabilities.orEmpty()
        Text(if (capabilities.isEmpty()) "无" else capabilities.joinToString("\n"))
        Text("提供的界面扩展", fontWeight = FontWeight.Bold)
        val extensions = manifest?.provides?.extensions.orEmpty()
        Text(if (extensions.isEmpty()) "无" else extensions.joinToString("\n") { it.point + " / " + it.id })
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
