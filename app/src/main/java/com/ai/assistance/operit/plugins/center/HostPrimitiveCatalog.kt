package com.ai.assistance.operit.plugins.center

enum class HostPrimitiveMaturity { CONFIRMED, CANDIDATE, TARGET_CONFIRMED }

enum class HostPrimitiveExposure { DECLARED, PARTIAL, BOUND, KERNEL_GATE }

data class HostPrimitiveDefinition(
    val number: Int,
    val id: String,
    val title: String,
    val maturity: HostPrimitiveMaturity,
    val exposure: HostPrimitiveExposure,
    val requestableScope: Boolean
)

data class HostPrimitiveSnapshot(
    val definition: HostPrimitiveDefinition,
    val policyAllowed: Boolean?
)

object AiLimbsHostPrimitiveCatalog {
    val all: List<HostPrimitiveDefinition> = listOf(
        HostPrimitiveDefinition(1, "host.filesystem@1", "Filesystem", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(2, "host.process@1", "Process / Terminal Session", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(3, "host.ubuntu.runtime@1", "Ubuntu Runtime", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(4, "host.ui.automation@1", "UI Automation / Interaction", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(5, "host.screen.capture@1", "Screen Capture", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(6, "host.network@1", "Network I/O", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(7, "host.background.runtime@1", "Background Runtime", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(8, "host.notification@1", "Notification", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(9, "host.android.settings@1", "Android Settings", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(10, "host.android.package@1", "Android Package / App", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(11, "host.bluetooth@1", "Bluetooth", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(12, "host.location@1", "Location", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(13, "host.clipboard@1", "Clipboard", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(14, "host.permission@1", "Permission / Consent Broker", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(15, "host.audio.capture@1", "Audio Capture", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(16, "host.audio.playback@1", "Audio Playback", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(17, "host.android.component@1", "Android Component Invocation", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(18, "host.event@1", "Host / System Event", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(19, "host.device.state@1", "Device State / Capability", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(20, "host.scheduler@1", "Persistent Scheduler", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(21, "host.ai.inference@1", "AI Inference / Model Routing", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(22, "host.chat@1", "Chat / Conversation Runtime", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(23, "host.logging@1", "Structured Logging", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, true),
        HostPrimitiveDefinition(24, "host.secrets@1", "Secret / Credential Broker", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.PARTIAL, false),
        HostPrimitiveDefinition(25, "host.ui.surface@1", "Host UI Surface / Route", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(26, "host.window.overlay@1", "System Overlay Window", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(27, "host.capability@1", "Capability Bus", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(28, "host.plugin.service@1", "Plugin Service RPC / Dependency", HostPrimitiveMaturity.CANDIDATE, HostPrimitiveExposure.PARTIAL, false),
        HostPrimitiveDefinition(29, "host.extension.routing@1", "Typed Extension Point / Binding", HostPrimitiveMaturity.TARGET_CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(30, "host.plugin.runtime@1", "Plugin Runtime Host / Isolation", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(31, "host.pipeline.hook@1", "Typed Pipeline Hook / Interceptor", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(32, "host.android.usage@1", "Android App Usage / Activity History", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(33, "host.content@1", "Content / Document Broker", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(34, "host.web.runtime@1", "Embedded Web Runtime / Browser Session", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(35, "host.ingress@1", "External Ingress / Request Broker", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(36, "host.authorization@1", "Execution Authorization / Policy", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.KERNEL_GATE, false),
        HostPrimitiveDefinition(37, "kernel.plugin.trust@1", "Plugin Package Integrity / Provenance Gate", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.KERNEL_GATE, false),
        HostPrimitiveDefinition(38, "host.ui.widget@1", "Desktop AppWidget Surface", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(39, "host.camera.capture@1", "Camera Capture / Visual Sensor", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false)
    )

    private val byId = all.associateBy { it.id.lowercase() }

    fun find(id: String): HostPrimitiveDefinition? = byId[id.trim().lowercase()]

    fun snapshots(surfacePolicy: HostSurfacePolicy): List<HostPrimitiveSnapshot> = all.map { definition ->
        val policyAllowed = if (definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND) {
            surfacePolicy.isAllowed(PluginSurfaceIds.hostPrimitive(definition.id))
        } else null
        HostPrimitiveSnapshot(definition, policyAllowed)
    }

    fun requireInstallableScopes(scopes: Set<String>) {
        scopes.forEach { rawScope ->
            val scope = rawScope.trim().lowercase()
            val definition = find(scope)
                ?: throw PluginInstallException("PLUGIN_SCOPE_UNKNOWN", "Unknown AI Limbs Host Primitive scope: $rawScope")
            if (!definition.requestableScope || definition.exposure != HostPrimitiveExposure.BOUND) {
                throw PluginInstallException(
                    "PLUGIN_SCOPE_NOT_AVAILABLE",
                    "Host Primitive is not requestable in this kernel build: ${definition.id} (${definition.exposure})"
                )
            }
        }
    }
}
