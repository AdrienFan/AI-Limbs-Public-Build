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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDocumentProvider
import com.ai.assistance.operit.ui.components.CustomScaffold
import kotlinx.coroutines.launch

@Composable
fun AiLimbsAccessManagerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documents = remember { AiLimbsDocumentProvider(context) }
    var accessPrompt by remember { mutableStateOf("") }
    var workManual by remember { mutableStateOf("") }

    fun toast(resId: Int) = Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()

    fun reload() {
        scope.launch {
            runCatching {
                accessPrompt = documents.readAccessPrompt()
                workManual = documents.readWorkManual()
            }.onSuccess { toast(R.string.laner_access_reloaded) }
                .onFailure { Toast.makeText(context, it.message ?: "Read failed", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            accessPrompt = documents.readAccessPrompt()
            workManual = documents.readWorkManual()
        }
    }

    CustomScaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DocumentEditorCard(
                title = stringResource(R.string.laner_access_prompt_title),
                path = AiLimbsDocumentProvider.ACCESS_PROMPT_PATH,
                value = accessPrompt,
                onValueChange = { accessPrompt = it },
                minLines = 5,
                onReload = { reload() },
                onSave = {
                    scope.launch {
                        runCatching { documents.writeAccessPrompt(accessPrompt) }
                            .onSuccess { toast(R.string.laner_access_saved) }
                            .onFailure { Toast.makeText(context, it.message ?: "Save failed", Toast.LENGTH_LONG).show() }
                    }
                }
            )
            DocumentEditorCard(
                title = stringResource(R.string.laner_work_manual_title),
                path = AiLimbsDocumentProvider.WORK_MANUAL_PATH,
                value = workManual,
                onValueChange = { workManual = it },
                minLines = 14,
                onReload = { reload() },
                onSave = {
                    scope.launch {
                        runCatching { documents.writeWorkManual(workManual) }
                            .onSuccess { toast(R.string.laner_work_manual_saved) }
                            .onFailure { Toast.makeText(context, it.message ?: "Save failed", Toast.LENGTH_LONG).show() }
                    }
                }
            )
        }
    }
}

@Composable
private fun DocumentEditorCard(
    title: String,
    path: String,
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int,
    onReload: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
    }
}
