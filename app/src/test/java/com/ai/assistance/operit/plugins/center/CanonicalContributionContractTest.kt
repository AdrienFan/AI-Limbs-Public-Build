package com.ai.assistance.operit.plugins.center

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalContributionContractTest {
    @Test
    fun `provider contract round trips through canonical codec`() {
        val contract = CanonicalContributionContracts.provider(
            ownerPluginId = " plugin.synthetic.owner ",
            id = " provider.synthetic ",
            metadata = mapOf("z" to "last", "a" to "first")
        )

        val encoded = PluginContributionContractCodec.encode(contract)
        val decoded = PluginContributionContractCodec.decode(JSONObject(encoded.toString()))

        assertEquals(PluginContributionContractCodec.SCHEMA_VERSION, encoded.getInt("schema_version"))
        assertEquals("provider", encoded.getString("kind"))
        assertEquals("provider.binding", encoded.getString("contract_type"))
        assertEquals("plugin.synthetic.owner", encoded.getString("owner"))
        assertEquals("provider.synthetic", encoded.getString("id"))
        assertTrue(encoded.isNull("api_version"))
        assertTrue(encoded.isNull("extension_point"))
        assertEquals(contract, decoded)
    }

    @Test
    fun `extension contract canonicalizes point and preserves declared api`() {
        val contract = CanonicalContributionContracts.extension(
            ownerPluginId = "plugin.synthetic.owner",
            point = " UI.SYNTHETIC.SLOT ",
            id = " extension.synthetic ",
            apiVersion = 3,
            metadata = mapOf("role" to "test")
        )

        assertEquals(PluginContributionKind.EXTENSION, contract.kind)
        assertEquals(PluginContributionContractType.EXTENSION_BINDING, contract.contractType)
        assertEquals("ui.synthetic.slot", contract.extensionPoint)
        assertEquals("extension.synthetic", contract.id)
        assertEquals(3, contract.apiVersion)

        val record = PluginContributionRecord(contract = contract, payload = "payload")
        assertSame(contract, record.contract)
        assertEquals(contract.ownerPluginId, record.ownerPluginId)
        assertEquals(contract.extensionPoint, record.extensionPoint)
        assertEquals(contract.metadata, record.metadata)
    }

    @Test
    fun `contract type cannot disagree with contribution kind`() {
        val encoded = JSONObject()
            .put("schema_version", PluginContributionContractCodec.SCHEMA_VERSION)
            .put("kind", "provider")
            .put("contract_type", "service.rpc")
            .put("owner", "plugin.synthetic.owner")
            .put("id", "provider.synthetic")
            .put("api_version", JSONObject.NULL)
            .put("extension_point", JSONObject.NULL)
            .put("metadata", JSONObject())

        val failure = runCatching { PluginContributionContractCodec.decode(encoded) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `capability and provider do not invent api versions`() {
        val capability = CanonicalContributionContracts.capability(
            "plugin.synthetic.owner",
            "capability.synthetic"
        )
        val provider = CanonicalContributionContracts.provider(
            "plugin.synthetic.owner",
            "provider.synthetic"
        )

        assertNull(capability.apiVersion)
        assertNull(provider.apiVersion)
        assertEquals(PluginContributionContractType.CAPABILITY_EXECUTION, capability.contractType)
        assertEquals(PluginContributionContractType.PROVIDER_BINDING, provider.contractType)
    }
}
