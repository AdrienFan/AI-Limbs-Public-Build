package com.ai.assistance.operit.ui.features.toolbox.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.plugins.system.SystemPluginUiActionsV2
import com.ai.assistance.operit.plugins.system.SystemPluginUiSurfaceV2
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun DynamicNavigationScreen(
    surfaceId: String,
    focusKind: String? = null,
    focusId: String? = null,
    onOpenPluginScreen: (String) -> Unit
) {
    val surfaces by PluginPlatformKernel.dynamicNavigationRegistry.surfaces.collectAsState()
    val bindings by PluginPlatformKernel.dynamicNavigationRegistry.bindings.collectAsState()
    val homeTiles by PluginPlatformKernel.uiRegistry.homeTiles.collectAsState()
    val surface = surfaces.firstOrNull { it.id == surfaceId }
    val boundTiles = bindings.filter { it.surfaceId == surfaceId }.mapNotNull { binding ->
        homeTiles.firstOrNull { it.id == binding.tileId && it.ownerPluginId == binding.ownerPluginId }
    }
    val listState = rememberLazyListState()
    val focusedPluginId = focusId?.takeIf { focusKind == "plugin" }
    var showFocusHighlight by remember(surfaceId) { mutableStateOf(false) }
    LaunchedEffect(focusedPluginId, boundTiles) {
        val targetIndex = focusedPluginId?.let { id ->
            boundTiles.indexOfFirst { it.ownerPluginId == id }.takeIf { it >= 0 }
        }
        if (targetIndex != null) {
            listState.animateScrollToItem(targetIndex)
            showFocusHighlight = true
            delay(2400)
            showFocusHighlight = false
        } else {
            showFocusHighlight = false
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            text = surface?.title ?: "动态页面",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        if (boundTiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这是一个空白页面，可在 Plugin Center 中向这里添加插件或应用。")
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                items(boundTiles, key = { it.id }) { tile ->
                    val isFocusedPlugin = tile.ownerPluginId == focusedPluginId
                    val focusShape = RoundedCornerShape(12.dp)
                    val renderedRegion = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 2.dp,
                            color = if (isFocusedPlugin && showFocusHighlight) Color.Red else Color.Transparent,
                            shape = focusShape
                        )
                    Card(
                        onClick = { onOpenPluginScreen(tile.screenId) },
                        modifier = renderedRegion,
                        shape = focusShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, contentDescription = null)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(tile.title, fontWeight = FontWeight.SemiBold)
                                tile.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stable Host shell for an ordinary plugin screen.
 *
 * Notice that this function contains no component switch and no widget-specific code.  Its only
 * job is to resolve the registered screen, attach trusted owner metadata, and hand the opaque UI
 * document to the renderer currently supplied by Plugin Center.  This is the architectural seam
 * that keeps future composite controls out of Stable Kernel.
 */
@Composable
fun PluginDeclarativeScreen(
    screenId: String,
    focusKind: String? = null,
    focusId: String? = null
) {
    val screen = PluginPlatformKernel.uiRegistry.screen(screenId)
    val renderer by PluginPlatformKernel.systemUiRegistry.pluginSurfaceRenderer.collectAsState()
    if (screen == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("插件页面当前不可用")
        }
        return
    }
    val activeRenderer = renderer
    if (activeRenderer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Plugin Center UI Runtime 当前不可用")
        }
        return
    }

    val routedDocumentJson = remember(screen.documentJson, focusKind, focusId) {
        runCatching {
            JSONObject(screen.documentJson).apply {
                remove("_host_focus")
                val normalizedKind = focusKind?.trim()?.lowercase()
                val normalizedId = focusId?.trim()?.takeIf { it.isNotEmpty() }
                if (normalizedKind in setOf("plugin", "child") && normalizedId != null) {
                    put(
                        "_host_focus",
                        JSONObject().put("kind", normalizedKind).put("id", normalizedId)
                    )
                }
            }.toString()
        }.getOrDefault(screen.documentJson)
    }

    activeRenderer.Render(
        SystemPluginUiSurfaceV2(
            ownerPluginId = screen.ownerPluginId,
            screenId = screen.id,
            title = screen.title,
            description = screen.description,
            schemaId = screen.schemaId,
            documentJson = routedDocumentJson,
            actions = SystemPluginUiActionsV2 { capabilityId, parameters ->
                // Bind capability execution to the Host-trusted screen owner. Plugin Center receives
                // no arbitrary plugin-id parameter, so a component cannot impersonate another plugin.
                PluginPlatformKernel.capabilities.requireOwnedCapability(
                    screen.ownerPluginId,
                    capabilityId
                )
                val authorization = PluginPlatformKernel.manager.activeAuthorization(screen.ownerPluginId)
                PluginPlatformKernel.capabilities.invokeDelegated(
                    ownerPluginId = authorization.pluginId,
                    grantedScopes = authorization.grantedScopes,
                    capabilityId = capabilityId,
                    parameters = org.json.JSONObject(parameters.toString())
                )
            }
        )
    )
}
