package com.ai.limbs.plugins.permission

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Both surfaces use the same controller and transient form; pairing has one implementation. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WirelessDebuggingForm(
    host: InProcessPluginUiHost,
    controller: PermissionPageController,
    form: PairingFormState,
    compact: Boolean = false
) {
    val draft by form.draft.collectAsState()
    val service by controller.state.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val busy = draft.working || service.busy

    fun perform(label: String, action: suspend () -> Unit) {
        // Host runtime scope survives navigation to Settings and closing the overlay.
        focus.clearFocus()
        keyboard?.hide()
        host.scope.launch(Dispatchers.Main) {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // Pairing secrets must not enter new presentation logs or Toasts.
                host.logger.w("PermissionService", "$label failed (${error.javaClass.simpleName})")
                if (!form.draft.value.failed) {
                    Toast.makeText(context, "$label 未完成，请检查输入或服务状态", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = draft.step == PairingStep.PAIR, enabled = !busy,
                onClick = { form.step(PairingStep.PAIR) }, label = { Text("1 配对") })
            FilterChip(selected = draft.step == PairingStep.CONNECT, enabled = !busy,
                onClick = { form.step(PairingStep.CONNECT) }, label = { Text("2 启动") })
        }
        if (!compact || draft.step == PairingStep.PAIR) {
            Text("在系统无线调试中点“使用配对码配对设备”，保持该窗口打开。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = draft.pairPort, onValueChange = form::pairPort,
                label = { Text("配对端口") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.pairCode, onValueChange = form::pairCode,
                label = { Text("六位配对码") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && draft.canPair,
                onClick = { perform("配对") { form.pair(controller::pair) } }) { Text("配对") }
        }
        if (!compact) HorizontalDivider()
        if (!compact || draft.step == PairingStep.CONNECT) {
            Text("使用无线调试主页上的连接端口；它与配对端口不同，重启后可能变化。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = draft.connectPort, onValueChange = form::connectPort,
                label = { Text("连接端口") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && !service.running && draft.canStart,
                onClick = { perform("启动权限服务") { form.start(controller::startAdb) } }) { Text("启动权限服务") }
            if (service.running) Text("权限服务已连接", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (draft.feedback.isNotBlank()) Text(draft.feedback,
            color = if (draft.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
    }
}
