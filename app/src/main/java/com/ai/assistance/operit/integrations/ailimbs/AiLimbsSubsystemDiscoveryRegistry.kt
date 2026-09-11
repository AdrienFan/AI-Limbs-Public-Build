package com.ai.assistance.operit.integrations.ailimbs

import com.ai.limbs.plugin.runtime.ChildAiIngressDiscovery
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal data class AiLimbsSubsystemDiscoveryBinding(
    val extensionId: String,
    val schemaId: String,
    val payloadJson: String
) {
    fun payload(): JSONObject = JSONObject(payloadJson)
}

/** Host registry for child-owned AI ingress knowledge. Identity is always Host-attested. */
internal object AiLimbsSubsystemDiscoveryRegistry {
    private data class OwnedDiscovery(
        val token: String,
        val binding: AiLimbsSubsystemDiscoveryBinding
    )

    private val discoveries = ConcurrentHashMap<String, OwnedDiscovery>()

    fun publish(
        extensionId: String,
        discovery: ChildAiIngressDiscovery
    ): AutoCloseable {
        val owner = extensionId.trim().lowercase()
        val schema = discovery.schemaId.trim().lowercase()
        require(IDENTIFIER.matches(owner)) { "Invalid child extension identity: $extensionId" }
        require(IDENTIFIER.matches(schema)) { "Invalid AI ingress discovery schema: ${discovery.schemaId}" }
        require(discovery.payloadJson.length <= MAX_PAYLOAD_CHARS) { "AI ingress discovery payload is too large" }
        val payload = JSONObject(discovery.payloadJson)
        val binding = AiLimbsSubsystemDiscoveryBinding(owner, schema, payload.toString())
        val owned = OwnedDiscovery(UUID.randomUUID().toString(), binding)
        check(discoveries.putIfAbsent(owner, owned) == null) {
            "AI ingress discovery already published by $owner"
        }
        return AutoCloseable {
            discoveries.computeIfPresent(owner) { _, current ->
                if (current.token == owned.token) null else current
            }
        }
    }

    fun resolve(extensionId: String): AiLimbsSubsystemDiscoveryBinding? =
        discoveries[extensionId.trim().lowercase()]?.binding

    private const val MAX_PAYLOAD_CHARS = 65_536
    private val IDENTIFIER = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
}
