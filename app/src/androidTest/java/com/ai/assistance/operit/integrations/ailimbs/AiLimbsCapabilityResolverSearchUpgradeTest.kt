package com.ai.assistance.operit.integrations.ailimbs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiLimbsCapabilityResolverSearchUpgradeTest {
    private val owner = "plugin.test.unknown_search_upgrade"
    private val id = "$owner.command"
    private val scopeId = "plugin:$owner"
    private fun resolver(): AiLimbsCapabilityResolver {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return AiLimbsCapabilityResolver(context, AiLimbsExecutionPolicyEngine(context,
            AiLimbsExecutionSession(transport = AiLimbsExecutionTransport.RDC, scopeId = "search-upgrade-regression")))
    }
    private fun register(description: String): AutoCloseable = AiLimbsCapabilityRegistry.registerPluginCapability(
        ownerPluginId = owner, capabilityId = id, invokeAliases = emptyList(),
        catalogEntry = ToolCatalogEntry(id, "Upgrade Workbench · Command", description, emptyList(),
            ToolCatalogSourceKind.PACKAGE, keywords = listOf("command", "工作台"), sourceName = "plugin:$owner"),
        effect = AiLimbsEffect.READ_ONLY, domain = AiLimbsDomain.PLUGIN, workContextRequiredReceipts = emptySet(),
        executor = AiLimbsPluginCapabilityExecutor { JSONObject().put("success", true) },
        ownerDisplayName = "Upgrade Workbench")

    @Test fun exactAndNamedRoutesReturnCanonicalNavigationWithoutPackageCollection() = runBlocking {
        val handle = register("Execute a command")
        val resolver = resolver()
        try {
            for ((query, mode) in listOf(id to "exact_identity", "Upgrade Workbench command" to "named_scope")) {
                val result = resolver.search(query, 5)
                assertEquals(mode, result.getString("search_mode"))
                assertEquals(id, result.getJSONArray("results").getJSONObject(0).getString("capability_id"))
                val ordered = result.getJSONArray("items").getJSONObject(0)
                assertEquals("capability", ordered.getString("kind"))
                assertEquals(1, ordered.getInt("rank"))
                assertEquals(id, ordered.getString("capability_id"))
                assertEquals(id, result.getJSONObject("next_action").getJSONObject("capability")
                    .getJSONObject("parameters").getString("capability_id"))
                assertFalse(result.getJSONObject("timings_ms").has("catalog_packages"))
                assertFalse(result.getBoolean("live_discovery"))
            }
        } finally { handle.close() }
    }

    @Test fun scopeQueriesReuseOnlyMetadataAndRemountShowsNewPurposeImmediately() = runBlocking {
        val resolver = resolver()
        val original = register("Old purpose")
        try {
            val first = resolver.search("command", 5, scopeId)
            assertEquals("scope", first.getString("search_mode"))
            assertFalse(first.getJSONObject("catalog").getBoolean("index_reused"))
            val repeated = resolver.search("command", 5, scopeId)
            assertTrue(repeated.getJSONObject("catalog").getBoolean("index_reused"))
        } finally { original.close() }
        val missing = resolver.search("command", 5, scopeId)
        assertEquals("CAPABILITY_SEARCH_SCOPE_UNKNOWN", missing.getString("error_code"))
        val replacement = register("New purpose")
        try {
            val updated = resolver.search("command", 5, scopeId)
            assertEquals("New purpose", updated.getJSONArray("results").getJSONObject(0).getString("purpose"))
            assertFalse(updated.getJSONObject("catalog").getBoolean("index_reused"))
        } finally { replacement.close() }
    }

    @Test fun compactPurposeKeepsUnicodeCodePointsWhenTruncated() = runBlocking {
        val handle = register("汉😀".repeat(100))
        try {
            val result = resolver().search(id, 1)
            val purpose = result.getJSONArray("results").getJSONObject(0).getString("purpose")
            assertEquals("汉😀".repeat(60) + "…", purpose)
            assertEquals(121, purpose.codePointCount(0, purpose.length))
        } finally { handle.close() }
    }
}
