package com.ai.assistance.operit.ui.features.toolbox.screens.ailimbs

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDocumentId
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDocumentProvider
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDocumentSnapshot
import com.ai.assistance.operit.ui.components.CustomScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private data class RestoreRequest(
    val documentId: AiLimbsDocumentId,
    val snapshotId: String
)

private val visibleDocumentIds =
    listOf(
        AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
        AiLimbsDocumentId.WORK_MANUAL
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiLimbsAccessManagerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documents = remember { AiLimbsDocumentProvider(context) }
    var customAccessPrompt by remember { mutableStateOf("") }
    var workManual by remember { mutableStateOf("") }
    var snapshots by remember {
        mutableStateOf<Map<AiLimbsDocumentId, List<AiLimbsDocumentSnapshot>>>(emptyMap())
    }
    var selectedSnapshots by remember {
        mutableStateOf<Map<AiLimbsDocumentId, String>>(emptyMap())
    }
    var restoreRequest by remember { mutableStateOf<RestoreRequest?>(null) }

    fun toast(resId: Int) =
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()

    suspend fun loadDocuments() {
        customAccessPrompt = documents.readCustomAccessPrompt()
        workManual = documents.readWorkManual()
        val loadedSnapshots =
            visibleDocumentIds.associateWith { documentId ->
                documents.listSnapshots(documentId)
            }
        snapshots = loadedSnapshots
        selectedSnapshots =
            loadedSnapshots.mapNotNull { (documentId, versions) ->
                versions.firstOrNull()?.let { documentId to it.id }
            }.toMap()
    }

    fun reload(showConfirmation: Boolean) {
        scope.launch {
            runCatching { loadDocuments() }
                .onSuccess {
                    if (showConfirmation) toast(R.string.laner_access_reloaded)
                }
                .onFailure {
                    Toast.makeText(context, it.message ?: "Read failed", Toast.LENGTH_LONG).show()
                }
        }
    }

    fun saveDocument(
        documentId: AiLimbsDocumentId,
        content: String,
        savedMessage: Int
    ) {
        scope.launch {
            try {
                val changed =
                    when (documentId) {
                        AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT ->
                            documents.writeSystemAccessPrompt(content)
                        AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT ->
                            documents.writeCustomAccessPrompt(content)
                        AiLimbsDocumentId.WORK_MANUAL ->
                            documents.writeWorkManual(content)
                        AiLimbsDocumentId.TOOL_MANUAL ->
                            documents.writeToolManual(content)
                    }
                toast(if (changed) savedMessage else R.string.laner_document_unchanged)
                val versions = documents.listSnapshots(documentId)
                snapshots = snapshots + (documentId to versions)
                selectedSnapshots =
                    if (versions.isEmpty()) {
                        selectedSnapshots - documentId
                    } else {
                        selectedSnapshots + (documentId to versions.first().id)
                    }
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    error.message ?: "Save failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { loadDocuments() }
            .onFailure {
                Toast.makeText(context, it.message ?: "Read failed", Toast.LENGTH_LONG).show()
            }
    }

    CustomScaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DocumentEditorCard(
                title = stringResource(R.string.laner_access_prompt_title),
                value = customAccessPrompt,
                onValueChange = { customAccessPrompt = it },
                minLines = 5,
                snapshots = snapshots[AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT].orEmpty(),
                selectedSnapshotId = selectedSnapshots[AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT],
                onSnapshotSelected = { snapshotId ->
                    selectedSnapshots =
                        selectedSnapshots + (AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT to snapshotId)
                },
                onRestore = { snapshotId ->
                    restoreRequest =
                        RestoreRequest(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT, snapshotId)
                },
                onReload = { reload(showConfirmation = true) },
                onSave = {
                    saveDocument(
                        AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT,
                        customAccessPrompt,
                        R.string.laner_access_saved
                    )
                }
            )
            DocumentEditorCard(
                title = stringResource(R.string.laner_work_manual_title),
                value = workManual,
                onValueChange = { workManual = it },
                minLines = 14,
                snapshots = snapshots[AiLimbsDocumentId.WORK_MANUAL].orEmpty(),
                selectedSnapshotId = selectedSnapshots[AiLimbsDocumentId.WORK_MANUAL],
                onSnapshotSelected = { snapshotId ->
                    selectedSnapshots =
                        selectedSnapshots + (AiLimbsDocumentId.WORK_MANUAL to snapshotId)
                },
                onRestore = { snapshotId ->
                    restoreRequest = RestoreRequest(AiLimbsDocumentId.WORK_MANUAL, snapshotId)
                },
                onReload = { reload(showConfirmation = true) },
                onSave = {
                    saveDocument(
                        AiLimbsDocumentId.WORK_MANUAL,
                        workManual,
                        R.string.laner_work_manual_saved
                    )
                }
            )
        }
    }

    restoreRequest?.let { request ->
        val documentTitle =
            stringResource(
                when (request.documentId) {
                    AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT -> R.string.laner_system_access_prompt_title
                    AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT -> R.string.laner_access_prompt_title
                    AiLimbsDocumentId.WORK_MANUAL -> R.string.laner_work_manual_title
                    AiLimbsDocumentId.TOOL_MANUAL -> R.string.laner_tool_manual_title
                }
            )
        AlertDialog(
            onDismissRequest = { restoreRequest = null },
            title = { Text(stringResource(R.string.laner_restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.laner_restore_confirm_message, documentTitle))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreRequest = null
                        scope.launch {
                            try {
                                documents.restoreSnapshot(
                                    request.documentId,
                                    request.snapshotId
                                )
                                loadDocuments()
                                toast(R.string.laner_document_restored)
                            } catch (error: Exception) {
                                Toast.makeText(
                                    context,
                                    error.message ?: "Restore failed",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.laner_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreRequest = null }) {
                    Text(stringResource(R.string.laner_cancel))
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
    snapshots: List<AiLimbsDocumentSnapshot>,
    selectedSnapshotId: String?,
    onSnapshotSelected: (String) -> Unit,
    onRestore: (String) -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
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
                modifier = Modifier.fillMaxWidth().heightIn(min = (minLines * 24).dp),
                minLines = minLines
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReload) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.laner_reload))
                }
                Button(onClick = onSave) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text(stringResource(R.string.laner_save))
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
    snapshots: List<AiLimbsDocumentSnapshot>,
    selectedSnapshotId: String?,
    onSnapshotSelected: (String) -> Unit,
    onRestore: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedSnapshot = snapshots.firstOrNull { it.id == selectedSnapshotId }
    val selectedLabel =
        selectedSnapshot?.let(::formatSnapshot)
            ?: stringResource(R.string.laner_document_history_empty)

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
            label = { Text(stringResource(R.string.laner_document_history)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor()
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
        Text(stringResource(R.string.laner_restore))
    }
}

private fun formatSnapshot(snapshot: AiLimbsDocumentSnapshot): String {
    val timestamp =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(snapshot.createdAtEpochMillis))
    return "$timestamp · ${snapshot.sha256.take(8)}"
}
