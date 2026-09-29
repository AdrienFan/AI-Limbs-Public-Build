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
        val provider = LanerChatModeProvider(host) { name, parameters ->
            org.json.JSONObject(
                host.invokePluginCapability(
                    "$LANER_CHAT_PLUGIN_ID.$name",
                    parameters.toString()
                )
            )
        }
        val registration = host.registerPresentationProvider(
            LANER_CHAT_MODE_PROVIDER_ID,
            provider,
            mapOf(
                "kind" to "chat_mode_extension",
                "api" to "1",
                "provider_type_id" to LanerChatContract.PROVIDER_TYPE_ID
            )
        )
        val quickRegistration = host.registerPageProvider(
            LANER_CHAT_QUICK_PROVIDER_ID,
            LanerChatQuickPageProvider(host, provider) { name, parameters ->
                org.json.JSONObject(
                    host.invokePluginCapability(
                        "$LANER_CHAT_PLUGIN_ID.$name",
                        parameters.toString()
                    )
                )
            },
            mapOf(
                "overlay_enabled" to "true",
                "host_collapsed_drag" to "true",
                "kind" to "plugin_page"
            )
        )
        val launcherRegistration = host.registerPageProvider(
            LANER_CHAT_QUICK_LAUNCHER_ID,
            LanerChatQuickLauncherProvider(host) { name, parameters ->
                org.json.JSONObject(
                    host.invokePluginCapability(
                        "$LANER_CHAT_PLUGIN_ID.$name",
                        parameters.toString()
                    )
                )
            },
            quickLauncherSlotMetadata()
        )
        host.logger.i("LanerChat", "Laner Chat presentation mounted")
        return InProcessPluginPresentationHandle {
            provider.stop()
            try {
                host.invokeHostCapability(
                    "host.window.overlay@1",
                    org.json.JSONObject()
                        .put("operation", "remove")
                        .put("overlay_id", LANER_CHAT_OVERLAY_ID).toString()
                )
            } finally {
                launcherRegistration.close()
                quickRegistration.close()
                registration.close()
            }
            host.logger.i("LanerChat", "Laner Chat presentation stopped")
        }
    }
}
