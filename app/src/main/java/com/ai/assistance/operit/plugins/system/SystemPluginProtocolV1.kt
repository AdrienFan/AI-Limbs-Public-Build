package com.ai.assistance.operit.plugins.system

object SystemPluginProtocolV1 {
    const val FORMAT = "AIL_SYSTEM_PLUGIN_V1"
    const val SCHEMA_VERSION = 1
    const val HOST_ABI = 1
    const val PACKAGE_EXTENSION = ".ailpsys"
    const val MANIFEST_ENTRY = "system-plugin.json"

    const val ROLE_PLUGIN_CENTER = "plugin_center"
    const val ROLE_EXTENSION_HUB = "extension_hub"
    const val ROLE_HOST_ADAPTER = "host_adapter"
    const val ROLE_RECOVERY = "recovery"
    const val ROLE_SYSTEM_SERVICE = "system_service"

    val supportedRoles = setOf(
        ROLE_PLUGIN_CENTER,
        ROLE_EXTENSION_HUB,
        ROLE_HOST_ADAPTER,
        ROLE_RECOVERY,
        ROLE_SYSTEM_SERVICE
    )

    val supportedRuntimeKinds = setOf("declarative", "android_inprocess")
}

data class SystemPluginDisplaySpec(
    val name: String,
    val description: String?
)

data class SystemPluginHostAbiSpec(
    val min: Int,
    val max: Int
)

data class SystemPluginRuntimeSpec(
    val kind: String,
    val entry: String
)

data class SystemPluginSignatureSpec(
    val algorithm: String,
    val signerId: String,
    val entry: String
)

data class SystemPluginManifestV1(
    val pluginId: String,
    val version: String,
    val display: SystemPluginDisplaySpec,
    val role: String,
    val hostAbi: SystemPluginHostAbiSpec,
    val runtime: SystemPluginRuntimeSpec,
    val requestedScopes: Set<String>,
    val signature: SystemPluginSignatureSpec
)

enum class SystemPluginTrustStatus {
    NOT_EVALUATED
}

data class SystemPluginValidationResult(
    val manifest: SystemPluginManifestV1,
    val packageSha256: String,
    val entryCount: Int,
    val verifiedPayloadEntries: Int,
    val trustStatus: SystemPluginTrustStatus = SystemPluginTrustStatus.NOT_EVALUATED
)

class SystemPluginProtocolException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)
