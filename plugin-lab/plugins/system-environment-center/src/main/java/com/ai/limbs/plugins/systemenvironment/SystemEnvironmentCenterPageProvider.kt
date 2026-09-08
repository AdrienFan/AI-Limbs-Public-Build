package com.ai.limbs.plugins.systemenvironment

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiComponentIds
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdleMode
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdlePolicy
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimePhase
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class SystemEnvironmentCenterPageProvider(
    private val host: InProcessPluginHost,
    private val registry: SystemEnvironmentSubsystemRegistry
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SystemEnvironmentCenterPage(host, registry, sharedUi)
                }
            }
        }
}

private enum class CenterRoute { DISPLAY, ENVIRONMENT_CONFIG }

@Composable
private fun SystemEnvironmentCenterPage(
    host: InProcessPluginHost,
    registry: SystemEnvironmentSubsystemRegistry,
    sharedUi: InProcessSharedUiHost
) {
    val mounted by registry.mounted.collectAsState()
    val foregroundId by registry.foregroundExtensionId.collectAsState()
    var route by remember { mutableStateOf(CenterRoute.DISPLAY) }

    when (route) {
        CenterRoute.DISPLAY -> SystemEnvironmentDisplayPage(
            host = host,
            current = mounted.firstOrNull { it.extensionId == foregroundId },
            onOpenConfig = { route = CenterRoute.ENVIRONMENT_CONFIG }
        )
        CenterRoute.ENVIRONMENT_CONFIG -> EnvironmentConfigPage(
            mounted = mounted,
            foregroundId = foregroundId,
            sharedUi = sharedUi,
            onSelect = registry::setForeground,
            onBack = { route = CenterRoute.DISPLAY }
        )
    }
}

@Composable
private fun SystemEnvironmentDisplayPage(
    host: InProcessPluginHost,
    current: MountedSystemEnvironment?,
    onOpenConfig: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = current?.displayName ?: "系统环境中心",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onOpenConfig) { Text("环境配置") }
            PresentationButton(host, "fullscreen_portrait", "全屏")
            PresentationButton(host, "fullscreen_landscape", "横屏")
            PresentationButton(host, "normal", "退出全屏")
        }

        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有前台系统环境", color = Color.White)
                    Text(
                        "请在环境配置中安装或选择一个已激活环境。",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onOpenConfig, modifier = Modifier.padding(top = 12.dp)) {
                        Text("打开环境配置")
                    }
                }
            }
        } else {
            key(current.extensionId) {
                ActiveEnvironment(current)
            }
        }
    }
}

@Composable
private fun ActiveEnvironment(current: MountedSystemEnvironment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by current.contribution.runtime.state.collectAsState()
    val idlePolicy by current.contribution.runtime.idlePolicy.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.phase.name,
                color = if (state.phase == SystemEnvironmentRuntimePhase.FAILED) Color.Red else Color.LightGray
            )
            if (state.phase == SystemEnvironmentRuntimePhase.RUNNING) {
                Button(onClick = {
                    scope.launch { report(context) { current.contribution.runtime.stop() } }
                }) { Text("停止") }
            } else {
                Button(
                    enabled = state.phase == SystemEnvironmentRuntimePhase.STOPPED ||
                        state.phase == SystemEnvironmentRuntimePhase.FAILED,
                    onClick = {
                        scope.launch { report(context) { current.contribution.runtime.start() } }
                    }
                ) { Text("启动") }
            }
            OutlinedButton(onClick = {
                val next = nextIdlePolicy(idlePolicy)
                scope.launch { report(context) { current.contribution.runtime.setIdlePolicy(next) } }
            }) {
                Text(idlePolicy.timeoutMinutes?.let { "空闲 ${it} 分钟" } ?: "保持运行")
            }
            state.detail?.let {
                Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        AndroidView(
            factory = { current.contribution.display.createView(it) },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
private fun PresentationButton(
    host: InProcessPluginHost,
    mode: String,
    label: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            report(context) {
                host.invokeHostCapability(
                    "host.ui.presentation@1",
                    JSONObject()
                        .put("operation", "set_mode")
                        .put("screen_id", SystemEnvironmentContract.SCREEN_ID)
                        .put("mode", mode)
                        .toString()
                )
            }
        }
    }) { Text(label) }
}

@Composable
private fun EnvironmentConfigPage(
    mounted: List<MountedSystemEnvironment>,
    foregroundId: String?,
    sharedUi: InProcessSharedUiHost,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit
) {
    val installerId = InProcessSharedUiComponentIds.CHILD_EXTENSION_INSTALLER
    val listId = InProcessSharedUiComponentIds.CHILD_EXTENSION_LIST
    val installerParameters = remember {
        JSONObject()
            .put("label", "+ 添加系统环境")
            .put("parent_plugin_id", SystemEnvironmentContract.PARENT_PLUGIN_ID)
            .put("point", SystemEnvironmentContract.EXTENSION_POINT)
            .put("api", SystemEnvironmentContract.API_VERSION)
            .toString()
    }
    val listParameters = remember {
        JSONObject()
            .put("parent_plugin_id", SystemEnvironmentContract.PARENT_PLUGIN_ID)
            .put("point", SystemEnvironmentContract.EXTENSION_POINT)
            .put("api", SystemEnvironmentContract.API_VERSION)
            .toString()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Text(
                "环境配置",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }

        Text(
            "已激活系统环境",
            color = Color.White,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )
        if (mounted.isEmpty()) {
            Text("当前没有运行中的子系统插件。", color = Color.Gray)
        } else {
            mounted.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName)
                            Text(
                                "${item.extensionId} · ${item.version}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (foregroundId == item.extensionId) {
                            Text("正在显示", color = MaterialTheme.colorScheme.primary)
                        } else {
                            OutlinedButton(onClick = { onSelect(item.extensionId) }) {
                                Text("设为显示")
                            }
                        }
                    }
                }
            }
        }

        Text(
            "安装与生命周期",
            color = Color.White,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )
        if (sharedUi.supports(installerId)) {
            AndroidView(
                factory = { sharedUi.createComponent(installerId, installerParameters) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            )
        } else {
            Text("Plugin Center 未提供子插件安装组件。", color = Color.Red)
        }
        if (sharedUi.supports(listId)) {
            AndroidView(
                factory = { sharedUi.createComponent(listId, listParameters) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Text("Plugin Center 未提供子插件列表组件。", color = Color.Red)
        }
    }
}

private fun nextIdlePolicy(current: SystemEnvironmentIdlePolicy): SystemEnvironmentIdlePolicy {
    val next = when (current.mode) {
        SystemEnvironmentIdleMode.KEEP_RUNNING -> SystemEnvironmentIdleMode.MINUTES_10
        SystemEnvironmentIdleMode.MINUTES_10 -> SystemEnvironmentIdleMode.MINUTES_15
        SystemEnvironmentIdleMode.MINUTES_15 -> SystemEnvironmentIdleMode.MINUTES_30
        SystemEnvironmentIdleMode.MINUTES_30 -> SystemEnvironmentIdleMode.MINUTES_60
        SystemEnvironmentIdleMode.MINUTES_60,
        SystemEnvironmentIdleMode.CUSTOM -> SystemEnvironmentIdleMode.KEEP_RUNNING
    }
    return SystemEnvironmentIdlePolicy(mode = next, customMinutes = current.customMinutes)
}

private suspend fun report(context: Context, action: suspend () -> Unit) {
    runCatching { action() }.onFailure { error ->
        Toast.makeText(
            context,
            error.message ?: error.javaClass.simpleName,
            Toast.LENGTH_LONG
        ).show()
    }
}
