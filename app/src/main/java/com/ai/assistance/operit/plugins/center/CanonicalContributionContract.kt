package com.ai.assistance.operit.plugins.center

import org.json.JSONObject

/**
 * Canonical, transport-neutral description of one plugin contribution.
 *
 * The payload object is deliberately not part of this contract. Local and remote runtimes may
 * carry the payload differently, but ownership, identity and API semantics must remain identical.
 */
enum class PluginContributionContractType(val wireName: String) {
    CAPABILITY_EXECUTION("capability.execution"),
    SERVICE_RPC("service.rpc"),
    PROVIDER_BINDING("provider.binding"),
    EXTENSION_BINDING("extension.binding");

    companion object {
        fun forKind(kind: PluginContributionKind): PluginContributionContractType = when (kind) {
            PluginContributionKind.CAPABILITY -> CAPABILITY_EXECUTION
            PluginContributionKind.SERVICE -> SERVICE_RPC
            PluginContributionKind.PROVIDER -> PROVIDER_BINDING
            PluginContributionKind.EXTENSION -> EXTENSION_BINDING
        }

        fun fromWireName(raw: String): PluginContributionContractType =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
                ?: throw IllegalArgumentException("Unsupported contribution contract type: " + raw)
    }
}

data class CanonicalContributionContract internal constructor(
    val schemaVersion: Int,
    val kind: PluginContributionKind,
    val contractType: PluginContributionContractType,
    val ownerPluginId: String,
    val id: String,
    val apiVersion: Int?,
    val extensionPoint: String?,
    val metadata: Map<String, String>
) {
    init {
        require(schemaVersion == PluginContributionContractCodec.SCHEMA_VERSION) {
            "Unsupported contribution contract schema: " + schemaVersion
        }
        require(ownerPluginId.isNotBlank() && ownerPluginId == ownerPluginId.trim()) {
            "Contribution owner must be canonical and non-blank"
        }
        require(id.isNotBlank() && id == id.trim()) {
            "Contribution id must be canonical and non-blank"
        }
        require(contractType == PluginContributionContractType.forKind(kind)) {
            "Contribution contract type does not match kind: " + kind
        }
        when (kind) {
            PluginContributionKind.CAPABILITY,
            PluginContributionKind.PROVIDER -> {
                require(apiVersion == null) { kind.name.lowercase() + " must not declare apiVersion" }
                require(extensionPoint == null) { kind.name.lowercase() + " must not declare extensionPoint" }
            }
            PluginContributionKind.SERVICE -> {
                require(apiVersion != null && apiVersion > 0) { "service apiVersion must be positive" }
                require(extensionPoint == null) { "service must not declare extensionPoint" }
            }
            PluginContributionKind.EXTENSION -> {
                require(apiVersion != null && apiVersion > 0) { "extension apiVersion must be positive" }
                require(!extensionPoint.isNullOrBlank()) { "extensionPoint is required for extension contributions" }
                require(extensionPoint == extensionPoint.trim().lowercase()) {
                    "extensionPoint must be canonical"
                }
            }
        }
    }
}

internal object CanonicalContributionContracts {
    fun capability(
        ownerPluginId: String,
        id: String,
        metadata: Map<String, String> = emptyMap()
    ): CanonicalContributionContract = create(
        kind = PluginContributionKind.CAPABILITY,
        ownerPluginId = ownerPluginId,
        id = id,
        metadata = metadata
    )

    fun service(
        ownerPluginId: String,
        id: String,
        apiVersion: Int,
        metadata: Map<String, String> = emptyMap()
    ): CanonicalContributionContract = create(
        kind = PluginContributionKind.SERVICE,
        ownerPluginId = ownerPluginId,
        id = id,
        apiVersion = apiVersion,
        metadata = metadata
    )

    fun provider(
        ownerPluginId: String,
        id: String,
        metadata: Map<String, String> = emptyMap()
    ): CanonicalContributionContract = create(
        kind = PluginContributionKind.PROVIDER,
        ownerPluginId = ownerPluginId,
        id = id,
        metadata = metadata
    )

    fun extension(
        ownerPluginId: String,
        point: String,
        id: String,
        apiVersion: Int,
        metadata: Map<String, String> = emptyMap()
    ): CanonicalContributionContract = create(
        kind = PluginContributionKind.EXTENSION,
        ownerPluginId = ownerPluginId,
        id = id,
        apiVersion = apiVersion,
        extensionPoint = point,
        metadata = metadata
    )

    internal fun decoded(
        schemaVersion: Int,
        kind: PluginContributionKind,
        contractType: PluginContributionContractType,
        ownerPluginId: String,
        id: String,
        apiVersion: Int?,
        extensionPoint: String?,
        metadata: Map<String, String>
    ): CanonicalContributionContract = CanonicalContributionContract(
        schemaVersion = schemaVersion,
        kind = kind,
        contractType = contractType,
        ownerPluginId = ownerPluginId.trim(),
        id = id.trim(),
        apiVersion = apiVersion,
        extensionPoint = extensionPoint?.trim()?.lowercase(),
        metadata = metadata.toMap()
    )

    private fun create(
        kind: PluginContributionKind,
        ownerPluginId: String,
        id: String,
        apiVersion: Int? = null,
        extensionPoint: String? = null,
        metadata: Map<String, String>
    ): CanonicalContributionContract = CanonicalContributionContract(
        schemaVersion = PluginContributionContractCodec.SCHEMA_VERSION,
        kind = kind,
        contractType = PluginContributionContractType.forKind(kind),
        ownerPluginId = ownerPluginId.trim(),
        id = id.trim(),
        apiVersion = apiVersion,
        extensionPoint = extensionPoint?.trim()?.lowercase(),
        metadata = metadata.toMap()
    )
}

internal object PluginContributionContractCodec {
    const val SCHEMA_VERSION = 1

    fun encode(contract: CanonicalContributionContract): JSONObject {
        val metadata = JSONObject()
        contract.metadata.toSortedMap().forEach { (key, value) -> metadata.put(key, value) }
        return JSONObject()
            .put("schema_version", contract.schemaVersion)
            .put("kind", contract.kind.name.lowercase())
            .put("contract_type", contract.contractType.wireName)
            .put("owner", contract.ownerPluginId)
            .put("id", contract.id)
            .put("api_version", contract.apiVersion ?: JSONObject.NULL)
            .put("extension_point", contract.extensionPoint ?: JSONObject.NULL)
            .put("metadata", metadata)
    }

    fun decode(value: JSONObject): CanonicalContributionContract {
        val kindRaw = value.getString("kind").trim().uppercase()
        val kind = runCatching { PluginContributionKind.valueOf(kindRaw) }
            .getOrElse { throw IllegalArgumentException("Unsupported contribution kind: " + kindRaw, it) }
        val contractType = PluginContributionContractType.fromWireName(value.getString("contract_type"))
        val metadataJson = value.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap<String, String> {
            val keys = metadataJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, metadataJson.optString(key))
            }
        }
        return CanonicalContributionContracts.decoded(
            schemaVersion = value.getInt("schema_version"),
            kind = kind,
            contractType = contractType,
            ownerPluginId = value.getString("owner"),
            id = value.getString("id"),
            apiVersion = if (value.has("api_version") && !value.isNull("api_version")) value.getInt("api_version") else null,
            extensionPoint = if (value.has("extension_point") && !value.isNull("extension_point")) value.getString("extension_point") else null,
            metadata = metadata
        )
    }
}
