package com.ai.assistance.operit.ui.pluginlab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.plugins.center.PluginCenterKernel
import com.ai.assistance.operit.plugins.center.compatBackup
import com.ai.assistance.operit.plugins.center.compatBackupSnapshots
import com.ai.assistance.operit.plugins.center.PluginContributionKind
import com.ai.assistance.operit.plugins.center.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.center.PluginScreenBlock
import com.ai.assistance.operit.plugins.center.PluginScreenSpec
import com.ai.assistance.operit.plugins.center.PluginThemeMode
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.PluginCenterScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.PluginCollectionSection
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.ScrollStateScrollIndicator
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

class PluginLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginCenterKernel.initialize(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) { PluginCenterKernel.start() }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            PluginCenterKernel.refreshNotifications()
        }
        setContent { PluginLabThemeHost() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) PluginCenterKernel.refreshNotifications()
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 7053
    }
}

@Composable
private fun PluginLabThemeHost() {
    val pluginTheme by PluginCenterKernel.uiRegistry.activeTheme.collectAsState()
    val baseColors = when (pluginTheme?.mode) {
        PluginThemeMode.DARK -> {
            if (pluginTheme?.pureBlack == true) {
                darkColorScheme(background = Color.Black, surface = Color.Black)
            } else {
                darkColorScheme()
            }
        }
        PluginThemeMode.LIGHT, null -> lightColorScheme()
    }
    val colors = applyThemeColors(baseColors, pluginTheme?.colors.orEmpty())
    val gradient = pluginTheme?.backgroundGradient.orEmpty().map(::parseThemeColor)
    MaterialTheme(colorScheme = colors) {
        if (gradient.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(gradient))
            ) {
                PluginLabRoot()
            }
        } else {
            PluginLabRoot()
        }
    }
}

private fun applyThemeColors(base: ColorScheme, values: Map<String, String>): ColorScheme {
    fun value(key: String, fallback: Color): Color = values[key]?.let(::parseThemeColor) ?: fallback
    return base.copy(
        primary = value("primary", base.primary),
        onPrimary = value("on_primary", base.onPrimary),
        primaryContainer = value("primary_container", base.primaryContainer),
        onPrimaryContainer = value("on_primary_container", base.onPrimaryContainer),
        secondary = value("secondary", base.secondary),
        onSecondary = value("on_secondary", base.onSecondary),
        secondaryContainer = value("secondary_container", base.secondaryContainer),
        onSecondaryContainer = value("on_secondary_container", base.onSecondaryContainer),
        tertiary = value("tertiary", base.tertiary),
        onTertiary = value("on_tertiary", base.onTertiary),
        tertiaryContainer = value("tertiary_container", base.tertiaryContainer),
        onTertiaryContainer = value("on_tertiary_container", base.onTertiaryContainer),
        background = value("background", base.background),
        onBackground = value("on_background", base.onBackground),
        surface = value("surface", base.surface),
        onSurface = value("on_surface", base.onSurface),
        surfaceVariant = value("surface_variant", base.surfaceVariant),
        onSurfaceVariant = value("on_surface_variant", base.onSurfaceVariant),
        outline = value("outline", base.outline),
        error = value("error", base.error),
        onError = value("on_error", base.onError)
    )
}

private fun parseThemeColor(raw: String): Color = Color(AndroidColor.parseColor(raw))

private sealed class LabPage {
    object Home : LabPage()
    object PluginCenter : LabPage()
    data class PluginScreen(val screenId: String) : LabPage()
}

@Composable
private fun PluginLabRoot() {
    var page by remember { mutableStateOf<LabPage>(LabPage.Home) }
    BackHandler(enabled = page != LabPage.Home) { page = LabPage.Home }
    when (val current = page) {
        LabPage.Home -> PluginLabHome(
            onOpenCenter = { page = LabPage.PluginCenter },
            onOpenPlugin = { page = LabPage.PluginScreen(it) }
        )
        LabPage.PluginCenter -> PluginCenterScreen(onBack = { page = LabPage.Home })
        is LabPage.PluginScreen -> PluginSurface(current.screenId) { page = LabPage.Home }
    }
}

private data class LabTool(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginLabHome(
    onOpenCenter: () -> Unit,
    onOpenPlugin: (String) -> Unit
) {
    val pluginTiles by PluginCenterKernel.uiRegistry.homeTiles.collectAsState()
    val tools = remember(pluginTiles) {
        listOf(
            LabTool(
                id = "plugin_center",
                name = "Plugin Center",
                description = "安装、授权、启停、升级与回滚插件",
                icon = Icons.Default.Extension,
                onClick = onOpenCenter
            )
        ) + pluginTiles.map { tile ->
            LabTool(
                id = "plugin:" + tile.ownerPluginId + ":" + tile.id,
                name = tile.title,
                description = tile.description,
                icon = Icons.Default.Extension,
                onClick = { onOpenPlugin(tile.screenId) }
            )
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("AI Limbs Plugin Lab") }) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(tools, key = { it.id }) { LabToolCard(it) }
        }
    }
}

