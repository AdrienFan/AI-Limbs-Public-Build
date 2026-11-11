package com.ai.limbs.plugins.permission

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessOverlayPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

internal const val PAIRING_OVERLAY_PROVIDER = "plugin.system.permission_service.pairing_overlay"
private const val PAIRING_OVERLAY_WINDOW = "plugin.system.permission_service.pairing_window"
internal val pairingOverlayMetadata = mapOf(
    "kind" to "plugin_page", "overlay_enabled" to "true", "host_collapsed_drag" to "true"
)

/** Plugin-owned presentation; permission, focus, drag and WindowManager belong to Host. */
internal class PermissionPairingOverlay(
    private val host: InProcessPluginUiHost,
    private val controller: PermissionPageController
) : InProcessOverlayPageProvider {
    val form = PairingFormState()
    private val expanded = MutableStateFlow(false)
    private val opening = MutableStateFlow(0L)

    override fun open() { opening.update { it + 1 } }

    private suspend fun invoke(operation: String, extra: JSONObject = JSONObject()): JSONObject =
        JSONObject(host.invokeHostCapability("host.window.overlay@1", extra
            .put("operation", operation).put("overlay_id", PAIRING_OVERLAY_WINDOW).toString()))

    private suspend fun setExpanded(value: Boolean) {
        invoke("update", JSONObject().put("expanded", value))
        expanded.value = value
    }

    suspend fun openDeveloperOptions(context: Context) {
        val result = invoke("create", JSONObject().put("provider_id", PAIRING_OVERLAY_PROVIDER))
        if (!result.getBoolean("created")) {
            check(result.optBoolean("permission_required", false)) { "配对浮窗未能创建" }
            // Host opens the system overlay-permission screen. Do not immediately replace it
            // with Developer Options, or the user cannot finish the required permission grant.
            Toast.makeText(context, "请允许 AI Limbs 显示在其他应用上层，返回后再次点击打开开发者选项。", Toast.LENGTH_LONG).show()
            return
        }
        try {
            setExpanded(true)
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            try { close() }
            catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    suspend fun close() {
        form.clearCode()
        invoke("remove")
        expanded.value = false
    }

    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { Content(context) } }
        }

    @Composable
    private fun Content(context: Context) {
        val isExpanded by expanded.collectAsState()
        val maxHeight = (LocalConfiguration.current.screenHeightDp - 160).coerceIn(180, 460).dp
        fun perform(action: suspend () -> Unit) {
            host.scope.launch(Dispatchers.Main) {
                try { action() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    host.logger.w("PermissionService", "Pairing overlay window action failed", error)
                    Toast.makeText(context, "浮窗操作失败：" + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
        }
        LaunchedEffect(Unit) {
            opening.collect { sequence ->
                if (sequence > 0) {
                    try { setExpanded(true) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        host.logger.w("PermissionService", "Expand pairing overlay failed", error)
                        Toast.makeText(context, "无法展开配对浮窗，请重新打开。", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        if (!isExpanded) {
            FilledTonalButton(onClick = { perform { setExpanded(true) } }, modifier = Modifier.size(56.dp),
                contentPadding = PaddingValues(0.dp)) { Text("配对") }
        } else Surface(shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("无线调试配对", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { perform { setExpanded(false) } }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = "收起配对浮窗")
                    }
                    IconButton(onClick = { perform { close() } }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭配对浮窗")
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    WirelessDebuggingForm(host, controller, form, compact = true)
                }
            }
        }
    }
}
