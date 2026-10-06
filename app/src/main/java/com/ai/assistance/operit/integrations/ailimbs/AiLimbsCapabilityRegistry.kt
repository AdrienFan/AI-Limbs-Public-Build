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
    val effect: AiLimbsEffect,
    val domain: AiLimbsDomain,
    val workContextRequiredReceipts: Set<AiLimbsRequiredReceipt>,
    val executor: AiLimbsPluginCapabilityExecutor,
    val ownerDisplayName: String = ownerPluginId,
    val ownerDescription: String? = null,
    val role: AiLimbsCapabilityRole = AiLimbsCapabilityRole.BUSINESS
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

internal enum class AiLimbsCapabilityScopeKind(val wireName: String) {
    PLUGIN("plugin")
}

internal data class AiLimbsCapabilityScope(
    val scopeId: String,
    val kind: AiLimbsCapabilityScopeKind,
    val ownerPluginId: String,
    val displayName: String,
    val description: String?,
    val capabilityIds: List<String>,
    val invokeIds: List<String>
) {
    val capabilityCount: Int
        get() = capabilityIds.size
}

/** Unified stable-kernel registry for Core and mounted plugin capabilities. */
object AiLimbsCapabilityRegistry {
    private data class OwnedPluginRegistration(
        val token: String,
        val registration: AiLimbsPluginCapabilityRegistration
    )

    @Volatile private var discoveryRevision = 0L
    internal fun metadataRevision(): Long = discoveryRevision

    private val lock = Any()
    private val pluginByInvokeName = ConcurrentHashMap<String, OwnedPluginRegistration>()

    internal fun registerPluginCapability(
        ownerPluginId: String,
        capabilityId: String,
        invokeAliases: List<String>,
        catalogEntry: ToolCatalogEntry,
        effect: AiLimbsEffect,
        domain: AiLimbsDomain,
        workContextRequiredReceipts: Set<AiLimbsRequiredReceipt>,
        executor: AiLimbsPluginCapabilityExecutor,
        ownerDisplayName: String = ownerPluginId,
        ownerDescription: String? = null
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
            effect = effect,
            domain = domain,
            workContextRequiredReceipts = workContextRequiredReceipts,
            executor = executor,
            ownerDisplayName = ownerDisplayName.trim().ifBlank { ownerPluginId },
            ownerDescription = ownerDescription?.trim()?.ifBlank { null },
            role = AiLimbsCapabilityRolePolicy.forPlugin(
                capabilityId = normalizedCapabilityId,
                effect = effect
            )
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
            discoveryRevision += 1
        }
        return AutoCloseable {
            synchronized(lock) {
                var removed = false
                names.forEach { name ->
                    if (pluginByInvokeName[name]?.token == owned.token) {
                        pluginByInvokeName.remove(name)
                        removed = true
                    }
                }
                if (removed) discoveryRevision += 1
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

    internal fun capabilityScopeSnapshot(): List<AiLimbsCapabilityScope> {
        val registrations =
            pluginByInvokeName.values
                .distinctBy { it.token }
                .map { it.registration }
                .sortedBy { it.capabilityId }

        return registrations
            .groupBy { it.ownerPluginId }
            .map { (ownerPluginId, owned) ->
                val displayName =
                    owned.asSequence()
                        .map { it.ownerDisplayName.trim() }
                        .firstOrNull {
                            it.isNotBlank() && !it.equals(ownerPluginId, ignoreCase = true)
                        }
                        ?: owned.asSequence()
                            .map { it.ownerDisplayName.trim() }
                            .firstOrNull { it.isNotBlank() }
                        ?: ownerPluginId
                val description =
                    owned.asSequence()
                        .mapNotNull { it.ownerDescription?.trim()?.ifBlank { null } }
                        .firstOrNull()
                AiLimbsCapabilityScope(
                    scopeId = "plugin:$ownerPluginId",
                    kind = AiLimbsCapabilityScopeKind.PLUGIN,
                    ownerPluginId = ownerPluginId,
                    displayName = displayName,
                    description = description,
                    capabilityIds = owned.map { it.capabilityId }.distinct().sorted(),
                    invokeIds = owned.map { it.catalogEntry.targetToolName }.distinct().sorted()
                )
            }
            .sortedBy { it.scopeId }
    }

    internal fun mergeInto(runtimeCatalog: List<ToolCatalogEntry>): List<ToolCatalogEntry> {
        val coreMerged = AiLimbsCoreCapabilityRegistry.mergeInto(runtimeCatalog)
        val pluginEntries = pluginByInvokeName.values
            .distinctBy { it.token }
            .map { it.registration.catalogEntry }
            .sortedBy { it.targetToolName }
        // plugin.* discovery belongs to the live canonical registry. Raw runtime stubs must not
        // override current plugin metadata or resurrect an unmounted capability/alias.
        val hostEntries = coreMerged.filter { entry ->
            !isReservedPluginCapabilityName(entry.targetToolName) ||
                AiLimbsCoreCapabilityRegistry.isRegisteredInvokeName(normalize(entry.targetToolName))
        }
        return hostEntries + pluginEntries
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private fun requirePluginNamespace(value: String) {
        require(isReservedPluginCapabilityName(value) && PLUGIN_NAME.matches(value)) {
            "Plugin capability invoke names must use plugin.* namespace: $value"
        }
    }

    private val PLUGIN_NAME = Regex("^plugin\\.[a-z0-9]+(?:[._-][a-z0-9]+)*$")
}
