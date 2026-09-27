package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCapabilityCatalog
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsDynamicCapabilityRegistryTest {
    @Test
    fun mountedCapabilityIsSearchableDescribableAndRemovedOnClose() {
        val canonical = "plugin.dynamic_bridge.echo"
        val alias = "plugin.dynamic_bridge.say"
        val entry = ToolCatalogEntry(
            targetToolName = canonical,
            displayName = "Dynamic Bridge Echo",
            description = "Echo text through a dynamically mounted Bridge child capability.",
            parameterHints = listOf("text [string, required]: text to echo"),
            sourceKind = ToolCatalogSourceKind.PACKAGE,
            keywords = listOf("dynamic bridge", "echo", "动态桥接"),
            parameters = listOf(ToolParameterSchema("text", "string", "text to echo", true)),
            sourceName = "plugin:test.dynamic_bridge",
            sourceLocator = "ai-limbs://plugin/test.dynamic_bridge/$canonical",
            searchMetadata = listOf(alias, "bridge child capability")
        )
        val handle = AiLimbsCapabilityRegistry.registerPluginCapability(
            ownerPluginId = "test.dynamic_bridge",
            capabilityId = canonical,
            invokeAliases = listOf(alias),
            catalogEntry = entry,
            effect = AiLimbsEffect.EXTERNAL_COMMUNICATION,
            domain = AiLimbsDomain.PLUGIN,
            workContextRequiredReceipts = setOf(AiLimbsRequiredReceipt.WORK_MANUAL),
            executor = AiLimbsPluginCapabilityExecutor { parameters ->
                JSONObject().put("echo", parameters.optString("text"))
            }
        )

        try {
            val canonicalRegistration =
                AiLimbsCapabilityRegistry.pluginRegistrationForInvokeName(canonical)
            val aliasRegistration =
                AiLimbsCapabilityRegistry.pluginRegistrationForInvokeName(alias)
            assertTrue(canonicalRegistration != null)
            assertSame(canonicalRegistration, aliasRegistration)
            assertEquals(AiLimbsEffect.EXTERNAL_COMMUNICATION, canonicalRegistration?.effect)
            assertEquals(AiLimbsDomain.PLUGIN, canonicalRegistration?.domain)
            assertEquals(
                setOf(AiLimbsRequiredReceipt.WORK_MANUAL),
                canonicalRegistration?.workContextRequiredReceipts
            )

            val merged = AiLimbsCapabilityRegistry.mergeInto(emptyList())
            assertTrue(merged.any { it.targetToolName == canonical })
            val search = ToolCapabilityCatalog.search(merged, "dynamic bridge echo", 5)
            assertTrue(search.any { it.targetToolName == canonical })
        } finally {
            handle.close()
        }

        assertNull(AiLimbsCapabilityRegistry.pluginRegistrationForInvokeName(canonical))
        assertNull(AiLimbsCapabilityRegistry.pluginRegistrationForInvokeName(alias))
        assertFalse(
            AiLimbsCapabilityRegistry.mergeInto(emptyList())
                .any { it.targetToolName == canonical }
        )
    }

    @Test
    fun capabilityScopesAreDerivedFromCurrentOwnerRegistrations() {
        val owner = "plugin.test.unknown_scope"
        val firstId = "plugin.test.unknown_scope.create"
        val secondId = "plugin.test.unknown_scope.save"

        fun entry(id: String, displayName: String) =
            ToolCatalogEntry(
                targetToolName = id,
                displayName = displayName,
                description = "Scope regression capability",
                parameterHints = emptyList(),
                sourceKind = ToolCatalogSourceKind.PACKAGE,
                sourceName = "plugin:$owner",
                sourceLocator = "ai-limbs://plugin/$owner/$id"
            )

        val first =
            AiLimbsCapabilityRegistry.registerPluginCapability(
                ownerPluginId = owner,
                capabilityId = firstId,
                invokeAliases = listOf("plugin.test.unknown_scope.new"),
                catalogEntry = entry(firstId, "Create"),
                effect = AiLimbsEffect.STATE_CHANGE,
                domain = AiLimbsDomain.PLUGIN,
                workContextRequiredReceipts = emptySet(),
                executor = AiLimbsPluginCapabilityExecutor { JSONObject().put("success", true) },
                ownerDisplayName = "测试画室",
                ownerDescription = "用于验证动态 CapabilityScope 的未知插件"
            )
        val second =
            AiLimbsCapabilityRegistry.registerPluginCapability(
                ownerPluginId = owner,
                capabilityId = secondId,
                invokeAliases = emptyList(),
                catalogEntry = entry(secondId, "Save"),
                effect = AiLimbsEffect.PERSISTENT_WRITE,
                domain = AiLimbsDomain.PLUGIN,
                workContextRequiredReceipts = emptySet(),
                executor = AiLimbsPluginCapabilityExecutor { JSONObject().put("success", true) },
                ownerDisplayName = "测试画室",
                ownerDescription = "用于验证动态 CapabilityScope 的未知插件"
            )

        try {
            val scope =
                AiLimbsCapabilityRegistry.capabilityScopeSnapshot()
                    .single { it.ownerPluginId == owner }
            assertEquals("plugin:$owner", scope.scopeId)
            assertEquals(AiLimbsCapabilityScopeKind.PLUGIN, scope.kind)
            assertEquals("测试画室", scope.displayName)
            assertEquals("用于验证动态 CapabilityScope 的未知插件", scope.description)
            assertEquals(2, scope.capabilityCount)
            assertEquals(listOf(firstId, secondId), scope.capabilityIds)
            assertEquals(listOf(firstId, secondId), scope.invokeIds)

            first.close()
            val afterFirstClose =
                AiLimbsCapabilityRegistry.capabilityScopeSnapshot()
                    .single { it.ownerPluginId == owner }
            assertEquals(1, afterFirstClose.capabilityCount)
            assertEquals(listOf(secondId), afterFirstClose.capabilityIds)
        } finally {
            first.close()
            second.close()
        }

        assertFalse(
            AiLimbsCapabilityRegistry.capabilityScopeSnapshot()
                .any { it.ownerPluginId == owner }
        )
    }

}
