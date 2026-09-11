package com.ai.limbs.plugins.laneraccess

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

internal class LanerAccessManagerPageProvider(
    private val host: InProcessPluginHost
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    LanerAccessManagerPage(host)
                }
            }
        }
}

private data class RestoreRequest(
    val kind: ManagedDocumentKind,
    val snapshotId: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanerAccessManagerPage(host: InProcessPluginHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(host) { ManagedDocumentClient(host) }

    var customPrompt by remember { mutableStateOf("") }
    var workManual by remember { mutableStateOf("") }
    var customSnapshots by remember { mutableStateOf<List<ManagedDocumentSnapshot>>(emptyList()) }
    var workSnapshots by remember { mutableStateOf<List<ManagedDocumentSnapshot>>(emptyList()) }
    var customSelected by remember { mutableStateOf<String?>(null) }
    var workSelected by remember { mutableStateOf<String?>(null) }
    var restoreRequest by remember { mutableStateOf<RestoreRequest?>(null) }

    fun show(message: String, long: Boolean = false) {
        Toast.makeText(
            context,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    suspend fun loadDocument(kind: ManagedDocumentKind) {
        val state = client.load(kind)
        when (kind) {
            ManagedDocumentKind.CUSTOM_ACCESS_PROMPT -> {
                customPrompt = state.content
                customSnapshots = state.snapshots
                customSelected = state.snapshots.firstOrNull()?.id
            }
            ManagedDocumentKind.WORK_MANUAL -> {
                workManual = state.content
                workSnapshots = state.snapshots
                workSelected = state.snapshots.firstOrNull()?.id
            }
        }
    }

    suspend fun loadAll() {
        loadDocument(ManagedDocumentKind.CUSTOM_ACCESS_PROMPT)
        loadDocument(ManagedDocumentKind.WORK_MANUAL)
    }

    fun reload() {
        scope.launch {
            runCatching { loadAll() }
                .onSuccess { show("已重新加载") }
                .onFailure { show(it.message ?: "读取失败", long = true) }
        }
    }

    fun save(kind: ManagedDocumentKind, content: String) {
        scope.launch {
            runCatching {
                val changed = client.write(kind, content)
                loadDocument(kind)
                changed
            }.onSuccess { changed ->
                show(if (changed) "已保存" else "内容未变化")
            }.onFailure {
                show(it.message ?: "保存失败", long = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { loadAll() }
            .onFailure { show(it.message ?: "读取失败", long = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DocumentEditorCard(
            title = ManagedDocumentKind.CUSTOM_ACCESS_PROMPT.title,
            value = customPrompt,
            onValueChange = { customPrompt = it },
            minLines = ManagedDocumentKind.CUSTOM_ACCESS_PROMPT.minLines,
            snapshots = customSnapshots,
            selectedSnapshotId = customSelected,
            onSnapshotSelected = { customSelected = it },
            onRestore = {
                restoreRequest = RestoreRequest(
                    ManagedDocumentKind.CUSTOM_ACCESS_PROMPT,
                    it
                )
            },
            onReload = ::reload,
            onSave = { save(ManagedDocumentKind.CUSTOM_ACCESS_PROMPT, customPrompt) }
        )
        DocumentEditorCard(
            title = ManagedDocumentKind.WORK_MANUAL.title,
            value = workManual,
            onValueChange = { workManual = it },
            minLines = ManagedDocumentKind.WORK_MANUAL.minLines,
            snapshots = workSnapshots,
            selectedSnapshotId = workSelected,
            onSnapshotSelected = { workSelected = it },
            onRestore = {
                restoreRequest = RestoreRequest(
                    ManagedDocumentKind.WORK_MANUAL,
                    it
                )
            },
            onReload = ::reload,
            onSave = { save(ManagedDocumentKind.WORK_MANUAL, workManual) }
        )
    }

    restoreRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { restoreRequest = null },
            title = { Text("确认恢复") },
            text = { Text("确定恢复 ${request.kind.title} 的这个历史版本吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreRequest = null
                        scope.launch {
                            runCatching {
                                client.restore(request.kind, request.snapshotId)
                                loadDocument(request.kind)
                            }.onSuccess {
                                show("历史版本已恢复")
                            }.onFailure {
                                show(it.message ?: "恢复失败", long = true)
                            }
                        }
                    }
                ) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreRequest = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentEditorCard(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int,
    snapshots: List<ManagedDocumentSnapshot>,
    selectedSnapshotId: String?,
    onSnapshotSelected: (String) -> Unit,
    onRestore: (String) -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = (minLines * 24).dp),
                minLines = minLines
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReload) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("重新加载")
                }
                Button(onClick = onSave) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("保存")
                }
            }
            BackupSelector(
                snapshots = snapshots,
                selectedSnapshotId = selectedSnapshotId,
                onSnapshotSelected = onSnapshotSelected,
                onRestore = onRestore
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSelector(
    snapshots: List<ManagedDocumentSnapshot>,
    selectedSnapshotId: String?,
    onSnapshotSelected: (String) -> Unit,
    onRestore: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedSnapshot = snapshots.firstOrNull { it.id == selectedSnapshotId }
    val selectedLabel = selectedSnapshot?.let(::formatSnapshot) ?: "暂无历史版本"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (snapshots.isNotEmpty()) expanded = !expanded
        }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = snapshots.isNotEmpty(),
            label = { Text("历史版本") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            snapshots.forEach { snapshot ->
                DropdownMenuItem(
                    text = { Text(formatSnapshot(snapshot)) },
                    onClick = {
                        onSnapshotSelected(snapshot.id)
                        expanded = false
                    }
                )
            }
        }
    }
    Button(
        onClick = { selectedSnapshot?.let { onRestore(it.id) } },
        enabled = selectedSnapshot != null
    ) {
        Text("恢复")
    }
}

private fun formatSnapshot(snapshot: ManagedDocumentSnapshot): String {
    val timestamp = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date(snapshot.createdAtEpochMillis))
    return "$timestamp · ${snapshot.sha256.take(8)}"
}
