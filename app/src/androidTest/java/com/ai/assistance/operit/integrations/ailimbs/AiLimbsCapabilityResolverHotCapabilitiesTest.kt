package com.ai.assistance.operit.integrations.ailimbs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiLimbsCapabilityResolverHotCapabilitiesTest {
    private val ownerPluginId = "plugin.test.unknown_hot_cycle"
    private val capabilityId = "$ownerPluginId.echo"

    private fun context() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun resolver(): AiLimbsCapabilityResolver {
        val context = context()
        val session =
            AiLimbsExecutionSession(
                transport = AiLimbsExecutionTransport.RDC,
                scopeId = "resolver-hot-capabilities-regression"
            )
        return AiLimbsCapabilityResolver(
            context,
            AiLimbsExecutionPolicyEngine(context, session)
        )
    }

    private fun registerCapability(): AutoCloseable {
        val entry =
            ToolCatalogEntry(
                targetToolName = capabilityId,
                displayName = "热度周期回归能力",
                description = "Hot capability interaction cycle regression",
                parameterHints = emptyList(),
                sourceKind = ToolCatalogSourceKind.PACKAGE,
                keywords = listOf("热度周期回归"),
                sourceName = "plugin:$ownerPluginId",
                sourceLocator = "ai-limbs://plugin/$ownerPluginId/$capabilityId"
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
            ownerDisplayName = "热度周期回归插件"
        )
    }

    @Test
    fun scopedSearchDoesNotConsumeFirstGlobalHotCapabilityDelivery() = runBlocking {
        val handle = registerCapability()
        val resolver = resolver()
        val context = context()

        try {
            AiLimbsInteractionCycleRuntime.reset(context)

            val scoped =
                resolver.search(
                    query = "热度周期回归",
                    requestedLimit = 5,
                    scope = "plugin:$ownerPluginId"
                )
            assertFalse(scoped.has("hot_capabilities"))

            val firstGlobal = resolver.search("热度周期回归", 5)
            assertTrue(firstGlobal.has("hot_capabilities"))

            val secondGlobal = resolver.search("热度周期回归", 5)
            assertFalse(secondGlobal.has("hot_capabilities"))

            AiLimbsInteractionCycleRuntime.reset(context)
            val nextGenerationGlobal = resolver.search("热度周期回归", 5)
            assertTrue(nextGenerationGlobal.has("hot_capabilities"))
        } finally {
            handle.close()
        }
    }
}
