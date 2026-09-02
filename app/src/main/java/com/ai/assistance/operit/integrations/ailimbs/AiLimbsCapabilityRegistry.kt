package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal fun isReservedPluginCapabilityName(name: String): Boolean =
    name.trim().lowercase().startsWith("plugin.")

internal fun interface AiLimbsPluginCapabilityExecutor {
    suspend fun execute(parameters: JSONObject): JSONObject
}

internal data class AiLimbsPluginCapabilityRegistration(
    val ownerPluginId: String,
    val capabilityId: String,
    val invokeAliases: List<String>,
    val catalogEntry: ToolCatalogEntry,
    val executor: AiLimbsPluginCapabilityExecutor
)

internal sealed interface AiLimbsCapabilityRegistration {
    val catalogEntry: ToolCatalogEntry

    data class Core(
        val registration: AiLimbsCoreCapabilityRegistration
    ) : AiLimbsCapabilityRegistration {
        override val catalogEntry: ToolCatalogEntry = registration.catalogEntry
    }

    data class Plugin(
        val registration: AiLimbsPluginCapabilityRegistration
    ) : AiLimbsCapabilityRegistration {
        override val catalogEntry: ToolCatalogEntry = registration.catalogEntry
    }
}

internal sealed interface AiLimbsCapabilityRoute {
    data class Core(
        val registration: AiLimbsCoreCapabilityRegistration
    ) : AiLimbsCapabilityRoute

    data class Plugin(
        val registration: AiLimbsPluginCapabilityRegistration
    ) : AiLimbsCapabilityRoute

    data class HostTool(
        val targetName: String
    ) : AiLimbsCapabilityRoute
}

/** Unified stable-kernel registry for Core and mounted plugin capabilities. */
object AiLimbsCapabilityRegistry {
    private data class OwnedPluginRegistration(
        val token: String,
        val registration: AiLimbsPluginCapabilityRegistration
    )

    private val lock = Any()
    private val pluginByInvokeName = ConcurrentHashMap<String, OwnedPluginRegistration>()

    internal fun registerPluginCapability(
        ownerPluginId: String,
        capabilityId: String,
        invokeAliases: List<String>,
        catalogEntry: ToolCatalogEntry,
        executor: AiLimbsPluginCapabilityExecutor
    ): AutoCloseable {
        val canonical = normalize(catalogEntry.targetToolName)
        val normalizedCapabilityId = normalize(capabilityId)
        val aliases = invokeAliases.map(::normalize).filter { it != canonical }.distinct()
        val names = (listOf(canonical) + aliases).distinct()
        requirePluginNamespace(normalizedCapabilityId)
        names.forEach(::requirePluginNamespace)
        val registration = AiLimbsPluginCapabilityRegistration(
            ownerPluginId = ownerPluginId,
            capabilityId = normalizedCapabilityId,
            invokeAliases = aliases,
            catalogEntry = catalogEntry.copy(targetToolName = canonical),
            executor = executor
        )
        val owned = OwnedPluginRegistration(UUID.randomUUID().toString(), registration)
        synchronized(lock) {
            names.forEach { name ->
                check(!AiLimbsCoreCapabilityRegistry.isRegisteredInvokeName(name)) {
                    "Plugin capability conflicts with Core invoke name: $name"
                }
                check(pluginByInvokeName[name] == null) {
                    "Plugin capability invoke name is already registered: $name"
                }
            }
            names.forEach { pluginByInvokeName[it] = owned }
        }
        return AutoCloseable {
            synchronized(lock) {
                names.forEach { name ->
                    if (pluginByInvokeName[name]?.token == owned.token) {
                        pluginByInvokeName.remove(name)
                    }
                }
            }
        }
    }

    internal fun registrationForInvokeName(name: String): AiLimbsCapabilityRegistration? {
        val normalized = normalize(name)
        return AiLimbsCoreCapabilityRegistry.registrationForInvokeName(normalized)
            ?.let { AiLimbsCapabilityRegistration.Core(it) }
            ?: pluginByInvokeName[normalized]?.registration
                ?.let { AiLimbsCapabilityRegistration.Plugin(it) }
    }

    internal fun isRegisteredInvokeName(name: String): Boolean =
        registrationForInvokeName(name) != null

    internal fun pluginRegistrationForInvokeName(name: String): AiLimbsPluginCapabilityRegistration? =
        pluginByInvokeName[normalize(name)]?.registration

    internal fun mergeInto(runtimeCatalog: List<ToolCatalogEntry>): List<ToolCatalogEntry> {
        val coreMerged = AiLimbsCoreCapabilityRegistry.mergeInto(runtimeCatalog)
        val existingNames = coreMerged.mapTo(linkedSetOf()) { normalize(it.targetToolName) }
        val pluginEntries = pluginByInvokeName.values
            .distinctBy { it.token }
            .map { it.registration.catalogEntry }
            .sortedBy { it.targetToolName }
            .filter { normalize(it.targetToolName) !in existingNames }
        return coreMerged + pluginEntries
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private fun requirePluginNamespace(value: String) {
        require(isReservedPluginCapabilityName(value) && PLUGIN_NAME.matches(value)) {
            "Plugin capability invoke names must use plugin.* namespace: $value"
        }
    }

    private val PLUGIN_NAME = Regex("^plugin\\.[a-z0-9]+(?:[._-][a-z0-9]+)*$")
}
