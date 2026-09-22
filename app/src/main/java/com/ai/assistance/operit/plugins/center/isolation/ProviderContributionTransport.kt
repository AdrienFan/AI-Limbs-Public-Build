package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.plugins.center.BusinessPageProviderMetadata
import com.ai.assistance.operit.plugins.center.CanonicalContributionContract
import com.ai.assistance.operit.plugins.center.PluginContributionContractCodec
import com.ai.assistance.operit.plugins.center.PluginContributionKind
import com.ai.assistance.operit.plugins.center.PluginContributionRecord
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import org.json.JSONObject

/**
 * Process-neutral envelope for an already validated canonical Provider contribution.
 *
 * The canonical contract carries identity/ownership/API metadata. The protocol only describes which
 * generic proxy shape Core must construct for the runtime-local payload; it never names a plugin.
 */
internal enum class ProviderProxyProtocol(val wireName: String) {
    CAPABILITY_EXECUTOR("capability_executor.v1"),
    UI_STATE("ui_state.v1"),
    PAGE_METADATA("page_metadata.v1"),
    CHILD_EXTENSION_INSTALLER("child_extension_installer.v1");

    companion object {
        fun fromWireName(raw: String): ProviderProxyProtocol =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
                ?: throw PluginInstallException(
                    "PLUGIN_WORKER_PROVIDER_PROTOCOL_UNSUPPORTED",
                    "Worker returned unsupported Provider proxy protocol: " + raw
                )
    }
}

internal data class ProviderContributionEnvelope(
    val contract: CanonicalContributionContract,
    val protocol: ProviderProxyProtocol,
    val proxy: JSONObject
)

internal object ProviderContributionTransportCodec {
    const val SCHEMA_VERSION = 1

    fun encode(record: PluginContributionRecord): JSONObject {
        if (record.kind != PluginContributionKind.PROVIDER) {
            throw PluginInstallException(
                "WORKER_PROVIDER_CONTRACT_INVALID",
                "Only canonical Provider records may enter the Provider transport"
            )
        }

        val proxy = when (val payload = record.payload) {
            is InProcessCapabilityExecutor -> proxy(ProviderProxyProtocol.CAPABILITY_EXECUTOR)
            is InProcessUiStateProvider -> proxy(ProviderProxyProtocol.UI_STATE)
                .put("state_json", payload.stateJson.value ?: JSONObject.NULL)
            is InProcessPageProvider,
            BusinessPageProviderMetadata -> proxy(ProviderProxyProtocol.PAGE_METADATA)
            is ExtensionHubService -> proxy(ProviderProxyProtocol.CHILD_EXTENSION_INSTALLER)
            else -> throw PluginInstallException(
                "WORKER_PROVIDER_NOT_PROXYABLE",
                "Provider " + record.id + " has no structured cross-process proxy protocol: " +
                    (payload?.let { it::class.java.name } ?: "null")
            )
        }

        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("contract", PluginContributionContractCodec.encode(record.contract))
            .put("proxy", proxy)
    }

    fun decode(value: JSONObject): ProviderContributionEnvelope {
        val schema = value.getInt("schema_version")
        if (schema != SCHEMA_VERSION) {
            throw PluginInstallException(
                "PLUGIN_WORKER_PROVIDER_SCHEMA_UNSUPPORTED",
                "Unsupported Provider transport schema: " + schema
            )
        }

        val contract = PluginContributionContractCodec.decode(value.getJSONObject("contract"))
        if (contract.kind != PluginContributionKind.PROVIDER) {
            throw PluginInstallException(
                "PLUGIN_WORKER_PROVIDER_CONTRACT_INVALID",
                "Provider transport carried a non-Provider canonical contract"
            )
        }

        val proxy = JSONObject(value.getJSONObject("proxy").toString())
        return ProviderContributionEnvelope(
            contract = contract,
            protocol = ProviderProxyProtocol.fromWireName(proxy.getString("protocol")),
            proxy = proxy
        )
    }

    private fun proxy(protocol: ProviderProxyProtocol): JSONObject =
        JSONObject().put("protocol", protocol.wireName)
}
