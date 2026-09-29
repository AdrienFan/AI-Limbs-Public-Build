package com.ai.assistance.operit.ui.main.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A Host-owned slot container. The installed plugin supplies the actual View and its behavior.
 * Provider discovery stays in the Host so Plugin Center does not render plugin-owned buttons.
 */
@Composable
internal fun PluginPageSlotHost(pageId: String, slotId: String) {
    var bindings by remember(pageId, slotId) {
        mutableStateOf<List<InProcessProviderBinding>>(emptyList())
    }
    LaunchedEffect(pageId, slotId) {
        while (isActive) {
            bindings = PluginPlatformKernel.presentationProvidersSnapshot()
                .asSequence()
                .filter { it.metadata["kind"] == "host_page_slot" && it.metadata["ai_limbs.host_page_slot.v1"] == "true" }
                .filter { it.metadata["target_page_id"] == pageId && it.metadata["slot_id"] == slotId }
                .filter { it.id.startsWith("${it.ownerPluginId}.") && it.payload is InProcessPageProvider }
                .sortedBy { it.id }
                .take(3)
                .toList()
            delay(1_500L)
        }
    }
    bindings.forEach { binding ->
        val provider = binding.payload as InProcessPageProvider
        key(binding.ownerPluginId, binding.id, provider) {
            AndroidView(factory = { context ->
                provider.createView(context, object : InProcessSharedUiHost {
                    override fun supports(componentId: String): Boolean = false
                    override fun createComponent(componentId: String, parametersJson: String): View =
                        error("Page slot has no shared component: $componentId")
                })
            })
        }
    }
}
