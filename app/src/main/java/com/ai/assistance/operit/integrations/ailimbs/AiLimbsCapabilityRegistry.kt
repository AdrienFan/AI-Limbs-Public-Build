package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry

internal fun isReservedPluginCapabilityName(name: String): Boolean =
    name.trim().startsWith("plugin.")

internal sealed interface AiLimbsCapabilityRegistration {
    val catalogEntry: ToolCatalogEntry

    data class Core(
        val registration: AiLimbsCoreCapabilityRegistration
    ) : AiLimbsCapabilityRegistration {
        override val catalogEntry: ToolCatalogEntry = registration.catalogEntry
    }
}

internal sealed interface AiLimbsCapabilityRoute {
    data class Core(
        val registration: AiLimbsCoreCapabilityRegistration
    ) : AiLimbsCapabilityRoute

    data class HostTool(
        val targetName: String
    ) : AiLimbsCapabilityRoute
}

/**
 * Stable-kernel registry view while the incomplete V0.7.1 Plugin Center is absent.
 * Dynamic plugin contributions are intentionally disconnected here and will be
 * reintroduced only through Plugin Center v2's versioned Host contracts.
 */
object AiLimbsCapabilityRegistry {
    internal fun registrationForInvokeName(name: String): AiLimbsCapabilityRegistration? =
        AiLimbsCoreCapabilityRegistry.registrationForInvokeName(name)
            ?.let { AiLimbsCapabilityRegistration.Core(it) }

    internal fun isRegisteredInvokeName(name: String): Boolean =
        registrationForInvokeName(name) != null

    internal fun mergeInto(runtimeCatalog: List<ToolCatalogEntry>): List<ToolCatalogEntry> =
        AiLimbsCoreCapabilityRegistry.mergeInto(runtimeCatalog)
}
