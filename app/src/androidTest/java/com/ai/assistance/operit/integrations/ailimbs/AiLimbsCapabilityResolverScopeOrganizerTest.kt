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
class AiLimbsCapabilityResolverScopeOrganizerTest {
    private val ownerPluginId = "plugin.test.unknown_scope_organizer"
    private val scopeDisplayName = "回归画室"

    private fun resolver(): AiLimbsCapabilityResolver {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val session =
            AiLimbsExecutionSession(
                transport = AiLimbsExecutionTransport.RDC,
                scopeId = "resolver-scope-organizer-regression"
            )
        return AiLimbsCapabilityResolver(
            context,
            AiLimbsExecutionPolicyEngine(context, session)
        )
    }

    private fun registerCapability(
        suffix: String,
        leafDisplayName: String,
        leafKeywords: List<String>
    ): AutoCloseable {
        val capabilityId = "$ownerPluginId.$suffix"
        val entry =
            ToolCatalogEntry(
                targetToolName = capabilityId,
                displayName = "$scopeDisplayName · $leafDisplayName",
                description = "Global Scope Organizer regression capability",
                parameterHints = emptyList(),
                sourceKind = ToolCatalogSourceKind.PACKAGE,
                keywords = listOf(scopeDisplayName) + leafKeywords,
                sourceName = "plugin:$ownerPluginId",
                sourceLocator = "ai-limbs://plugin/$ownerPluginId/$capabilityId",
                searchMetadata = listOf(ownerPluginId, scopeDisplayName)
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
            ownerDisplayName = scopeDisplayName,
            ownerDescription = "用于验证 Global Search Scope Organizer 的未知插件"
        )
    }

    @Test
    fun broadScopeIntentFoldsLeavesButSpecificIntentKeepsLeafWinner() = runBlocking {
        val handles =
            listOf(
                registerCapability("history.undo", "撤销上一步", listOf("撤销", "上一步")),
                registerCapability("document.save", "保存工程", listOf("保存", "工程")),
                registerCapability("document.open", "打开工程", listOf("打开", "工程")),
                registerCapability("image.crop", "裁剪图片", listOf("裁剪", "图片")),
                registerCapability("image.resize", "调整尺寸", listOf("尺寸", "图片"))
            )
        val resolver = resolver()
        val scopeId = "plugin:$ownerPluginId"

        try {
            val broad = resolver.search(scopeDisplayName, 5)
            assertEquals(3, broad.getInt("protocol_version"))
            assertEquals(1, broad.getInt("count"))
            assertEquals(0, broad.getInt("capability_count"))
            assertEquals(1, broad.getInt("scope_count"))
            assertEquals(0, broad.getJSONArray("results").length())
            val scopeResults = broad.getJSONArray("scope_results")
            assertEquals(1, scopeResults.length())
            val scope = scopeResults.getJSONObject(0)
            assertEquals(scopeId, scope.getString("scope_id"))
            assertEquals(scopeDisplayName, scope.getString("display_name"))
            assertEquals(5, scope.getInt("capability_count"))
            assertEquals(
                scopeId,
                scope.getJSONObject("next_action")
                    .getJSONObject("capability")
                    .getJSONObject("parameters")
                    .getString("scope")
            )

            val specific = resolver.search("$scopeDisplayName 撤销 上一步", 5)
            assertEquals(3, specific.getInt("protocol_version"))
            val specificIds =
                (0 until specific.getJSONArray("results").length())
                    .map {
                        specific.getJSONArray("results")
                            .getJSONObject(it)
                            .getString("capability_id")
                    }
            assertTrue("$ownerPluginId.history.undo" in specificIds)
            assertFalse(
                (0 until specific.getJSONArray("scope_results").length())
                    .map {
                        specific.getJSONArray("scope_results")
                            .getJSONObject(it)
                            .getString("scope_id")
                    }
                    .contains(scopeId)
            )
        } finally {
            handles.forEach(AutoCloseable::close)
        }
    }
}
