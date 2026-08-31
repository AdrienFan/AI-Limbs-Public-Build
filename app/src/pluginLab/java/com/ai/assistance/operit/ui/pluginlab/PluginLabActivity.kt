package com.ai.assistance.operit.ui.pluginlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.plugins.center.PluginCenterKernel
import com.ai.assistance.operit.plugins.lab.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.lab.PluginScreenBlock
import com.ai.assistance.operit.plugins.lab.PluginScreenSpec
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.PluginCenterScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PluginLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginCenterKernel.initialize(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) { PluginCenterKernel.start() }
        setContent { MaterialTheme { PluginLabRoot() } }
    }
}

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
        LabPage.PluginCenter -> PluginCenterScreen()
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
            }
        }
    }
}
