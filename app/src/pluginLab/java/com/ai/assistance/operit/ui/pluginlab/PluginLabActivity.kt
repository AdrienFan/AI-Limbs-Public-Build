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
import com.ai.assistance.operit.plugins.center.PluginScreenSpec
import com.ai.assistance.operit.plugins.center.PluginThemeMode
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.PluginCenterScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.PluginCollectionSection
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.ScrollStateScrollIndicator
import com.ai.limbs.plugin.runtime.ChildExtensionLifecycle
import com.ai.limbs.plugin.runtime.ExtensionHubService
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
    val scrollState = rememberScrollState()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 16.dp, top = 16.dp, end = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            screen.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Schema: ${screen.schemaId}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "此实验壳只保存与路由 opaque UI document；组件语义与交互由 Plugin Center 自己的 renderer 负责。",
                style = MaterialTheme.typography.bodySmall
            )
            SelectionContainer {
                Text(
                    screen.documentJson,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        ScrollStateScrollIndicator(
            state = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}
