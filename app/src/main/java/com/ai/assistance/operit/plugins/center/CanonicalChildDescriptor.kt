package com.ai.assistance.operit.plugins.center

import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import org.json.JSONObject

internal enum class CanonicalChildDescriptorKind(val wireName: String) {
    PARENT_POINT("parent_point"),
    CHILD_BINDING("child_binding"),
    CAPABILITY("capability"),
    UI_CONTRIBUTION("ui_contribution");

    companion object {
        fun fromWireName(raw: String): CanonicalChildDescriptorKind =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
                ?: throw IllegalArgumentException("Unsupported child descriptor kind: " + raw)
    }
}

internal enum class CanonicalChildProtocol(val wireName: String, val version: Int) {
    EXTENSION_POINT("child.extension_point", 1),
    EXTENSION_BINDING("child.extension_binding", 1),
    CAPABILITY_EXECUTION("capability.execution", 1),
    UI_CONTRIBUTION("child.ui_contribution", 1);

    companion object {
        fun fromWireName(raw: String, version: Int): CanonicalChildProtocol =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() && it.version == version }
                ?: throw IllegalArgumentException("Unsupported child protocol: " + raw + "@" + version)
    }
}

internal data class CanonicalChildDescriptor(
    val schemaVersion: Int,
    val kind: CanonicalChildDescriptorKind,
    val protocol: CanonicalChildProtocol,
    val ownerId: String,
    val id: String,
    val target: ChildExtensionTarget,
    val metadata: Map<String, String>
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported child descriptor schema" }
        require(ownerId.isNotBlank() && ownerId == ownerId.trim()) { "Child descriptor owner must be canonical" }
        require(id.isNotBlank() && id == id.trim()) { "Child descriptor id must be canonical" }
        require(target.parentPluginId.isNotBlank() && target.parentPluginId == target.parentPluginId.trim()) {
            "Child descriptor parentPluginId must be canonical"
        }
        require(target.point.isNotBlank() && target.point == target.point.trim()) {
            "Child descriptor point must be canonical"
        }
        require(target.apiVersion > 0) { "Child descriptor apiVersion must be positive" }
        require(protocol == expectedProtocol(kind)) { "Child descriptor protocol does not match kind" }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun expectedProtocol(kind: CanonicalChildDescriptorKind): CanonicalChildProtocol = when (kind) {
            CanonicalChildDescriptorKind.PARENT_POINT -> CanonicalChildProtocol.EXTENSION_POINT
            CanonicalChildDescriptorKind.CHILD_BINDING -> CanonicalChildProtocol.EXTENSION_BINDING
            CanonicalChildDescriptorKind.CAPABILITY -> CanonicalChildProtocol.CAPABILITY_EXECUTION
            CanonicalChildDescriptorKind.UI_CONTRIBUTION -> CanonicalChildProtocol.UI_CONTRIBUTION
        }
    }
}

internal object CanonicalChildDescriptors {
    fun parentPoint(
        ownerPluginId: String,
        point: String,
        apiVersion: Int,
        metadata: Map<String, String>
    ) = create(
        kind = CanonicalChildDescriptorKind.PARENT_POINT,
        ownerId = ownerPluginId,
        id = point,
        target = ChildExtensionTarget(ownerPluginId, point, apiVersion),
        metadata = metadata
    )

    fun childBinding(
        extensionId: String,
        target: ChildExtensionTarget,
        metadata: Map<String, String>
    ) = create(CanonicalChildDescriptorKind.CHILD_BINDING, extensionId, extensionId, target, metadata)

    fun capability(
        extensionId: String,
        capabilityId: String,
        target: ChildExtensionTarget,
        metadata: Map<String, String> = emptyMap()
    ) = create(CanonicalChildDescriptorKind.CAPABILITY, extensionId, capabilityId, target, metadata)

    fun ui(
        extensionId: String,
        contributionId: String,
        target: ChildExtensionTarget,
        metadata: Map<String, String>
    ) = create(CanonicalChildDescriptorKind.UI_CONTRIBUTION, extensionId, contributionId, target, metadata)

    private fun create(
        kind: CanonicalChildDescriptorKind,
        ownerId: String,
        id: String,
        target: ChildExtensionTarget,
        metadata: Map<String, String>
    ) = CanonicalChildDescriptor(
        schemaVersion = CanonicalChildDescriptor.SCHEMA_VERSION,
        kind = kind,
        protocol = CanonicalChildDescriptor.expectedProtocol(kind),
        ownerId = ownerId.trim(),
        id = id.trim(),
        target = target,
        metadata = metadata.toSortedMap()
    )
}

internal object CanonicalChildDescriptorCodec {
    fun encode(value: CanonicalChildDescriptor): JSONObject =
        JSONObject()
            .put("schema_version", value.schemaVersion)
            .put("kind", value.kind.wireName)
            .put("protocol", value.protocol.wireName)
            .put("protocol_version", value.protocol.version)
            .put("owner_id", value.ownerId)
            .put("id", value.id)
            .put("target", JSONObject()
                .put("parent_plugin_id", value.target.parentPluginId)
                .put("point", value.target.point)
                .put("api_version", value.target.apiVersion))
            .put("metadata", JSONObject(value.metadata))

    fun decode(value: JSONObject): CanonicalChildDescriptor {
        val targetValue = value.getJSONObject("target")
        val metadataValue = value.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap {
            val keys = metadataValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, metadataValue.getString(key))
            }
        }
        val kind = CanonicalChildDescriptorKind.fromWireName(value.getString("kind"))
        val protocol = CanonicalChildProtocol.fromWireName(
            value.getString("protocol"),
            value.getInt("protocol_version")
        )
        return CanonicalChildDescriptor(
            schemaVersion = value.getInt("schema_version"),
            kind = kind,
            protocol = protocol,
            ownerId = value.getString("owner_id"),
            id = value.getString("id"),
            target = ChildExtensionTarget(
                parentPluginId = targetValue.getString("parent_plugin_id"),
                point = targetValue.getString("point"),
                apiVersion = targetValue.getInt("api_version")
            ),
            metadata = metadata
        )
    }
}

internal data class CanonicalChildDescriptorEnvelope(
    val descriptor: CanonicalChildDescriptor,
    val payload: JSONObject
)

internal object CanonicalChildDescriptorEnvelopeCodec {
    fun encode(value: CanonicalChildDescriptorEnvelope): JSONObject =
        JSONObject()
            .put("descriptor", CanonicalChildDescriptorCodec.encode(value.descriptor))
            .put("payload", JSONObject(value.payload.toString()))

    fun decode(value: JSONObject): CanonicalChildDescriptorEnvelope =
        CanonicalChildDescriptorEnvelope(
            descriptor = CanonicalChildDescriptorCodec.decode(value.getJSONObject("descriptor")),
            payload = JSONObject(value.optJSONObject("payload")?.toString() ?: "{}")
        )
}
