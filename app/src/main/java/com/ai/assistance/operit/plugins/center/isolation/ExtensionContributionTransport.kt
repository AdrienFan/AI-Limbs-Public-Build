package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.plugins.center.CanonicalContributionContract
import com.ai.assistance.operit.plugins.center.PluginContributionContractCodec
import com.ai.assistance.operit.plugins.center.PluginContributionKind
import com.ai.assistance.operit.plugins.center.PluginContributionRecord
import com.ai.assistance.operit.plugins.center.PluginExtensionPoints
import com.ai.assistance.operit.plugins.center.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginScreenSpec
import com.ai.assistance.operit.plugins.center.PluginThemeMode
import com.ai.assistance.operit.plugins.center.PluginThemeSpec
import org.json.JSONArray
import org.json.JSONObject

internal enum class ExtensionProtocolType(
    val wireName: String,
    val point: String,
    val apiVersion: Int
) {
    HOME_TILE("ui.home_tile@1", PluginExtensionPoints.UI_HOME_TILE, 1),
    UI_SCREEN("ui.screen@2", PluginExtensionPoints.UI_SCREEN, 2),
    THEME("ui.theme@1", PluginExtensionPoints.UI_THEME, 1);

    companion object {
        fun fromWireName(raw: String): ExtensionProtocolType =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() }
                ?: throw PluginInstallException(
                    "PLUGIN_WORKER_EXTENSION_PROTOCOL_UNSUPPORTED",
                    "Unsupported Extension protocol: " + raw
                )
    }
}

internal data class ExtensionContributionEnvelope(
    val contract: CanonicalContributionContract,
    val protocol: ExtensionProtocolType,
    val payload: Any
)

private interface ExtensionPayloadCodec {
    val protocol: ExtensionProtocolType
    fun accepts(payload: Any?): Boolean
    fun encode(payload: Any?): JSONObject
    fun decode(contract: CanonicalContributionContract, value: JSONObject): Any
}

internal object ExtensionContributionTransportCodecRegistry {
    const val SCHEMA_VERSION = 1

    private val codecs: List<ExtensionPayloadCodec> = listOf(
        object : ExtensionPayloadCodec {
            override val protocol = ExtensionProtocolType.HOME_TILE
            override fun accepts(payload: Any?) = payload is PluginHomeTileSpec
            override fun encode(payload: Any?): JSONObject {
                payload as PluginHomeTileSpec
                return JSONObject()
                    .put("title", payload.title)
                    .put("description", payload.description)
                    .put("screen_id", payload.screenId)
            }
            override fun decode(contract: CanonicalContributionContract, value: JSONObject): Any =
                PluginHomeTileSpec(
                    ownerPluginId = contract.ownerPluginId,
                    id = contract.id,
                    title = value.getString("title"),
                    description = value.optString("description", ""),
                    screenId = value.getString("screen_id")
                )
        },
        object : ExtensionPayloadCodec {
            override val protocol = ExtensionProtocolType.UI_SCREEN
            override fun accepts(payload: Any?) = payload is PluginScreenSpec
            override fun encode(payload: Any?): JSONObject {
                payload as PluginScreenSpec
                return JSONObject()
                    .put("title", payload.title)
                    .put("description", payload.description ?: JSONObject.NULL)
                    .put("schema_id", payload.schemaId)
                    .put("document_json", payload.documentJson)
            }
            override fun decode(contract: CanonicalContributionContract, value: JSONObject): Any =
                PluginScreenSpec(
                    ownerPluginId = contract.ownerPluginId,
                    id = contract.id,
                    title = value.getString("title"),
                    description = if (!value.has("description") || value.isNull("description")) null else value.getString("description"),
                    schemaId = value.getString("schema_id"),
                    documentJson = value.getString("document_json")
                )
        },
        object : ExtensionPayloadCodec {
            override val protocol = ExtensionProtocolType.THEME
            override fun accepts(payload: Any?) = payload is PluginThemeSpec
            override fun encode(payload: Any?): JSONObject {
                payload as PluginThemeSpec
                return JSONObject()
                    .put("mode", payload.mode.name)
                    .put("pure_black", payload.pureBlack)
                    .put("colors", JSONObject(payload.colors))
                    .put("background_gradient", JSONArray(payload.backgroundGradient))
            }
            override fun decode(contract: CanonicalContributionContract, value: JSONObject): Any =
                PluginThemeSpec(
                    ownerPluginId = contract.ownerPluginId,
                    id = contract.id,
                    mode = PluginThemeMode.valueOf(value.getString("mode")),
                    pureBlack = value.optBoolean("pure_black", false),
                    colors = value.optJSONObject("colors")?.let { objectValue ->
                        buildMap {
                            val keys = objectValue.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, objectValue.getString(key))
                            }
                        }
                    }.orEmpty(),
                    backgroundGradient = buildList {
                        val array = value.optJSONArray("background_gradient") ?: JSONArray()
                        for (index in 0 until array.length()) add(array.getString(index))
                    }
                )
        }
    )

    fun proxyableProtocols(): List<ExtensionProtocolType> = codecs.map { it.protocol }

    fun encode(record: PluginContributionRecord): JSONObject {
        if (record.kind != PluginContributionKind.EXTENSION) {
            throw PluginInstallException(
                "WORKER_EXTENSION_CONTRACT_INVALID",
                "Only canonical Extension records may enter Extension transport"
            )
        }
        val point = checkNotNull(record.extensionPoint)
        val apiVersion = checkNotNull(record.apiVersion)
        val codec = codecs.firstOrNull {
            it.protocol.point == point &&
                it.protocol.apiVersion == apiVersion &&
                it.accepts(record.payload)
        } ?: throw PluginInstallException(
            "WORKER_EXTENSION_NOT_PROXYABLE",
            "No Extension codec for " + point + "@" + apiVersion + " payload=" + (record.payload?.let { it::class.java.name } ?: "null")
        )
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("contract", PluginContributionContractCodec.encode(record.contract))
            .put("protocol", codec.protocol.wireName)
            .put("payload", codec.encode(record.payload))
    }

    fun decode(value: JSONObject): ExtensionContributionEnvelope {
        val schema = value.getInt("schema_version")
        if (schema != SCHEMA_VERSION) {
            throw PluginInstallException(
                "PLUGIN_WORKER_EXTENSION_SCHEMA_UNSUPPORTED",
                "Unsupported Extension transport schema: " + schema
            )
        }
        val contract = PluginContributionContractCodec.decode(value.getJSONObject("contract"))
        if (contract.kind != PluginContributionKind.EXTENSION) {
            throw PluginInstallException(
                "PLUGIN_WORKER_EXTENSION_CONTRACT_INVALID",
                "Extension transport carried a non-Extension canonical contract"
            )
        }
        val protocol = ExtensionProtocolType.fromWireName(value.getString("protocol"))
        if (contract.extensionPoint != protocol.point || contract.apiVersion != protocol.apiVersion) {
            throw PluginInstallException(
                "PLUGIN_WORKER_EXTENSION_PROTOCOL_MISMATCH",
                "Canonical Extension target does not match protocol " + protocol.wireName
            )
        }
        val codec = codecs.first { it.protocol == protocol }
        return ExtensionContributionEnvelope(
            contract = contract,
            protocol = protocol,
            payload = codec.decode(contract, value.getJSONObject("payload"))
        )
    }

    fun validateRecord(record: PluginContributionRecord) {
        encode(record)
    }
}
