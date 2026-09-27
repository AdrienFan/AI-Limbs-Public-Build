package com.ai.assistance.operit.integrations.ailimbs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiLimbsCapabilityResolverV2RegressionTest {
    private val ownerPluginId = "plugin.test.unknown_resolver_baseline"

    private fun resolver(): AiLimbsCapabilityResolver {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val session = AiLimbsExecutionSession(
            transport = AiLimbsExecutionTransport.RDC,
            scopeId = "resolver-v2-regression"
        )
        return AiLimbsCapabilityResolver(
            context,
            AiLimbsExecutionPolicyEngine(context, session)
        )
    }

    private fun registerCapability(
        capabilityId: String,
        displayName: String,
        keywords: List<String>,
        ownerId: String = ownerPluginId
    ): AutoCloseable {
        val entry = ToolCatalogEntry(
            targetToolName = capabilityId,
            displayName = displayName,
            description = "Resolver v2 regression capability for $displayName",
            parameterHints = listOf("text [string, optional]: regression payload"),
            sourceKind = ToolCatalogSourceKind.PACKAGE,
            keywords = keywords,
            parameters = listOf(
                ToolParameterSchema("text", "string", "regression payload", false)
            ),
            sourceName = "plugin:$ownerId",
            sourceLocator = "ai-limbs://plugin/$ownerId/$capabilityId",
            searchMetadata = listOf(ownerId, "resolver v2 baseline")
        )
        return AiLimbsCapabilityRegistry.registerPluginCapability(
            ownerPluginId = ownerId,
            capabilityId = capabilityId,
            invokeAliases = emptyList(),
            catalogEntry = entry,
            effect = AiLimbsEffect.READ_ONLY,
            domain = AiLimbsDomain.PLUGIN,
            workContextRequiredReceipts = emptySet(),
            executor = AiLimbsPluginCapabilityExecutor { args ->
                JSONObject().put("echo", args.optString("text"))
            }
        )
    }

    @Test
    fun searchDescribeAndUnmount_preserveV2Contract() = runBlocking {
        val capabilityId = "plugin.test.unknown_resolver_baseline.image.create"
        val handle = registerCapability(
            capabilityId,
            "新建测试图片",
            listOf("新建图片", "画布", "resolver regression")
        )
        val resolver = resolver()
        try {
            val search = resolver.search(capabilityId, 8)
            assertEquals(3, search.getInt("protocol_version"))
            assertEquals(capabilityId, search.getString("query"))
            assertFalse(search.getBoolean("live_discovery"))
            assertEquals(
                "capability.describe",
                search.getJSONObject("next_action")
                    .getJSONObject("capability")
                    .getString("name")
            )
            val results = search.getJSONArray("results")
            val card = (0 until results.length())
                .map { results.getJSONObject(it) }
                .first { it.getString("capability_id") == capabilityId }
            assertEquals(capabilityId, card.getString("invoke_id"))

            val described = resolver.describe(capabilityId)
            assertTrue(described.getBoolean("success"))
            assertEquals(3, described.getInt("protocol_version"))
            assertEquals(capabilityId, described.getString("capability_id"))
            assertEquals(capabilityId, described.getString("invoke_id"))
            assertEquals(
                card.getString("policy_outcome"),
                described.getJSONObject("policy").getString("outcome")
            )
            assertEquals(card.getString("availability"), described.getString("availability"))
            assertEquals(
                "text",
                described.getJSONArray("parameters").getJSONObject(0).getString("name")
            )
        } finally {
            handle.close()
        }

        val afterUnmountResponse = resolver.search(capabilityId, 8)
        val afterUnmount = afterUnmountResponse.getJSONArray("results")
        assertFalse(
            (0 until afterUnmount.length())
                .map { afterUnmount.getJSONObject(it) }
                .any { it.getString("capability_id") == capabilityId }
        )
        assertEquals(
            "capability.search",
            afterUnmountResponse.getJSONObject("next_action")
                .getJSONObject("capability")
                .getString("name")
        )
    }

    @Test
    fun limitAndLowConfidenceLiveDiscovery_preserveV2Contract() = runBlocking {
        val handles = (1..4).map { index ->
            registerCapability(
                capabilityId = "plugin.test.unknown_resolver_baseline.limit_$index",
                displayName = "基线限额能力 $index",
                keywords = listOf("基线限额", "resolver regression"),
                ownerId = "plugin.test.unknown_resolver_limit_$index"
            )
        }
        val resolver = resolver()
        try {
            val limited = resolver.search("基线限额", 2)
            assertEquals(2, limited.getInt("count"))
            assertEquals(2, limited.getJSONArray("results").length())
        } finally {
            handles.forEach(AutoCloseable::close)
        }

        val lowConfidence = resolver.search(
            "zzzzzzzz qqqqqqqq no_such_capability",
            8
        )
        assertTrue(lowConfidence.getBoolean("live_discovery"))
    }
}
