package com.ai.limbs.plugins.artstudio

import android.view.Menu
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.limbs.plugin.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun appendStudioExtensionMenu(menu: Menu, bridge: StudioMenuBridge) {
    val sub = menu.addSubMenu(9000, 200000, 2000, "扩展")
    sub.item.isEnabled = true
    fun populate() {
        sub.clear()
        sub.add(9001, 200001, 0, "添加扩展").setOnMenuItemClickListener {
            bridge.onAddExtension?.invoke()
            true
        }
        for ((index, row) in bridge.extensionRows.withIndex()) {
            sub.add(9002, 200002 + index, index + 1, row.item.title).apply {
                isEnabled = row.item.enabled && !bridge.busy && !bridge.extensionBusy
                setOnMenuItemClickListener { bridge.onExtensionAction?.invoke(row); true }
            }
        }
        sub.setGroupDividerEnabled(true)
    }
    bridge.refreshExtensionMenu = ::populate
    populate()
}

@Composable internal fun StudioExtensionsUi(host: InProcessPluginUiHost,
    sharedUi: InProcessSharedUiHost, bridge: StudioMenuBridge) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    LaunchedEffect(host) {
        try {
            host.providers.observe(ART_EXTENSION_MENUS).collectLatest { binding ->
                bridge.extensionRows = emptyList()
                bridge.refreshExtensionMenu?.invoke()
                if (binding == null) return@collectLatest
                require(binding.ownerPluginId == ART_ID) { "画室扩展目录所有者不匹配" }
                val provider = binding.payload as? InProcessUiStateProvider
                    ?: error("画室扩展目录类型错误")
                provider.stateJson.collect { raw ->
                    bridge.extensionRows = ArtExtensionMenuSchema.rows(requireNotNull(raw))
                    bridge.refreshExtensionMenu?.invoke()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            bridge.extensionRows = emptyList(); bridge.refreshExtensionMenu?.invoke()
            host.logger.e("ArtStudio", "Extension menu directory failed", error)
            Toast.makeText(context, "读取扩展菜单失败：${error.message}", Toast.LENGTH_LONG).show()
        }
    }
    SideEffect {
        bridge.onAddExtension = { dialog = true }
        bridge.onExtensionAction = action@{ row ->
            if (bridge.busy || actionBusy) return@action
            actionBusy = true; bridge.extensionBusy = true; bridge.refreshExtensionMenu?.invoke()
            scope.launch {
                try {
                    val binding = requireNotNull(host.providers.resolve(ART_EXTENSION_MENUS)) { "画室扩展目录未就绪" }
                    require(binding.ownerPluginId == ART_ID) { "画室扩展目录所有者不匹配" }
                    val provider = binding.payload as? InProcessUiStateProvider ?: error("画室扩展目录类型错误")
                    provider.perform("activate", row.request())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    host.logger.e("ArtStudio", "Extension menu action failed", error)
                    Toast.makeText(context, "扩展操作失败：${error.message}", Toast.LENGTH_LONG).show()
                } finally {
                    actionBusy = false; bridge.extensionBusy = false; bridge.refreshExtensionMenu?.invoke()
                }
            }
        }
    }
    DisposableEffect(bridge) {
        onDispose {
            bridge.onAddExtension = null; bridge.onExtensionAction = null
            bridge.extensionBusy = false
            bridge.extensionRows = emptyList(); bridge.refreshExtensionMenu = null
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("添加扩展") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择画室子插件包（.ailx），校验通过后添加到扩展菜单。")
                val installer = InProcessSharedUiComponentIds.CHILD_EXTENSION_INSTALLER
                if (sharedUi.supports(installer)) AndroidView(factory = {
                    sharedUi.createComponent(installer, JSONObject().put("label", "选择扩展包")
                        .put("parent_plugin_id", ART_ID).put("point", ART_EXTENSION_POINT)
                        .put("api", ART_EXTENSION_API).toString())
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
                else Text("插件中心未提供子插件安装组件。", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { dialog = false }) { Text("关闭") } })
}
