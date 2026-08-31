package com.ai.assistance.operit.ui.pluginlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.plugins.center.PluginCenterKernel
import com.ai.assistance.operit.ui.features.toolbox.screens.Tool
import com.ai.assistance.operit.ui.features.toolbox.screens.ToolCard
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.LogcatScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.plugincenter.PluginCenterScreen
import com.ai.assistance.operit.ui.theme.OperitTheme
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PluginLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.resetLogFile()
        PluginCenterKernel.initialize(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) { PluginCenterKernel.start() }
        setContent { OperitTheme { PluginLabRoot() } }
    }
}

private enum class LabPage { HOME, LOGCAT, PLUGIN_CENTER }

@Composable
private fun PluginLabRoot() {
    var page by remember { mutableStateOf(LabPage.HOME) }
    BackHandler(enabled = page != LabPage.HOME) { page = LabPage.HOME }
    when (page) {
        LabPage.HOME -> PluginLabHome(onOpen = { page = it })
        LabPage.LOGCAT -> LogcatScreen()
        LabPage.PLUGIN_CENTER -> PluginCenterScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginLabHome(onOpen: (LabPage) -> Unit) {
    val tools = remember {
        listOf(
            Tool(
                id = "runtime_log",
                name = "日志查看器",
                icon = Icons.Default.Description,
                description = "V0.6.4.7.8 原生日志工具",
                onClick = { onOpen(LabPage.LOGCAT) }
            ),
            Tool(
                id = "plugin_center",
                name = "Plugin Center",
                icon = Icons.Default.Extension,
                description = "插件安装、状态与运行时总控中心",
                onClick = { onOpen(LabPage.PLUGIN_CENTER) }
            )
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text("AI Limbs Plugin Lab") }) }) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
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
                items(tools, key = { it.id }) { tool -> ToolCard(tool) }
            }
        }
    }
}
