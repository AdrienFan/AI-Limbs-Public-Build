package com.ai.limbs.plugins.logcenter

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.launch

internal class LogCenterPageProvider(
    private val host: InProcessPluginHost
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    LogCenterPage(host)
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogCenterPage(host: InProcessPluginHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(host) { LogCenterClient(host) }
    var sources by remember { mutableStateOf<List<LogSource>>(emptyList()) }
    var selectedKey by remember { mutableStateOf("global") }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }
    var truncated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }

    fun show(message: String, long: Boolean = false) {
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun loadLog(key: String = selectedKey) {
        scope.launch {
            loading = true
            runCatching { client.read(key) }
                .onSuccess {
                    logContent = it.content
                    truncated = it.truncated
                }
                .onFailure { show(it.message ?: "日志读取失败", true) }
            loading = false
        }
    }

    fun reloadSources(showToast: Boolean = true) {
        scope.launch {
            loading = true
            runCatching { client.sources() }
                .onSuccess { loaded ->
                    sources = loaded
                    if (loaded.none { it.sourceKey == selectedKey }) selectedKey = "global"
                    val key = selectedKey
                    runCatching { client.read(key) }
                        .onSuccess {
                            logContent = it.content
                            truncated = it.truncated
                        }
                    if (showToast) show("日志来源已刷新")
                }
                .onFailure { show(it.message ?: "日志来源读取失败", true) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reloadSources(showToast = false) }

    val selected = sources.firstOrNull { it.sourceKey == selectedKey }
        ?: LogSource("global", "global", "global", "全局日志", null, null, true)
    val normalizedQuery = query.trim().lowercase()
    val filtered = remember(sources, normalizedQuery) {
        if (normalizedQuery.isBlank()) sources else sources.filter { normalizedQuery in it.searchText }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("日志中心", style = MaterialTheme.typography.headlineSmall)
        Text(
            "日志来源由 AI Limbs Host 动态提供；新增或卸载插件后刷新即可同步，不需要更新日志中心。",
            style = MaterialTheme.typography.bodySmall
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("日志范围", style = MaterialTheme.typography.titleMedium)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = sourceLabel(selected),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("当前来源") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text("搜索名称 / ID / 版本") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        logSourceGroups.forEach { group ->
                            val groupItems = filtered.filter { it.kind in group.kinds }
                            if (groupItems.isNotEmpty()) {
                                Text(
                                    group.title,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                groupItems.forEach { item ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(sourceLabel(item))
                                                Text(item.id, style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        onClick = {
                                            selectedKey = item.sourceKey
                                            query = ""
                                            expanded = false
                                            loadLog(item.sourceKey)
                                        }
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                        if (filtered.isEmpty()) {
                            Text("没有匹配的日志来源", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                Text(
                    "${sourceKindName(selected.kind)} · ${selected.id}" +
                        (selected.version?.let { " · v$it" } ?: "") +
                        if (selected.enabled) "" else " · 已停用",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { reloadSources() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("刷新来源")
                    }
                    Button(onClick = { loadLog() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("刷新日志")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("日志预览", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = if (logContent.isBlank()) "当前来源暂无日志" else logContent,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 12,
                    maxLines = 24,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 520.dp)
                )
                if (truncated) {
                    Text("预览只显示最后一部分日志；导出文件仍包含当前来源的完整日志。", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !loading,
                        onClick = {
                            scope.launch {
                                loading = true
                                runCatching { client.export(selectedKey) }
                                    .onSuccess { show("已保存到 $it", true) }
                                    .onFailure { show(it.message ?: "日志导出失败", true) }
                                loading = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                        Text("导出当前日志")
                    }
                    TextButton(onClick = { clearConfirm = true }, enabled = !loading) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text("清除全局日志")
                    }
                }
                Text(
                    "清除操作始终清空全局日志文件；第一版不单独删除某个插件的历史记录。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("清除全部日志？") },
            text = { Text("这会清空当前 AI Limbs 全局日志，无法撤销。") },
            confirmButton = {
                Button(onClick = {
                    clearConfirm = false
                    scope.launch {
                        loading = true
                        runCatching { client.clearGlobal() }
                            .onSuccess {
                                logContent = ""
                                truncated = false
                                show("全局日志已清除")
                            }
                            .onFailure { show(it.message ?: "清除失败", true) }
                        loading = false
                    }
                }) { Text("确认清除") }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } }
        )
    }
}

private data class LogSourceGroup(val title: String, val kinds: Set<String>)

private val logSourceGroups = listOf(
    LogSourceGroup("全局 / 基座", setOf("global", "host")),
    LogSourceGroup("父插件", setOf("plugin")),
    LogSourceGroup("子插件", setOf("extension"))
)

private fun sourceLabel(source: LogSource): String = buildString {
    append(source.displayName)
    source.version?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
}

private fun sourceKindName(kind: String): String = when (kind) {
    "global" -> "全局"
    "host" -> "基座"
    "plugin" -> "父插件"
    "extension" -> "子插件"
    else -> kind
}
