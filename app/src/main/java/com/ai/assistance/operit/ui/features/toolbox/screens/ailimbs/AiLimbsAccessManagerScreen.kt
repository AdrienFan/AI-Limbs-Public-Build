package com.ai.assistance.operit.ui.features.toolbox.screens.ailimbs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var importTarget by remember { mutableStateOf<AiLimbsDocumentId?>(null) }

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

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = importTarget
            importTarget = null
            if (uri != null && target != null) {
                scope.launch {
                    runCatching {
                        val importedText =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)
                                    ?.bufferedReader(Charsets.UTF_8)
                                    ?.use { it.readText() }
                                    ?.removePrefix("\uFEFF")
                                    ?: error("Unable to read selected text file")
                            }
                        when (target) {
                            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT -> importedText
                            AiLimbsDocumentId.WORK_MANUAL ->
                                documents.normalizeWorkManualImport(importedText)
                            else -> error("This document does not support file import")
                        }
                    }.onSuccess { importedText ->
                        when (target) {
                            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT ->
                                customAccessPrompt = importedText
                            AiLimbsDocumentId.WORK_MANUAL ->
                                workManual = importedText
                            else -> Unit
                        }
                        toast(R.string.laner_document_imported)
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.laner_document_import_failed,
                                error.message ?: "Unknown error"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    fun importDocument(documentId: AiLimbsDocumentId) {
        importTarget = documentId
        importLauncher.launch(arrayOf("text/*", "application/octet-stream"))
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
                            error("AI Limbs system access prompt is immutable code")
                        AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT ->
                            documents.writeCustomAccessPrompt(content)
                        AiLimbsDocumentId.WORK_MANUAL ->
                            documents.writeWorkManual(content)
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
