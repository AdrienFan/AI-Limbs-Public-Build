package com.ai.limbs.plugins.logcenter

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost

class LogCenterPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == PLUGIN_ID) {
            "Unexpected Log Center presentation identity: ${host.pluginId}"
        }
        val registration = host.registerPageProvider(
            PAGE_PROVIDER_ID,
            LogCenterPageProvider(host),
            mapOf("kind" to "plugin_page", "screen_id" to SCREEN_ID)
        )
        return InProcessPluginPresentationHandle { registration.close() }
    }

    private companion object {
        const val PLUGIN_ID = "plugin.system.log_center"
        const val PAGE_PROVIDER_ID = "plugin.system.log_center.page"
        const val SCREEN_ID = "plugin.system.log_center.screen"
    }
}
