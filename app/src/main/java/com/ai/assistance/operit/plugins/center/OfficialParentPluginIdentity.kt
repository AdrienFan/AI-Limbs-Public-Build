package com.ai.assistance.operit.plugins.center

/**
 * Kernel-side admission policy for privileged official parent plugins.
 *
 * Manifest roles are functional claims. They never establish trusted identity by themselves.
 * Identity requires an exact official plugin ID and persisted verification by the official signer.
 */
internal object OfficialParentPluginIdentity {
    const val SIGNER_ID = "ai-limbs-parent-plugin-dev-v1"

    private val requiredRoles = mapOf(
        "plugin.system.extension_hub" to "system_extension_hub",
        "plugin.system.bridge" to "system_bridge",
        "plugin.system.developer_guide" to "system_plugin",
        "plugin.system.packager" to "system_packager"
    )

    fun requiredRole(pluginId: String): String? = requiredRoles[pluginId]

    fun isTrusted(pluginId: String, metadata: PluginInstallMetadata): Boolean =
        requiredRoles.containsKey(pluginId) &&
            metadata.pluginId == pluginId &&
            metadata.trustVerdict == PluginTrustVerdict.TRUSTED &&
            metadata.signerId == SIGNER_ID
}
