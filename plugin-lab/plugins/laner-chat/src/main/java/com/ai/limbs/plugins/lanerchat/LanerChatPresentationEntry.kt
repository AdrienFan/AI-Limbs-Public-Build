package com.ai.limbs.plugins.lanerchat

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost

internal const val LANER_CHAT_MODE_PROVIDER_ID = "$LANER_CHAT_PLUGIN_ID.chat_mode"

class LanerChatPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == LANER_CHAT_PLUGIN_ID) {
            "Unexpected Laner Chat presentation identity: ${host.pluginId}"
        }
        val provider = LanerChatModeProvider(host)
        val registration = host.registerPresentationProvider(
            LANER_CHAT_MODE_PROVIDER_ID,
            provider,
            mapOf(
                "kind" to "chat_mode_extension",
                "api" to "1",
                "provider_type_id" to LanerChatContract.PROVIDER_TYPE_ID
            )
        )
        host.logger.i("LanerChat", "Laner Chat presentation mounted")
        return InProcessPluginPresentationHandle {
            provider.stop()
            registration.close()
            host.logger.i("LanerChat", "Laner Chat presentation stopped")
        }
    }
}
