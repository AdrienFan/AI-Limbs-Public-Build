package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiLimbsCapabilityUsagePolicyTest {
    @Test
    fun pluginAlias_isRecordedUnderCanonicalInvokeId() {
        val registration = pluginRegistration("plugin.test.business.echo")
        val invocation =
            invocation(
                requestedName = "plugin.test.business.say",
                canonicalName = "plugin.test.business.echo",
                route = AiLimbsCapabilityRoute.Plugin(registration)
            )

        assertEquals(
            "plugin.test.business.echo",
            AiLimbsCapabilityUsagePolicy.trackedInvokeId(
                invocation,
                JSONObject().put("value", "ok")
            )
        )
    }

    @Test
    fun hostToolWrapper_isRecordedUnderRealTargetName() {
        val invocation =
            invocation(
                requestedName = "ai_limbs.host_tool.execute",
                canonicalName = "ai_limbs.host_tool.execute",
                targetName = "execute_shell",
                route = AiLimbsCapabilityRoute.HostTool("execute_shell")
            )

        assertEquals(
            "execute_shell",
            AiLimbsCapabilityUsagePolicy.trackedInvokeId(
                invocation,
                JSONObject().put("success", true)
            )
        )
    }

    @Test
    fun explicitFailure_isNotRecorded() {
        val registration = pluginRegistration("plugin.test.business.echo")
        val invocation =
            invocation(
                requestedName = registration.capabilityId,
                canonicalName = registration.capabilityId,
                route = AiLimbsCapabilityRoute.Plugin(registration)
            )

        assertNull(
            AiLimbsCapabilityUsagePolicy.trackedInvokeId(
                invocation,
                JSONObject().put("success", false)
            )
        )
    }

    @Test
    fun coreControlPlane_isNotRecorded() {
        val registration =
            requireNotNull(
                AiLimbsCoreCapabilityRegistry.registrationForInvokeName("capability.search")
            )
        val invocation =
            invocation(
                requestedName = "capability.search",
                canonicalName = "capability.search",
                route = AiLimbsCapabilityRoute.Core(registration)
            )

        assertNull(
            AiLimbsCapabilityUsagePolicy.trackedInvokeId(
                invocation,
                JSONObject().put("success", true)
            )
        )
    }

    @Test
    fun forwardedBridgeControlAction_isNotRecorded() {
        val invocation =
            invocation(
                requestedName = "ai_limbs.bridge.reconnect",
                canonicalName = "ai_limbs.bridge.reconnect",
                route = AiLimbsCapabilityRoute.HostTool("ai_limbs.bridge.reconnect")
            )

        assertNull(
            AiLimbsCapabilityUsagePolicy.trackedInvokeId(
                invocation,
                JSONObject().put("success", true)
            )
        )
    }

    private fun pluginRegistration(capabilityId: String) =
        AiLimbsPluginCapabilityRegistration(
            ownerPluginId = "plugin.test.business",
            capabilityId = capabilityId,
            invokeAliases = listOf("plugin.test.business.say"),
            catalogEntry =
                ToolCatalogEntry(
                    targetToolName = capabilityId,
                    displayName = "Test business capability",
                    description = "Test capability",
                    parameterHints = emptyList(),
                    sourceKind = ToolCatalogSourceKind.PACKAGE
                ),
            effect = AiLimbsEffect.READ_ONLY,
            domain = AiLimbsDomain.PLUGIN,
            workContextRequiredReceipts = emptySet(),
            executor = AiLimbsPluginCapabilityExecutor { JSONObject().put("success", true) }
        )

    private fun invocation(
        requestedName: String,
        canonicalName: String,
        targetName: String = canonicalName,
        route: AiLimbsCapabilityRoute
    ) =
        AiLimbsNormalizedInvocation(
            requestedName = requestedName,
            canonicalName = canonicalName,
            targetName = targetName,
            parameters = JSONObject(),
            route = route,
            sourceEnabled = true,
            spec =
                AiLimbsPolicySpec(
                    effect = AiLimbsEffect.READ_ONLY,
                    domain = AiLimbsDomain.PLUGIN,
                    permissionMode = AiLimbsPermissionMode.PROTOCOL_ALLOW,
                    requiredReceipts = emptySet(),
                    hostPermissionEnforced = false
                )
        )
}
