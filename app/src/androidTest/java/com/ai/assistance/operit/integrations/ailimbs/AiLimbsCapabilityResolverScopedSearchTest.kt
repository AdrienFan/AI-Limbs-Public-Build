package com.ai.assistance.operit.integrations.ailimbs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiLimbsCapabilityResolverScopedSearchTest {
    private fun resolver(): AiLimbsCapabilityResolver {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val session =
            AiLimbsExecutionSession(
                transport = AiLimbsExecutionTransport.RDC,
                scopeId = "resolver-scoped-search-regression"
            )
        return AiLimbsCapabilityResolver(
            context,
            AiLimbsExecutionPolicyEngine(context, session)
        )
    }

    private fun registerCapability(
        ownerPluginId: String,
        capabilityId: String,
        displayName: String
    ): AutoCloseable {
        val queryToken = "scope-isolation-regression-token"
        val entry =
            ToolCatalogEntry(
                targetToolName = capabilityId,
                displayName = displayName,
                description = "Scoped Resolver regression capability",
                parameterHints = emptyList(),
                sourceKind = ToolCatalogSourceKind.PACKAGE,
                keywords = listOf(queryToken, "作用域搜索回归"),
                sourceName = "plugin:$ownerPluginId",
                sourceLocator = "ai-limbs://plugin/$ownerPluginId/$capabilityId",
                searchMetadata = listOf(queryToken, ownerPluginId)
            )
        return AiLimbsCapabilityRegistry.registerPluginCapability(
            ownerPluginId = ownerPluginId,
            capabilityId = capabilityId,
            invokeAliases = emptyList(),
            catalogEntry = entry,
            effect = AiLimbsEffect.READ_ONLY,
            domain = AiLimbsDomain.PLUGIN,
            workContextRequiredReceipts = emptySet(),
            executor = AiLimbsPluginCapabilityExecutor {
                JSONObject().put("success", true)
            },
            ownerDisplayName = displayName.substringBefore(" ·")
        )
    }

    @Test
    fun scopedSearchFiltersByCurrentOwnerBeforeReusingCatalogSearch() = runBlocking {
        val ownerA = "plugin.test.unknown_scope_a"
        val ownerB = "plugin.test.unknown_scope_b"
        val capabilityA = "plugin.test.unknown_scope_a.image.create"
        val capabilityB = "plugin.test.unknown_scope_b.image.create"
        val handles =
            listOf(
                registerCapability(ownerA, capabilityA, "未知插件 A · 创建图片"),
                registerCapability(ownerB, capabilityB, "未知插件 B · 创建图片")
            )
        val resolver = resolver()

        try {
            val global = resolver.search("scope-isolation-regression-token", 8)
            assertEquals(2, global.getInt("protocol_version"))
            assertFalse(global.has("scope"))
            val globalIds =
                (0 until global.getJSONArray("results").length())
                    .map {
                        global.getJSONArray("results")
                            .getJSONObject(it)
                            .getString("capability_id")
                    }
                    .toSet()
            assertTrue(capabilityA in globalIds)
            assertTrue(capabilityB in globalIds)

            val scopeId = "plugin:$ownerA"
            val scoped =
                resolver.search(
                    query = "scope-isolation-regression-token",
                    requestedLimit = 8,
                    scope = scopeId
                )
            assertEquals(2, scoped.getInt("protocol_version"))
            assertEquals(scopeId, scoped.getString("scope"))
            val scopedIds =
                (0 until scoped.getJSONArray("results").length())
                    .map {
                        scoped.getJSONArray("results")
                            .getJSONObject(it)
                            .getString("capability_id")
                    }
                    .toSet()
            assertTrue(capabilityA in scopedIds)
            assertFalse(capabilityB in scopedIds)
            assertTrue(
                scopedIds.all { capabilityId ->
                    AiLimbsCapabilityRegistry
                        .pluginRegistrationForInvokeName(capabilityId)
                        ?.ownerPluginId == ownerA
                }
            )

            val lowConfidenceScoped =
                resolver.search(
                    query = "zzzzzzzz qqqqqqqq scoped-no-such-capability",
                    requestedLimit = 8,
                    scope = scopeId
                )
            assertTrue(lowConfidenceScoped.getBoolean("live_discovery"))
            assertEquals(scopeId, lowConfidenceScoped.getString("scope"))
            val lowConfidenceIds =
                (0 until lowConfidenceScoped.getJSONArray("results").length())
                    .map {
                        lowConfidenceScoped.getJSONArray("results")
                            .getJSONObject(it)
                            .getString("capability_id")
                    }
            assertTrue(
                lowConfidenceIds.all { capabilityId ->
                    AiLimbsCapabilityRegistry
                        .pluginRegistrationForInvokeName(capabilityId)
                        ?.ownerPluginId == ownerA
                }
            )

            val unknown =
                resolver.search(
                    query = "scope-isolation-regression-token",
                    requestedLimit = 8,
                    scope = "plugin:plugin.test.scope_that_is_not_mounted"
                )
            assertFalse(unknown.getBoolean("success"))
            assertEquals(
                "CAPABILITY_SEARCH_SCOPE_UNKNOWN",
                unknown.getString("error_code")
            )
        } finally {
            handles.forEach(AutoCloseable::close)
        }
    }
}
