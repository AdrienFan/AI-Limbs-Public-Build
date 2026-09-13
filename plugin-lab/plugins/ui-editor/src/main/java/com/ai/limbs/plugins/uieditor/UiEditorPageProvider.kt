package com.ai.limbs.plugins.uieditor

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.launch

internal class UiEditorPageProvider(
    private val host: InProcessPluginHost
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    UiEditorPage(host)
                }
            }
        }
}

@Composable
private fun UiEditorPage(host: InProcessPluginHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(host) { UiEditorClient(host) }
    var status by remember { mutableStateOf<UiLayoutStatus?>(null) }
    var loading by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }

    fun show(message: String, long: Boolean = false) {
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { client.status() }
                .onSuccess { status = it }
                .onFailure { show(it.message ?: "UI 编辑状态读取失败", true) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("UI 编辑器", style = MaterialTheme.typography.headlineSmall)
        Text(
            "v0.1.0 先开放工具箱页面布局编辑；后续页面可以继续接入同一套 Host UI Editing 合同。",
            style = MaterialTheme.typography.bodySmall
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DashboardCustomize, contentDescription = null)
                    Text("工具箱页面", style = MaterialTheme.typography.titleMedium)
                }
                Text("开启后会自动返回真实工具箱。长按插件卡片并拖动即可调整顺序。")
                Text(
                    if (status?.active == true) {
                        "当前：正在编辑 ${status?.surface ?: "未知页面"} / ${status?.mode ?: "未知模式"}"
                    } else {
                        "当前：未进入编辑模式 · 已保存 ${status?.toolboxOrderCount ?: 0} 个卡片位置"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            runCatching { client.startToolboxLayout() }
                                .onSuccess { status = it }
                                .onFailure { show(it.message ?: "无法开启工具箱布局编辑", true) }
                            loading = false
                        }
                    }
                ) {
                    Icon(Icons.Default.DashboardCustomize, contentDescription = null)
                    Text("开启工具箱布局编辑")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status?.active == true) {
                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                scope.launch {
                                    loading = true
                                    runCatching { client.finish() }
                                        .onSuccess { status = it; show("编辑模式已结束") }
                                        .onFailure { show(it.message ?: "结束编辑模式失败", true) }
                                    loading = false
                                }
                            }
                        ) { Text("结束编辑模式") }
                    }
                    TextButton(enabled = !loading, onClick = { resetConfirm = true }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Text("恢复默认布局")
                    }
                }
            }
        }
    }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("恢复默认布局") },
            text = { Text("将清除工具箱自定义排序，并恢复系统当前默认顺序。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetConfirm = false
                        scope.launch {
                            loading = true
                            runCatching { client.resetToolbox() }
                                .onSuccess { status = it; show("工具箱布局已恢复默认") }
                                .onFailure { show(it.message ?: "恢复默认布局失败", true) }
                            loading = false
                        }
                    }
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text("取消") }
            }
        )
    }
}
