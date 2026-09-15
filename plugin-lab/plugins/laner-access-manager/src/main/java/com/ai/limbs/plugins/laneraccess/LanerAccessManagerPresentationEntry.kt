package com.ai.limbs.plugins.laneraccess

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost

class LanerAccessManagerPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == PLUGIN_ID) {
            "Unexpected Laner access manager presentation identity: ${host.pluginId}"
        }
        val registration = host.registerPageProvider(
            PAGE_PROVIDER_ID,
            LanerAccessManagerPageProvider(host),
            mapOf("kind" to "plugin_page", "screen_id" to SCREEN_ID)
        )
        return InProcessPluginPresentationHandle { registration.close() }
    }

    private companion object {
        const val PLUGIN_ID = "plugin.laner.access_manager"
        const val PAGE_PROVIDER_ID = "plugin.laner.access_manager.page"
        const val SCREEN_ID = "plugin.laner.access_manager.screen"
    }
}
