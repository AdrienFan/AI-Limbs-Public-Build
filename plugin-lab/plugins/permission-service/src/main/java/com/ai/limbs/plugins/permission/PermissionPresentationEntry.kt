package com.ai.limbs.plugins.permission

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost

class PermissionPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == ID) { "Unexpected permission service presentation owner" }
        // A hot upgrade can publish the presentation descriptor before the UI-state binding
        // appears in a Host snapshot. Register the page independently and observe that binding.
        val controller = PermissionPresentationController(host.providers, host.scope) { error ->
            host.logger.e("PermissionService", "Presentation state channel failed: " + error.message)
        }
        val pairing = PermissionPairingOverlay(host, controller)
        val overlayRegistration = host.registerPageProvider(PAIRING_OVERLAY_PROVIDER, pairing, pairingOverlayMetadata)
        val registration = host.registerPageProvider(
            "$ID.page",
            PermissionPage(host, controller, pairing),
            mapOf("kind" to "plugin_page", "screen_id" to "$ID.screen")
        )
        return InProcessPluginPresentationHandle {
            try { pairing.close() }
            finally {
                registration.close()
                overlayRegistration.close()
            }
        }
    }

    private companion object { const val ID = "plugin.system.permission_service" }
}
