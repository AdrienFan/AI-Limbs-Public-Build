package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.plugins.center.CallerAwarePluginServiceEndpoint
import com.ai.assistance.operit.plugins.center.CanonicalContributionContract
import com.ai.assistance.operit.plugins.center.PluginContributionContractCodec
import com.ai.assistance.operit.plugins.center.PluginContributionKind
import com.ai.assistance.operit.plugins.center.PluginContributionRecord
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginServiceEndpoint
import org.json.JSONObject

internal enum class ServiceProxyProtocol(val wireName: String) {
    RPC("service.rpc.v1");

    companion object {
        fun fromWireName(raw: String): ServiceProxyProtocol =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
                ?: throw PluginInstallException(
                    "PLUGIN_WORKER_SERVICE_PROTOCOL_UNSUPPORTED",
                    "Unsupported Service proxy protocol: " + raw
                )
    }
}

internal data class ServiceContributionEnvelope(
    val contract: CanonicalContributionContract,
    val protocol: ServiceProxyProtocol
)

internal object ServiceContributionTransportCodec {
    const val SCHEMA_VERSION = 1

    fun encode(record: PluginContributionRecord): JSONObject {
        if (record.kind != PluginContributionKind.SERVICE) {
            throw PluginInstallException(
                "WORKER_SERVICE_CONTRACT_INVALID",
                "Only canonical Service records may enter Service transport"
            )
        }
        if (record.payload !is PluginServiceEndpoint && record.payload !is CallerAwarePluginServiceEndpoint) {
            throw PluginInstallException(
                "WORKER_SERVICE_NOT_PROXYABLE",
                "Service " + record.id + " does not expose the generic RPC endpoint contract"
            )
        }
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("contract", PluginContributionContractCodec.encode(record.contract))
            .put("protocol", ServiceProxyProtocol.RPC.wireName)
    }

    fun decode(value: JSONObject): ServiceContributionEnvelope {
        val schema = value.getInt("schema_version")
        if (schema != SCHEMA_VERSION) {
            throw PluginInstallException(
                "PLUGIN_WORKER_SERVICE_SCHEMA_UNSUPPORTED",
                "Unsupported Service transport schema: " + schema
            )
        }
        val contract = PluginContributionContractCodec.decode(value.getJSONObject("contract"))
        if (contract.kind != PluginContributionKind.SERVICE) {
            throw PluginInstallException(
                "PLUGIN_WORKER_SERVICE_CONTRACT_INVALID",
                "Service transport carried a non-Service canonical contract"
            )
        }
        return ServiceContributionEnvelope(
            contract = contract,
            protocol = ServiceProxyProtocol.fromWireName(value.getString("protocol"))
        )
    }
}
