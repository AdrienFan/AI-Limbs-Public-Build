package com.ai.assistance.operit.ui.features.toolbox.screens.pluginbootstrap

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.plugins.system.SystemPluginPackageValidator
import com.ai.assistance.operit.plugins.system.SystemPluginProtocolException
import com.ai.assistance.operit.plugins.system.SystemPluginProtocolV1
import com.ai.assistance.operit.plugins.system.SystemPluginValidationResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_BOOTSTRAP_COPY_BYTES = 512L * 1024L * 1024L

@Composable
fun PluginBootstrapScreen(onInstalled: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<SystemPluginValidationResult?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        selectedUri = uri
        result = null
        errorCode = null
        errorMessage = null
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
                    ?: throw SystemPluginProtocolException(
                        "SOURCE_NAME_UNAVAILABLE",
                        "无法确认文件名；Bootstrap 必须先验证 .ailpsys 扩展名"
                    )
                selectedName = name
                val validation = withContext(Dispatchers.IO) {
                    val candidate = copyCandidateToCache(context, uri)
                    try {
                        SystemPluginPackageValidator.validateForPluginCenterBootstrap(candidate, name)
                    } finally {
                        candidate.delete()
                    }
                }
                result = validation
            } catch (error: SystemPluginProtocolException) {
                errorCode = error.code
                errorMessage = error.message
            } catch (error: Throwable) {
                errorCode = "BOOTSTRAP_VALIDATION_FAILED"
                errorMessage = error.message ?: error::class.java.simpleName
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Plugin Center Bootstrap", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "这里是 AI Limbs 基座永久保留的系统插件入口。只有通过角色、Host ABI、SHA-256 完整性和 Ed25519 可信发布者验签的 Plugin Center 才能安装。",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("允许的系统角色", fontWeight = FontWeight.Bold)
                Text("system.role = ${SystemPluginProtocolV1.ROLE_PLUGIN_CENTER}")
                Text("包格式 = ${SystemPluginProtocolV1.FORMAT}")
                Text("Host ABI = ${SystemPluginProtocolV1.HOST_ABI}")
                Text("扩展名 = ${SystemPluginProtocolV1.PACKAGE_EXTENSION}")
            }
        }

        Button(
            enabled = !busy,
            onClick = { picker.launch(arrayOf("*/*")) }
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" 选择并验证 .ailpsys")
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("正在验证系统插件协议…")
            }
        }

        selectedName?.let { Text("候选文件：$it", style = MaterialTheme.typography.bodySmall) }

        result?.let { validation ->
            val manifest = validation.manifest
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("协议验证通过", fontWeight = FontWeight.Bold)
                    Text("${manifest.display.name} · ${manifest.version}")
                    Text("ID：${manifest.pluginId}")
                    Text("Role：${manifest.role}")
                    Text("Runtime：${manifest.runtime.kind} · ${manifest.runtime.entry}")
                    manifest.runtime.entryClass?.let { Text("Entry Class：$it", style = MaterialTheme.typography.bodySmall) }
                    Text("Host ABI：${manifest.hostAbi.min}..${manifest.hostAbi.max}")
                    Text("Signer：${manifest.signature.signerId} · ${manifest.signature.algorithm}")
                    Text("可信状态：${validation.trustStatus}")
                    Text("完整性条目：${validation.verifiedPayloadEntries}")
                    Text("Package SHA-256：${validation.packageSha256}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "可信验证已完成：manifest 原始字节已通过 Ed25519 验签，signer_id 已进入受信任 keyring，并且该 signer 被允许承担 plugin_center 角色。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        enabled = !busy && selectedUri != null,
                        onClick = {
                            val uri = selectedUri ?: return@Button
                            val name = selectedName ?: return@Button
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        PluginPlatformKernel.systemPlugins.installFirstTrustedFromUri(uri.toString(), name)
                                    }
                                    onInstalled()
                                } catch (error: SystemPluginProtocolException) {
                                    errorCode = error.code
                                    errorMessage = error.message
                                } catch (error: Throwable) {
                                    errorCode = "BOOTSTRAP_INSTALL_FAILED"
                                    errorMessage = error.message ?: error::class.java.simpleName
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text("安装 Plugin Center") }
                }
            }
        }

        if (errorCode != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("协议验证失败", fontWeight = FontWeight.Bold)
                    Text(errorCode.orEmpty())
                    Text(errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)?.trim()?.takeIf { it.isNotBlank() }
        }
    }
    return null
}

private fun copyCandidateToCache(context: Context, uri: Uri): File {
    val dir = File(context.cacheDir, "system-plugin-bootstrap").apply { mkdirs() }
    val target = File(dir, "candidate-${System.nanoTime()}${SystemPluginProtocolV1.PACKAGE_EXTENSION}")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BOOTSTRAP_COPY_BYTES) {
                    throw SystemPluginProtocolException("PACKAGE_TOO_LARGE", "System plugin archive exceeds 512 MiB")
                }
                output.write(buffer, 0, read)
            }
        }
    } ?: throw SystemPluginProtocolException("SOURCE_OPEN_FAILED", "无法读取所选文件")
    return target
}
