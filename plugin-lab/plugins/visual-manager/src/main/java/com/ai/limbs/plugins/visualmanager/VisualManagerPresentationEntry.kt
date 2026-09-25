package com.ai.limbs.plugins.visualmanager

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost

class VisualManagerPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == VISUAL_PLUGIN_ID) {
            "Unexpected Visual Manager presentation identity: ${host.pluginId}"
        }
        val registration = host.registerPageProvider(
            VISUAL_PAGE_ID,
            VisualManagerPageProvider(host, VisualManagerPresentationClient(host)),
            mapOf("kind" to "plugin_page", "screen_id" to VISUAL_SCREEN_ID)
        )
        return InProcessPluginPresentationHandle { registration.close() }
    }
}