@Composable
private fun LabToolCard(tool: LabTool) {
    Card(
        onClick = tool.onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(tool.icon, contentDescription = null)
            Text(tool.name, style = MaterialTheme.typography.titleMedium)
            Text(tool.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSurface(screenId: String, onBack: () -> Unit) {
    val screens by PluginCenterKernel.uiRegistry.activeScreens.collectAsState()
    val screen = screens.firstOrNull { it.id == screenId }
    if (screen == null) {
        LaunchedEffect(screenId) { onBack() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(screen.id, screen.ownerPluginId) {
        withContext(Dispatchers.IO) { PluginCenterKernel.recordPluginUse(screen.ownerPluginId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        PluginScreenContent(screen, Modifier.padding(padding))
    }
}

@Composable
private fun PluginScreenContent(screen: PluginScreenSpec, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val results = remember(screen.id) { mutableStateMapOf<Int, String>() }
    val busy = remember(screen.id) { mutableStateMapOf<Int, Boolean>() }
    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 16.dp, top = 16.dp, end = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            screen.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            screen.blocks.forEachIndexed { index, block ->
                when (block) {
                    is PluginScreenBlock.Text -> Text(block.text)
                    is PluginScreenBlock.CapabilityButton -> {
                        Button(
                            enabled = busy[index] != true,
                            onClick = {
                                scope.launch {
                                    busy[index] = true
                                    results[index] = try {
                                        val value = withContext(Dispatchers.IO) {
                                            PluginCenterKernel.capabilities.invokePlugin(
                                                block.capabilityId,
                                                block.parameters
                                            )
                                        }
                                        value.optString("content").ifBlank { value.toString(2) }
                                    } catch (error: Throwable) {
                                        "执行失败：" + (error.message ?: "未知错误")
                                    } finally {
                                        busy[index] = false
                                    }
                                }
                            }
                        ) {
                            Text(if (busy[index] == true) "执行中…" else block.label)
                        }
                        results[index]?.let { result ->
                            SelectionContainer {
                                Text(
                                    result,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    is PluginScreenBlock.ChildExtensionInstaller -> ChildExtensionInstallerBlock(
                        block = block,
                        onResult = { results[index] = it }
                    )
                    is PluginScreenBlock.ChildExtensionList -> ChildExtensionListBlock(
                        point = block.point,
                        onResult = { results[index] = it }
                    )
                    is PluginScreenBlock.ChildExtensionSelector -> ChildExtensionSelectorBlock(
                        block = block,
                        onResult = { results[index] = it }
                    )
                    is PluginScreenBlock.DynamicPanel -> DynamicPanelBlock(
                        providerId = block.providerId
                    )
                }
            }
        }
        ScrollStateScrollIndicator(
            state = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

private fun activeExtensionHub(): ExtensionHubService? =
    PluginCenterKernel.contributions
        .find(PluginContributionKind.PROVIDER, InProcessSystemIds.EXTENSION_HUB_PROVIDER)
        ?.payload as? ExtensionHubService

@Composable
private fun ChildExtensionInstallerBlock(
    block: PluginScreenBlock.ChildExtensionInstaller,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = activeExtensionHub()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (hub == null) {
                onResult("Plugin Extension Hub 未启用")
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
                        onResult("已安装 ${snapshot.displayName} ${snapshot.version} · ${snapshot.lifecycle}")
                    } catch (error: Throwable) {
                        onResult("子插件安装失败：${error.message ?: "未知错误"}")
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            enabled = hub != null,
            onClick = { launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        ) { Text(block.label) }
        Text(
            if (hub == null) "Plugin Extension Hub 未启用" else "仅接受 AIL_EXTENSION_V1 / .ailx",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ChildExtensionListBlock(point: String, onResult: (String) -> Unit) {
    val hub = activeExtensionHub()
    if (hub == null) {
        Text("Plugin Extension Hub 未启用")
        return
    }
    val snapshots by hub.snapshotsForPoint(point).collectAsState()
    val backupFlow = remember(hub) { hub.compatBackupSnapshots() }
    val backups by backupFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by remember(point) { mutableStateOf(false) }
    var query by remember(point) { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val filtered = remember(snapshots, normalized) {
        if (normalized.isBlank()) snapshots else snapshots.filter { snapshot ->
            listOf(
                snapshot.displayName, snapshot.extensionId, snapshot.description.orEmpty(),
                snapshot.lifecycle.name, snapshot.target.point
            ).any { it.lowercase().contains(normalized) }
        }
    }
    PluginCollectionSection(
        title = "已安装子插件",
        totalCount = snapshots.size,
        matchedCount = filtered.size,
        query = query,
        onQueryChange = { query = it },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        searchPlaceholder = "搜索子插件"
    ) {
        if (filtered.isEmpty()) {
            Text(
                if (snapshots.isEmpty()) "尚未安装子插件" else "没有匹配的子插件",
                style = MaterialTheme.typography.bodySmall
            )
        }
        filtered.forEach { snapshot ->
            val backupVersion = backups.firstOrNull { it.extensionId == snapshot.extensionId }?.version
            val canBackup = backupVersion != snapshot.version
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("${snapshot.displayName} · ${snapshot.version}", style = MaterialTheme.typography.titleSmall)
                    Text("${snapshot.extensionId} · ${snapshot.lifecycle}", style = MaterialTheme.typography.bodySmall)
                    Text("使用 ${snapshot.useCount} 次 · 备份：${backupVersion ?: "未备份"}", style = MaterialTheme.typography.bodySmall)
                    snapshot.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                runCatching { hub.setEnabled(snapshot.extensionId, !snapshot.enabled) }
                                    .onSuccess { onResult("${it.displayName} → ${it.lifecycle}") }
                                    .onFailure { onResult("操作失败：${it.message}") }
                            }
                        }) { Text(if (snapshot.enabled) "禁用" else "启用") }
                        Button(onClick = {
                            scope.launch {
                                runCatching { hub.uninstall(snapshot.extensionId) }
                                    .onSuccess { onResult(if (it) "已卸载 ${snapshot.displayName}" else "子插件不存在") }
                                    .onFailure { onResult("卸载失败：${it.message}") }
                            }
                        }) { Text("卸载") }
                        Button(
                            enabled = canBackup,
                            onClick = {
                                scope.launch {
                                    runCatching { hub.compatBackup(snapshot.extensionId) }
                                        .onSuccess { onResult("已备份 ${it.displayName} ${it.version}") }
                                        .onFailure { onResult("备份失败：${it.message}") }
                                }
                            }
                        ) { Text("备份") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicPanelBlock(providerId: String) {
    val provider = PluginCenterKernel.contributions
        .find(PluginContributionKind.PROVIDER, providerId)
        ?.payload as? InProcessDynamicPanelProvider
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
            if (field.id !in values || values[field.id] == previousInitial) {
                values[field.id] = field.value
            }
            initialValues[field.id] = field.value
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current.title, style = MaterialTheme.typography.titleMedium)
            current.description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            current.statusLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
            current.fields.forEach { field ->
                val fieldValue = values[field.id] ?: field.value
                if (field.kind == InProcessPanelFieldKind.SECRET) {
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { values[field.id] = it },
                        label = { Text(field.label) },
                        placeholder = { Text(field.placeholder) },
                        enabled = field.enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { values[field.id] = it },
                        label = { Text(field.label) },
                        placeholder = { Text(field.placeholder) },
                        enabled = field.enabled,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            current.actions.forEach { action ->
                val requiredFieldsReady = action.requiredFieldIds.all { fieldId ->
                    values[fieldId].orEmpty().isNotBlank()
                }
                Button(
                    enabled = action.enabled && requiredFieldsReady && busyAction == null,
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
                ) {
                    Text(if (busyAction == action.id) "处理中…" else action.label)
                }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ChildExtensionSelectorBlock(
    block: PluginScreenBlock.ChildExtensionSelector,
    onResult: (String) -> Unit
) {
    val hub = activeExtensionHub()
    if (hub == null) {
        Text("Plugin Extension Hub 未启用")
        return
    }
    val snapshots by hub.snapshotsForPoint(block.point).collectAsState()
    val active = snapshots.filter { it.lifecycle == ChildExtensionLifecycle.ACTIVE }
    val selectionProvider = block.selectionProviderId?.let { providerId ->
        PluginCenterKernel.contributions
            .find(PluginContributionKind.PROVIDER, providerId)
            ?.payload as? InProcessSelectionProvider
    }
    val selectedExtensionId = selectionProvider?.selectedId?.collectAsState()?.value
    val selectedSnapshot = active.firstOrNull { it.extensionId == selectedExtensionId }
    val scope = rememberCoroutineScope()
    var expanded by remember(block.point) { mutableStateOf(false) }
    var localSelectedName by remember(block.point) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(block.label, style = MaterialTheme.typography.titleSmall)
        Box {
            Button(enabled = active.isNotEmpty(), onClick = { expanded = true }) {
                Text(
                    selectedSnapshot?.displayName
                        ?: localSelectedName
                        ?: active.firstOrNull()?.displayName
                        ?: "暂无 Provider"
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                active.forEach { snapshot ->
                    DropdownMenuItem(
                        text = { Text(snapshot.displayName) },
                        onClick = {
                            expanded = false
                            scope.launch {
                                try {
                                    val value = withContext(Dispatchers.IO) {
                                        PluginCenterKernel.capabilities.invokePlugin(
                                            block.selectCapabilityId,
                                            org.json.JSONObject().put("extension_id", snapshot.extensionId)
                                        )
                                    }
                                    localSelectedName = snapshot.displayName
                                    onResult(value.optString("content").ifBlank { value.toString(2) })
                                } catch (error: Throwable) {
                                    onResult("选择失败：${error.message ?: "未知错误"}")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
