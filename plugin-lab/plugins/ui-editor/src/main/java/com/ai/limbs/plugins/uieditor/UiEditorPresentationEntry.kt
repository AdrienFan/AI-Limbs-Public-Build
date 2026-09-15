package com.ai.limbs.plugins.uieditor

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost

class UiEditorPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == PLUGIN_ID) {
            "Unexpected UI Editor presentation identity: ${host.pluginId}"
        }
        val registration = host.registerPageProvider(
            PAGE_PROVIDER_ID,
            UiEditorPageProvider(host),
            mapOf("kind" to "plugin_page", "screen_id" to SCREEN_ID)
        )
        return InProcessPluginPresentationHandle { registration.close() }
    }

    private companion object {
        const val PLUGIN_ID = "plugin.system.ui_editor"
        const val PAGE_PROVIDER_ID = "plugin.system.ui_editor.page"
        const val SCREEN_ID = "plugin.system.ui_editor.screen"
    }
}
