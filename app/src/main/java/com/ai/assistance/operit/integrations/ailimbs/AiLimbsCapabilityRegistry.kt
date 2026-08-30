package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.plugins.center.PluginCapabilityBinder
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import java.util.UUID

internal data class AiLimbsPluginCapabilityRegistration(
    val token: String,
    val ownerPluginId: String,
    val catalogEntry: ToolCatalogEntry,
    val invokeAliases: List<String>,
    val capability: PluginCapabilitySpec
)

/** Dynamic capability registry. Entries exist only while their owning plugin mount is active. */
object AiLimbsPluginCapabilityRegistry : PluginCapabilityBinder {
    private val lock = Any()
    private val registrationsByInvokeName = linkedMapOf<String, AiLimbsPluginCapabilityRegistration>()
    private val registrationsByCanonical = linkedMapOf<String, AiLimbsPluginCapabilityRegistration>()

    override fun register(
        ownerPluginId: String,
        capabilityId: String,
        capability: PluginCapabilitySpec
    ): AutoCloseable {
        val canonical = requirePluginCapabilityName(capabilityId)
        val aliases = capability.invokeAliases
            .map(::requirePluginCapabilityName)
            .filter { it != canonical }
            .distinct()
        val allNames = listOf(canonical) + aliases
        allNames.forEach { name ->
            if (isCoreIdentifier(name)) {
                throw PluginInstallException(
                    "PLUGIN_CAPABILITY_CORE_CONFLICT",
                    "Plugin capability '$name' conflicts with an AI Limbs Core capability identifier"
                )
            }
        }

        val token = UUID.randomUUID().toString()
        val registration =
            AiLimbsPluginCapabilityRegistration(
                token = token,
                ownerPluginId = ownerPluginId,
                catalogEntry =
                    ToolCatalogEntry(
                        targetToolName = canonical,
                        displayName = capability.displayName.trim().ifBlank { canonical },
                        description = capability.description.trim(),
                        parameterHints = capability.parameters.map { it.name },
                        sourceKind = ToolCatalogSourceKind.INTERNAL,
                        keywords = (capability.keywords + "plugin" + ownerPluginId).distinct(),
                        suggestedParamsJson = capability.suggestedParamsJson,
                        parameters = capability.parameters.map { parameter ->
                            ToolParameterSchema(
                                name = parameter.name,
                                type = parameter.type,
                                description = parameter.description,
                                required = parameter.required,
                                default = parameter.default
                            )
                        },
                        sourceName = "plugin:$ownerPluginId",
                        sourceLocator = "plugin://$ownerPluginId/$canonical",
                        sourceEnabled = true,
                        inputSchema = capability.inputSchema,
                        searchMetadata = aliases + ownerPluginId
                    ),
                invokeAliases = aliases,
                capability = capability
            )

        synchronized(lock) {
            allNames.forEach { name ->
                val existing = registrationsByInvokeName[name]
                if (existing != null) {
                    throw PluginInstallException(
                        "PLUGIN_CAPABILITY_CONFLICT",
                        "Plugin capability '$name' is already owned by ${existing.ownerPluginId}"
                    )
                }
            }
            registrationsByCanonical[canonical] = registration
            allNames.forEach { registrationsByInvokeName[it] = registration }
        }

        return object : AutoCloseable {
            @Volatile
            private var closed = false

            override fun close() {
                if (closed) return
                synchronized(this) {
                    if (closed) return
                    closed = true
                }
                synchronized(lock) {
                    val current = registrationsByCanonical[canonical]
                    if (current?.token != token) return@synchronized
                    registrationsByCanonical.remove(canonical)
                    allNames.forEach { name ->
                        if (registrationsByInvokeName[name]?.token == token) {
                            registrationsByInvokeName.remove(name)
                        }
                    }
                }
            }
        }
    }

    internal fun registrationForInvokeName(name: String): AiLimbsPluginCapabilityRegistration? =
        synchronized(lock) { registrationsByInvokeName[name.trim()] }

    internal fun registrationSnapshot(): List<AiLimbsPluginCapabilityRegistration> =
        synchronized(lock) { registrationsByCanonical.values.toList() }

    internal fun isRegisteredInvokeName(name: String): Boolean =
        registrationForInvokeName(name) != null

    internal fun isCurrent(registration: AiLimbsPluginCapabilityRegistration): Boolean =
        synchronized(lock) {
            registrationsByCanonical[registration.catalogEntry.targetToolName]?.token == registration.token
        }

    internal fun mergeInto(runtimeCatalog: List<ToolCatalogEntry>): List<ToolCatalogEntry> =
        runtimeCatalog.filterNot { isReservedInvokeName(it.targetToolName) } +
            registrationSnapshot().map { it.catalogEntry }

    internal fun isReservedInvokeName(name: String): Boolean =
        name.trim().startsWith(PLUGIN_NAMESPACE_PREFIX)

    private fun requirePluginCapabilityName(raw: String): String {
        val value = raw.trim()
        if (!CAPABILITY_NAME.matches(value)) {
            throw PluginInstallException(
                "PLUGIN_CAPABILITY_ID_INVALID",
                "Invalid plugin capability identifier: $raw"
            )
        }
        if (!isReservedInvokeName(value)) {
            throw PluginInstallException(
                "PLUGIN_CAPABILITY_NAMESPACE_REQUIRED",
                "Plugin capability identifiers must use the reserved '$PLUGIN_NAMESPACE_PREFIX' namespace: $raw"
            )
        }
        return value
    }

    private fun isCoreIdentifier(name: String): Boolean =
        AiLimbsCoreCapabilityRegistry.registrationSnapshot().any { registration ->
            registration.catalogEntry.targetToolName == name ||
                name in registration.invokeAliases ||
                registration.capabilityId == name ||
                name in registration.capabilityAliases
        }

    private const val PLUGIN_NAMESPACE_PREFIX = "plugin."
    private val CAPABILITY_NAME = Regex("^[A-Za-z0-9]+(?:[._:/-][A-Za-z0-9]+)*$")
}

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

    data class HostTool(
        val targetName: String
    ) : AiLimbsCapabilityRoute

    data class Plugin(
        val registration: AiLimbsPluginCapabilityRegistration
    ) : AiLimbsCapabilityRoute
}

/** One authoritative registry view for Core + currently mounted Plugin capabilities. */
object AiLimbsCapabilityRegistry {
    internal fun registrationForInvokeName(name: String): AiLimbsCapabilityRegistration? {
        AiLimbsCoreCapabilityRegistry.registrationForInvokeName(name)?.let {
            return AiLimbsCapabilityRegistration.Core(it)
        }
        AiLimbsPluginCapabilityRegistry.registrationForInvokeName(name)?.let {
            return AiLimbsCapabilityRegistration.Plugin(it)
        }
        return null
    }

    internal fun isRegisteredInvokeName(name: String): Boolean =
        registrationForInvokeName(name) != null

    internal fun mergeInto(runtimeCatalog: List<ToolCatalogEntry>): List<ToolCatalogEntry> =
        AiLimbsPluginCapabilityRegistry.mergeInto(
            AiLimbsCoreCapabilityRegistry.mergeInto(runtimeCatalog)
        )
}
