package com.ai.assistance.operit.plugins.center

/**
 * Canonical capability metadata introduced by Arch Test 3.
 *
 * Arch Test 3 introduced this as the canonical execution-owner metadata source.
 * Arch Test 7 makes RuntimeCapabilityRouter consume executionOwner directly for transport
 * selection while existing handlers and policy enforcement remain authoritative.
 */
enum class CapabilityExecutionOwner {
    HOST,
    BUSINESS,
    PLUGIN_RUNTIME,
    EXTERNAL_DAEMON
}

data class CapabilitySchemaRef(
    val id: String,
    val version: Int
)

enum class CapabilityPolicyRef {
    LEGACY_EXISTING_POLICY
}

enum class CapabilityHandlerAdapter {
    LEGACY_SYSTEM_HOST_GATEWAY_V1
}
data class CapabilityDescriptor(
    val id: String,
    val version: Int,
    val executionOwner: CapabilityExecutionOwner,
    val requestSchema: CapabilitySchemaRef,
    val responseSchema: CapabilitySchemaRef,
    val policy: CapabilityPolicyRef,
    val maturity: HostPrimitiveMaturity,
    val handlerAdapter: CapabilityHandlerAdapter
)

/**
 * Single registration point for the current Host Primitive catalog.
 *
 * The explicit list is deliberate. A new Host Primitive must be added here in the same
 * change; ci/script/check_runtime_capability_registry.py rejects catalog/registry drift.
 * executionOwner is the single canonical owner source; legacy Host-owner mirror sets were
 * removed in Arch Test 8 and CI rejects their reintroduction.
 */
object CapabilityRegistry {
    private val legacyRequestEnvelope =
        CapabilitySchemaRef("ai_limbs.runtime.capability.request-envelope", 1)
    private val legacyResponseEnvelope =
        CapabilitySchemaRef("ai_limbs.runtime.capability.response-envelope", 1)

    val all: List<CapabilityDescriptor> = listOf(
        hostPrimitive("host.filesystem@1"),
        hostPrimitive("host.process@1"),
        hostPrimitive("host.ui.automation@1"),
        hostPrimitive("host.screen.capture@1"),
        hostPrimitive("host.network@1"),
        hostPrimitive("host.background.runtime@1"),
        hostPrimitive("host.notification@1"),
        hostPrimitive("host.android.settings@1"),
        hostPrimitive("host.android.package@1"),
        hostPrimitive("host.bluetooth@1"),
        hostPrimitive("host.location@1"),
        hostPrimitive("host.clipboard@1"),
        hostPrimitive("host.permission@1"),
        hostPrimitive("host.audio.capture@1"),
        hostPrimitive("host.audio.playback@1"),
        hostPrimitive("host.android.component@1"),
        hostPrimitive("host.event@1"),
        hostPrimitive("host.device.state@1"),
        hostPrimitive("host.scheduler@1"),
        hostPrimitive("host.ai.inference@1"),
        hostPrimitive("host.chat@1"),
        hostPrimitive("host.logging@1"),
        hostPrimitive("host.secrets@1"),
        hostPrimitive("host.ui.surface@1"),
        hostPrimitive("host.window.overlay@1"),
        hostPrimitive("host.capability@1"),
        hostPrimitive("host.plugin.service@1"),
        hostPrimitive("host.extension.routing@1"),
        hostPrimitive("host.plugin.runtime@1"),
        hostPrimitive("host.pipeline.hook@1"),
        hostPrimitive("host.android.usage@1"),
        hostPrimitive("host.content@1"),
        hostPrimitive("host.web.runtime@1"),
        hostPrimitive("host.ingress@1"),
        hostPrimitive("host.authorization@1"),
        hostPrimitive("kernel.plugin.trust@1"),
        hostPrimitive("host.ui.widget@1"),
        hostPrimitive("host.camera.capture@1"),
        hostPrimitive("host.custom_access_prompt@1"),
        hostPrimitive("host.work_manual@1"),
        hostPrimitive("host.peripheral.power@1"),
        hostPrimitive("host.peripheral.display@1"),
        hostPrimitive("host.peripheral.keyboard@1"),
        hostPrimitive("host.peripheral.pointer@1"),
        hostPrimitive("host.ui.presentation@1", CapabilityExecutionOwner.HOST),
        hostPrimitive("host.interaction.cycle@1"),
        hostPrimitive("host.privileged.runtime@1", CapabilityExecutionOwner.HOST),
        hostPrimitive("host.resident.runtime@1", CapabilityExecutionOwner.HOST),
        hostPrimitive("host.ui.layout@1", CapabilityExecutionOwner.HOST)
    )

    private val byId: Map<String, CapabilityDescriptor> =
        all.associateBy { it.id.lowercase() }

    init {
        check(byId.size == all.size) {
            "Duplicate capability id in CapabilityRegistry"
        }
    }

    fun find(id: String): CapabilityDescriptor? = byId[id.trim().lowercase()]

    fun requireDescriptor(id: String): CapabilityDescriptor =
        requireNotNull(find(id)) { "Unknown runtime capability: $id" }

    fun descriptorsOwnedBy(owner: CapabilityExecutionOwner): List<CapabilityDescriptor> =
        all.filter { it.executionOwner == owner }

    fun idsOwnedBy(owner: CapabilityExecutionOwner): Set<String> =
        descriptorsOwnedBy(owner).mapTo(linkedSetOf()) { it.id }

    fun isOwnedBy(id: String, owner: CapabilityExecutionOwner): Boolean =
        find(id)?.executionOwner == owner

    private fun hostPrimitive(
        id: String,
        executionOwner: CapabilityExecutionOwner = CapabilityExecutionOwner.BUSINESS
    ): CapabilityDescriptor {
        val normalized = id.trim().lowercase()
        val definition = requireNotNull(AiLimbsHostPrimitiveCatalog.find(normalized)) {
            "CapabilityRegistry entry has no Host Primitive definition: $id"
        }
        val version = normalized.substringAfterLast('@', "").toIntOrNull()
            ?: error("Capability id must end with a numeric version: $id")

        return CapabilityDescriptor(
            id = definition.id,
            version = version,
            executionOwner = executionOwner,
            requestSchema = legacyRequestEnvelope,
            responseSchema = legacyResponseEnvelope,
            policy = CapabilityPolicyRef.LEGACY_EXISTING_POLICY,
            maturity = definition.maturity,
            handlerAdapter = CapabilityHandlerAdapter.LEGACY_SYSTEM_HOST_GATEWAY_V1
        )
    }
}
