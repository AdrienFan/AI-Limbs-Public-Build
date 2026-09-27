package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.catalog.ToolCapabilityCatalog
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class AiLimbsCapabilityDefinition(
    val capabilityId: String,
    val displayName: String,
    val provider: String,
    val invokeId: String,
    val aliases: List<String>,
    val catalogEntry: ToolCatalogEntry
)

private data class AiLimbsCapabilitySearchMatch(
    val definition: AiLimbsCapabilityDefinition,
    val score: Int,
    val matchedTerms: Int,
    val totalTerms: Int,
    val strongIdentityMatch: Boolean
) {
    val coverage: Double
        get() = if (totalTerms == 0) 0.0 else matchedTerms.toDouble() / totalTerms.toDouble()
}

private data class AiLimbsCapabilitySearchResult(
    val matches: List<AiLimbsCapabilitySearchMatch>,
    val lowConfidence: Boolean
)

private sealed interface AiLimbsOrganizedSearchItem {
    val sourceIndex: Int

    data class Capability(
        override val sourceIndex: Int,
        val match: AiLimbsCapabilitySearchMatch
    ) : AiLimbsOrganizedSearchItem

    data class Scope(
        override val sourceIndex: Int,
        val scope: AiLimbsCapabilityScope
    ) : AiLimbsOrganizedSearchItem
}

/** Read-only discovery surface for all AI Limbs capabilities. */
class AiLimbsCapabilityResolver(
    context: Context,
    private val policyEngine: AiLimbsExecutionPolicyEngine
) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val packageManager = handler.getOrCreatePackageManager()
    private val capabilityUsageStore = AiLimbsCapabilityUsageStore(appContext)

    internal fun currentCapabilityScopes(): List<AiLimbsCapabilityScope> =
        AiLimbsCapabilityRegistry.capabilityScopeSnapshot()

    suspend fun search(
        query: String,
        requestedLimit: Int,
        scope: String? = null
    ): JSONObject {
        val normalizedQuery = query.trim()
        val requestedScope = scope?.trim()?.ifBlank { null }
        if (normalizedQuery.isEmpty()) {
            return error("Missing capability search query")
                .put("error_code", "CAPABILITY_SEARCH_QUERY_REQUIRED")
                .put("next_action", capabilitySearchUsage("<capability intent>", requestedScope))
        }
        val limit = requestedLimit.coerceIn(1, MAX_SEARCH_RESULTS)

        var activeScope = requestedScope?.let(::resolveCapabilityScope)
        if (requestedScope != null && activeScope == null) {
            return unknownScope(requestedScope)
        }

        var definitions =
            filterDefinitionsForScope(
                buildDefinitions(forceRefreshPackages = false),
                activeScope
            )
        val candidateLimit = if (activeScope == null) MAX_SEARCH_RESULTS else limit
        var searchResult = searchDefinitions(definitions, normalizedQuery, candidateLimit)
        var usedLiveDiscovery = false
        if (searchResult.lowConfidence) {
            definitions = buildDefinitions(forceRefreshPackages = true)
            if (requestedScope != null) {
                activeScope = resolveCapabilityScope(requestedScope)
                if (activeScope == null) {
                    return unknownScope(requestedScope)
                }
            }
            definitions = filterDefinitionsForScope(definitions, activeScope)
            searchResult = searchDefinitions(definitions, normalizedQuery, candidateLimit)
            usedLiveDiscovery = true
        }

        val organizedItems =
            if (activeScope == null) {
                organizeGlobalSearch(normalizedQuery, searchResult.matches, limit)
            } else {
                searchResult.matches
                    .take(limit)
                    .mapIndexed { index, match ->
                        AiLimbsOrganizedSearchItem.Capability(index, match)
                    }
            }

        val results = JSONArray()
        val scopeResults = JSONArray()
        for (item in organizedItems) {
            when (item) {
                is AiLimbsOrganizedSearchItem.Capability -> {
                    val definition = item.match.definition
                    results.put(compactCard(definition, readAvailability(definition)))
                }
                is AiLimbsOrganizedSearchItem.Scope ->
                    scopeResults.put(scopeCard(item.scope))
            }
        }

        val activeScopeId = activeScope?.scopeId
        val totalCount = results.length() + scopeResults.length()
        val nextAction =
            when {
                totalCount == 0 ->
                    capabilitySearchUsage(
                        "<refined capability intent>",
                        activeScopeId
                    )
                results.length() == 0 && scopeResults.length() > 0 ->
                    scopeResults.getJSONObject(0).getJSONObject("next_action")
                else ->
                    capabilityDescribeUsage("<capability_id from results>")
            }
        val next =
            when {
                totalCount == 0 && activeScopeId == null ->
                    "No matching capability is currently installed or registered."
                totalCount == 0 ->
                    "No matching capability is currently registered in scope '$activeScopeId'."
                results.length() == 0 && scopeResults.length() > 0 ->
                    "Refine the intent inside a returned scope with capability.search and its scope_id."
                scopeResults.length() > 0 ->
                    "Use capability.describe for a leaf result, or capability.search with a scope_id to continue inside a returned scope."
                else ->
                    "Call capability.describe with a capability_id when parameters or prerequisites are needed."
            }

        val response =
            ok()
                .put("module", MODULE_NAME)
                .put("protocol_version", CAPABILITY_PROTOCOL_VERSION)
                .put("query", normalizedQuery)
                .put("count", totalCount)
                .put("capability_count", results.length())
                .put("scope_count", scopeResults.length())
                .put("live_discovery", usedLiveDiscovery)
                .put("results", results)
                .put("scope_results", scopeResults)
                .put("next_action", nextAction)
                .put("next", next)
        activeScopeId?.let { response.put("scope", it) }

        if (activeScopeId == null) {
            val cycleState = AiLimbsInteractionCycleRuntime.state(appContext)
            val generation = cycleState.currentGeneration()
            if (cycleState.claimHotCapabilities(generation)) {
                response.put(
                    "hot_capabilities",
                    runCatching { hotCapabilitiesJson(definitions) }
                        .getOrElse { JSONArray() }
                )
            }
        }
        return response
    }

    suspend fun describe(identifier: String): JSONObject {
        val normalizedIdentifier = identifier.trim()
        if (normalizedIdentifier.isEmpty()) {
            return error("Missing capability_id")
                .put("error_code", "CAPABILITY_DESCRIBE_ID_REQUIRED")
                .put("next_action", capabilityDescribeUsage("<capability_id from capability.search>"))
        }

        var definitions = buildDefinitions(forceRefreshPackages = false)
        var definition = findDefinition(definitions, normalizedIdentifier)
        var usedLiveDiscovery = false
        if (definition == null) {
            definitions = buildDefinitions(forceRefreshPackages = true)
            definition = findDefinition(definitions, normalizedIdentifier)
            usedLiveDiscovery = true
        }
        definition ?: return error(
            "Unknown capability '$normalizedIdentifier'. Call capability.search first."
        )
            .put("error_code", "UNKNOWN_CAPABILITY_ID")
            .put("next_action", capabilitySearchUsage(normalizedIdentifier))

        val availability = readAvailability(definition)
        val entry = definition.catalogEntry
        return ok()
            .put("module", MODULE_NAME)
            .put("protocol_version", CAPABILITY_PROTOCOL_VERSION)
            .put("live_discovery", usedLiveDiscovery)
            .put("capability_id", definition.capabilityId)
            .put("display_name", definition.displayName)
            .put("provider", definition.provider)
            .put("invoke_id", definition.invokeId)
            .put("description", entry.description)
            .put("aliases", JSONArray(definition.aliases))
            .put("keywords", JSONArray(entry.keywords.distinct()))
            .put("parameters", parametersJson(entry.parameters))
            .put("schema", schemaJson(entry))
            .put("permissions", permissionJson(availability))
            .put("prerequisites", JSONArray(availability.prerequisites))
            .put("availability", availabilityLabel(availability))
            .put("reason", availability.reason ?: JSONObject.NULL)
            .put("next_action", availability.nextAction ?: JSONObject.NULL)
            .put("policy", availability.toJson())
            .put("source_locator", sourceLocator(definition))
            .put("version", BuildConfig.VERSION_NAME)
            .put("minimal_example", minimalExample(definition))
            .put("error_guidance", errorGuidance(definition, availability))
    }

    internal fun capabilitySearchUsage(
        queryExample: String,
        scope: String? = null
    ): JSONObject {
        val parameters = JSONObject().put("query", queryExample)
        scope?.trim()?.ifBlank { null }?.let { parameters.put("scope", it) }
        return resolverUsage(
            invokeId = "capability.search",
            actionType = "CAPABILITY_SEARCH",
            exampleParameters = parameters
        )
    }

    internal fun capabilityDescribeUsage(capabilityIdExample: String): JSONObject =
        resolverUsage(
            invokeId = "capability.describe",
            actionType = "CAPABILITY_DESCRIBE",
            exampleParameters = JSONObject().put("capability_id", capabilityIdExample)
        )

    private fun resolverUsage(
        invokeId: String,
        actionType: String,
        exampleParameters: JSONObject
    ): JSONObject {
        val registration =
            AiLimbsCapabilityRegistry.registrationForInvokeName(invokeId)
                as? AiLimbsCapabilityRegistration.Core
        val entry = checkNotNull(registration?.registration?.catalogEntry) {
            "Resolver capability is not registered: $invokeId"
        }
        return JSONObject()
            .put("type", actionType)
            .put("capability", JSONObject().put("name", invokeId).put("parameters", JSONObject(exampleParameters.toString())))
            .put("parameters", parametersJson(entry.parameters))
            .put("schema", schemaJson(entry))
            .put("transport_invocation", policyEngine.transportInvocation(invokeId, exampleParameters))
    }

    internal suspend fun containsInvokeId(invokeId: String): Boolean {
        val normalizedInvokeId = invokeId.trim()
        if (normalizedInvokeId.isEmpty()) return false

        var definitions = buildDefinitions(forceRefreshPackages = false)
        if (definitions.any { it.invokeId == normalizedInvokeId }) return true

        definitions = buildDefinitions(forceRefreshPackages = true)
        return definitions.any { it.invokeId == normalizedInvokeId }
    }

    private suspend fun buildDefinitions(forceRefreshPackages: Boolean): List<AiLimbsCapabilityDefinition> {
        handler.registerDefaultTools()
        val runtimeCatalog =
            withContext(Dispatchers.IO) {
                ToolCapabilityCatalog.build(
                    context = appContext,
                    packageManager = packageManager,
                    roleCardToolAccess = null,
                    useEnglish = false,
                    includeDisabledPackages = true,
                    forceRefreshPackages = forceRefreshPackages,
                    includeAlternateLanguageMetadata = true
                )
            }
        val catalog = AiLimbsCapabilityRegistry.mergeInto(runtimeCatalog)

        return catalog
            .distinctBy { catalogIdentity(it) }
            .map(::toDefinition)
    }

    private fun resolveCapabilityScope(scopeId: String): AiLimbsCapabilityScope? =
        currentCapabilityScopes()
            .firstOrNull { it.scopeId.equals(scopeId, ignoreCase = true) }

    private fun filterDefinitionsForScope(
        definitions: List<AiLimbsCapabilityDefinition>,
        scope: AiLimbsCapabilityScope?
    ): List<AiLimbsCapabilityDefinition> {
        if (scope == null) return definitions
        return definitions.filter { definition ->
            AiLimbsCapabilityRegistry
                .pluginRegistrationForInvokeName(definition.invokeId)
                ?.ownerPluginId == scope.ownerPluginId
        }
    }

    private fun unknownScope(scopeId: String): JSONObject =
        error(
            "Unknown capability scope '$scopeId'. Call capability.search without scope to rediscover."
        )
            .put("error_code", "CAPABILITY_SEARCH_SCOPE_UNKNOWN")
            .put("scope", scopeId)
            .put("next_action", capabilitySearchUsage("<capability intent>"))

    private fun searchDefinitions(
        definitions: List<AiLimbsCapabilityDefinition>,
        query: String,
        limit: Int
    ): AiLimbsCapabilitySearchResult {
        val searchable = definitions.map { definition ->
            definition to definition.catalogEntry.copy(
                displayName = definition.displayName,
                searchMetadata =
                    (definition.catalogEntry.searchMetadata +
                        definition.aliases +
                        definition.capabilityId +
                        definition.invokeId +
                        definition.displayName +
                        definition.provider).distinct()
            )
        }
        val definitionsByIdentity = searchable.associate { (definition, entry) ->
            catalogIdentity(entry) to definition
        }
        val catalogResult = ToolCapabilityCatalog.searchDetailed(searchable.map { it.second }, query, limit)
        val matches = catalogResult.matches.mapNotNull { match ->
            definitionsByIdentity[catalogIdentity(match.entry)]?.let { definition ->
                AiLimbsCapabilitySearchMatch(
                    definition = definition,
                    score = match.score,
                    matchedTerms = match.matchedTerms,
                    totalTerms = match.totalTerms,
                    strongIdentityMatch = match.strongIdentityMatch
                )
            }
        }
        return AiLimbsCapabilitySearchResult(matches, catalogResult.lowConfidence)
    }

    private fun organizeGlobalSearch(
        query: String,
        matches: List<AiLimbsCapabilitySearchMatch>,
        limit: Int
    ): List<AiLimbsOrganizedSearchItem> {
        if (matches.isEmpty()) return emptyList()

        val scopesByOwner = currentCapabilityScopes().associateBy { it.ownerPluginId }
        val indexedMatches = matches.withIndex().toList()
        val groupedByOwner =
            indexedMatches
                .mapNotNull { indexed ->
                    ownerPluginIdFor(indexed.value)?.let { owner -> owner to indexed }
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second }
                )

        val foldedOwners =
            groupedByOwner
                .mapNotNull { (ownerPluginId, ownedMatches) ->
                    val scope = scopesByOwner[ownerPluginId] ?: return@mapNotNull null
                    val groupMatches = ownedMatches.map { it.value }
                    if (shouldFoldScope(query, scope, groupMatches)) ownerPluginId else null
                }
                .toSet()

        val emittedScopes = linkedSetOf<String>()
        val items = mutableListOf<AiLimbsOrganizedSearchItem>()
        for (indexed in indexedMatches) {
            val ownerPluginId = ownerPluginIdFor(indexed.value)
            if (ownerPluginId != null && ownerPluginId in foldedOwners) {
                if (emittedScopes.add(ownerPluginId)) {
                    val scope = scopesByOwner.getValue(ownerPluginId)
                    items +=
                        AiLimbsOrganizedSearchItem.Scope(
                            sourceIndex = indexed.index,
                            scope = scope
                        )
                }
            } else {
                items +=
                    AiLimbsOrganizedSearchItem.Capability(
                        sourceIndex = indexed.index,
                        match = indexed.value
                    )
            }
        }
        return items.sortedBy { it.sourceIndex }.take(limit)
    }

    private fun shouldFoldScope(
        query: String,
        scope: AiLimbsCapabilityScope,
        matches: List<AiLimbsCapabilitySearchMatch>
    ): Boolean {
        if (matches.size < SCOPE_FOLD_MIN_MATCHES) return false
        if (queryMatchesScopeIdentity(query, scope)) return true

        val top = matches[0]
        val runnerUp = matches.getOrNull(1) ?: return false
        val scoreGap = top.score - runnerUp.score
        val specificByIdentity =
            top.strongIdentityMatch &&
                top.coverage >= SPECIFIC_LEAF_IDENTITY_MIN_COVERAGE &&
                scoreGap >= SPECIFIC_LEAF_IDENTITY_SCORE_GAP
        val specificByScore =
            top.score >= SPECIFIC_LEAF_MIN_SCORE &&
                top.coverage >= SPECIFIC_LEAF_MIN_COVERAGE &&
                scoreGap >= SPECIFIC_LEAF_SCORE_GAP

        return !(specificByIdentity || specificByScore)
    }

    private fun ownerPluginIdFor(match: AiLimbsCapabilitySearchMatch): String? =
        AiLimbsCapabilityRegistry
            .pluginRegistrationForInvokeName(match.definition.invokeId)
            ?.ownerPluginId

    private fun queryMatchesScopeIdentity(
        query: String,
        scope: AiLimbsCapabilityScope
    ): Boolean {
        val needle = normalizeOrganizerText(query)
        if (needle.isEmpty()) return false
        return sequenceOf(
            scope.scopeId,
            scope.ownerPluginId,
            scope.displayName
        ).any { normalizeOrganizerText(it) == needle }
    }

    private fun normalizeOrganizerText(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\p{L}\p{N}:_./-]+"), " ")
            .replace(Regex("\s+"), " ")
            .trim()

    private fun hotCapabilitiesJson(
        definitions: List<AiLimbsCapabilityDefinition>
    ): JSONArray {
        val discoverable =
            definitions.map { definition ->
                AiLimbsDiscoverableCapability(
                    capabilityId = definition.capabilityId,
                    displayName = definition.displayName,
                    invokeId = definition.invokeId
                )
            }
        val ranked =
            AiLimbsHotCapabilityRanker.rank(
                usageStats = capabilityUsageStore.snapshots(),
                discoverableCapabilities = discoverable,
                limit = HOT_CAPABILITY_LIMIT
            )
        return JSONArray().apply {
            ranked.forEach { capability ->
                put(
                    JSONObject()
                        .put("display_name", capability.displayName)
                        .put("invoke_id", capability.invokeId)
                        .put("capability_id", capability.capabilityId)
                        .put("use_count", capability.useCount)
                )
            }
        }
    }

    private fun scopeCard(scope: AiLimbsCapabilityScope): JSONObject =
        JSONObject()
            .put("scope_id", scope.scopeId)
            .put("kind", scope.kind.wireName)
            .put("display_name", scope.displayName)
            .put("description", scope.description ?: JSONObject.NULL)
            .put("capability_count", scope.capabilityCount)
            .put(
                "next_action",
                capabilitySearchUsage(
                    "<specific capability intent>",
                    scope.scopeId
                )
            )

    private fun findDefinition(
        definitions: List<AiLimbsCapabilityDefinition>,
        identifier: String
    ): AiLimbsCapabilityDefinition? {
        val needle = identifier.lowercase(Locale.ROOT)
        return definitions.firstOrNull { definition ->
            definition.capabilityId.lowercase(Locale.ROOT) == needle ||
                definition.invokeId.lowercase(Locale.ROOT) == needle ||
                definition.displayName.lowercase(Locale.ROOT) == needle ||
                definition.aliases.any { it.lowercase(Locale.ROOT) == needle }
        }
    }

    private fun toDefinition(entry: ToolCatalogEntry): AiLimbsCapabilityDefinition {
        val invokeId = entry.targetToolName
        val provider = providerFor(entry)
        val generatedId = generatedCapabilityId(provider, invokeId, entry.displayName)
        val managedRegistration = AiLimbsCapabilityRegistry.registrationForInvokeName(invokeId)
        val coreRegistration =
            (managedRegistration as? AiLimbsCapabilityRegistration.Core)?.registration
        val pluginRegistration =
            (managedRegistration as? AiLimbsCapabilityRegistration.Plugin)?.registration
        val semantic = semanticMetadata(invokeId)
        val capabilityId =
            coreRegistration?.capabilityId
                ?: pluginRegistration?.capabilityId
                ?: semantic?.capabilityId
                ?: generatedId
        val aliases =
            buildList {
                add(generatedId)
                coreRegistration?.invokeAliases?.let(::addAll)
                coreRegistration?.capabilityAliases?.let(::addAll)
                pluginRegistration?.invokeAliases?.let(::addAll)
                semantic?.aliases?.let(::addAll)
                add(invokeId)
            }.filter { it != capabilityId }.distinct()
        return AiLimbsCapabilityDefinition(
            capabilityId = capabilityId,
            displayName = semantic?.displayName ?: entry.displayName,
            provider = provider,
            invokeId = invokeId,
            aliases = aliases,
            catalogEntry =
                entry.copy(
                    keywords = (entry.keywords + semantic.orEmptyKeywords()).distinct()
                )
        )
    }

    private suspend fun readAvailability(
        definition: AiLimbsCapabilityDefinition
    ): AiLimbsPolicyInspection =
        policyEngine.inspectForResolver(
            targetName = definition.invokeId,
            entry = definition.catalogEntry
        )

    private fun compactCard(
        definition: AiLimbsCapabilityDefinition,
        availability: AiLimbsPolicyInspection
    ): JSONObject =
        JSONObject()
            .put("capability_id", definition.capabilityId)
            .put("display_name", definition.displayName)
            .put("provider", definition.provider)
            .put("invoke_id", definition.invokeId)
            .put("availability", availabilityLabel(availability))
            .put(
                "requires_confirmation",
                availability.outcome == AiLimbsPolicyOutcome.ASK
            )
            .put("policy_outcome", availability.outcome.name)
            .apply {
                availability.reason?.let { put("reason", it) }
                availability.nextAction?.let { put("next_action", it) }
            }

    private fun availabilityLabel(availability: AiLimbsPolicyInspection): String =
        if (availability.available) "available" else "unavailable"

    private fun permissionJson(availability: AiLimbsPolicyInspection): JSONObject =
        JSONObject()
            .put("effective", availability.permission)
            .put(
                "requires_confirmation",
                availability.outcome == AiLimbsPolicyOutcome.ASK
            )
            .put("enforced_by", availability.permissionEnforcedBy)
            .put("effect", availability.effect.name)
            .put("domain", availability.domain.name)
            .put(
                "required_receipts",
                JSONArray(availability.requiredReceipts.map { it.name })
            )
            .put("payload_kind", availability.payloadKind.name)

    private fun parametersJson(parameters: List<ToolParameterSchema>): JSONArray =
        JSONArray().apply {
            parameters.forEach { parameter ->
                put(
                    JSONObject()
                        .put("name", parameter.name)
                        .put("type", parameter.type)
                        .put("description", parameter.description)
                        .put("required", parameter.required)
                        .put("default", parameter.default ?: JSONObject.NULL)
                )
            }
        }

    private fun schemaJson(entry: ToolCatalogEntry): JSONObject {
        entry.inputSchema?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
        }
        val properties = JSONObject()
        val required = JSONArray()
        entry.parameters.forEach { parameter ->
            properties.put(
                parameter.name,
                JSONObject()
                    .put("type", parameter.type)
                    .put("description", parameter.description)
                    .apply { parameter.default?.let { put("default", it) } }
            )
            if (parameter.required) required.put(parameter.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
    }

    private fun minimalExample(definition: AiLimbsCapabilityDefinition): JSONObject {
        val suggested = definition.catalogEntry.suggestedParamsJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val parameters = suggested ?: JSONObject()
        if (suggested == null) {
            definition.catalogEntry.parameters
                .filter { it.required }
                .forEach { parameter ->
                    parameters.put(parameter.name, exampleValue(definition.invokeId, parameter))
                }
        }
        return policyEngine.transportInvocation(definition.invokeId, parameters)
    }

    private fun exampleValue(invokeId: String, parameter: ToolParameterSchema): Any {
        parameter.default?.let { return it }
        return when (parameter.name) {
            "query" -> "查看当前手机屏幕"
            "capability_id" -> "ui.screen.capture"
            "mode" -> "MINUTES_15"
            "key_code" -> "KEYCODE_HOME"
            "x", "y", "start_x", "start_y", "end_x", "end_y", "index" -> 0
            "duration", "duration_ms", "timeout_ms" -> 500
            "package_name" -> definitionSourceName(invokeId)
            else -> when (parameter.type.lowercase(Locale.ROOT)) {
                "integer", "number" -> 0
                "boolean" -> false
                "array" -> JSONArray()
                "object" -> JSONObject()
                else -> "<${parameter.name}>"
            }
        }
    }

    private fun errorGuidance(
        definition: AiLimbsCapabilityDefinition,
        availability: AiLimbsPolicyInspection
    ): JSONObject = JSONObject()
        .put(
            "before_invoke",
            availability.nextAction
                ?: "Invoke through the normal dispatcher; ALLOW / ASK / FORBID remains enforced."
        )
        .put(
            "on_unknown_invocation",
            "Call capability.search again; do not guess a replacement tool name."
        )
        .put("invoke_id", definition.invokeId)

    private fun providerFor(entry: ToolCatalogEntry): String {
        when (val registration = AiLimbsCapabilityRegistry.registrationForInvokeName(entry.targetToolName)) {
            is AiLimbsCapabilityRegistration.Core ->
                return when (registration.registration.provider) {
                    AiLimbsCoreProvider.CORE -> PROVIDER_CORE
                    AiLimbsCoreProvider.BRIDGE -> PROVIDER_BRIDGE
                }
            is AiLimbsCapabilityRegistration.Plugin ->
                return "plugin:${registration.registration.ownerPluginId}"
            null -> Unit
        }
        return when {
            isSystemEnvironmentBackedTool(entry.targetToolName) -> PROVIDER_SYSTEM_ENVIRONMENT
            entry.sourceKind == ToolCatalogSourceKind.PACKAGE -> "toolpkg"
            entry.sourceKind == ToolCatalogSourceKind.MCP -> "mcp"
            entry.sourceKind == ToolCatalogSourceKind.ACTIVATION -> "activation"
            else -> "native"
        }
    }

    private fun sourceLocator(definition: AiLimbsCapabilityDefinition): String = when {
        AiLimbsCapabilityRegistry.isRegisteredInvokeName(definition.invokeId) ->
            definition.catalogEntry.sourceLocator ?: "registry://${definition.invokeId}"
        definition.invokeId.startsWith(AUTOMATIC_UI_BASE_PREFIX) ->
            "assets://packages/automatic_ui_base.js#${definition.invokeId.substringAfter(':')}"
        definition.invokeId.startsWith(AUTOMATIC_UI_SUBAGENT_PREFIX) ->
            "assets://packages/automatic_ui_subagent.js#${definition.invokeId.substringAfter(':')}"
        definition.provider == PROVIDER_SYSTEM_ENVIRONMENT -> "system-environment://legacy-process/${definition.invokeId}"
        else -> definition.catalogEntry.sourceLocator ?: "registry://${definition.invokeId}"
    }

    private fun generatedCapabilityId(provider: String, invokeId: String, displayName: String): String {
        val stableName =
            if (invokeId == "use_package") "activate.$displayName" else invokeId.replace(':', '.')
        return "$provider.${stableName.replace(Regex("[^A-Za-z0-9_.-]"), "_")}"
    }

    private fun catalogIdentity(entry: ToolCatalogEntry): String =
        "${entry.sourceKind}:${entry.targetToolName}:${entry.sourceName ?: entry.displayName}"

    private fun definitionSourceName(invokeId: String): String =
        invokeId.substringBefore(':').takeIf { it != invokeId }.orEmpty().ifBlank { "<package_name>" }

    private fun isSystemEnvironmentBackedTool(invokeId: String): Boolean =
        SYSTEM_ENVIRONMENT_PROCESS_TOOLS.contains(invokeId)

    private data class SemanticMetadata(
        val capabilityId: String,
        val displayName: String,
        val aliases: List<String>,
        val keywords: List<String>
    )

    private fun semanticMetadata(invokeId: String): SemanticMetadata? = SEMANTIC_METADATA[invokeId]
    private fun SemanticMetadata?.orEmptyKeywords(): List<String> = this?.keywords.orEmpty()

    private fun ok() = JSONObject().put("success", true)
    private fun error(message: String) = JSONObject().put("success", false).put("error", message)

    private companion object {
        const val MODULE_NAME = "AI Limbs Capability Resolver"
        const val CAPABILITY_PROTOCOL_VERSION = 3
        const val MAX_SEARCH_RESULTS = 20
        const val HOT_CAPABILITY_LIMIT = 5
        const val SCOPE_FOLD_MIN_MATCHES = 3
        const val SPECIFIC_LEAF_MIN_SCORE = 220
        const val SPECIFIC_LEAF_MIN_COVERAGE = 0.75
        const val SPECIFIC_LEAF_SCORE_GAP = 100
        const val SPECIFIC_LEAF_IDENTITY_MIN_COVERAGE = 0.5
        const val SPECIFIC_LEAF_IDENTITY_SCORE_GAP = 80
        const val PROVIDER_CORE = AiLimbsCoreCapabilityRegistry.CORE_PROVIDER
        const val PROVIDER_BRIDGE = AiLimbsCoreCapabilityRegistry.BRIDGE_PROVIDER
        const val PROVIDER_SYSTEM_ENVIRONMENT = "system_environment"
        const val AUTOMATIC_UI_BASE_PREFIX = "Automatic_ui_base:"
        const val AUTOMATIC_UI_SUBAGENT_PREFIX = "Automatic_ui_subagent:"

        val SYSTEM_ENVIRONMENT_PROCESS_TOOLS = setOf(
            "create_terminal_session",
            "execute_in_terminal_session",
            "execute_in_terminal_session_streaming",
            "execute_hidden_terminal_command",
            "close_terminal_session",
            "input_in_terminal_session",
            "get_terminal_session_screen"
        )

        val SEMANTIC_METADATA = mapOf(
            "Automatic_ui_base:get_page_screenshot_image" to SemanticMetadata(
                "ui.screen.capture",
                "获取当前屏幕截图",
                listOf("screen.capture", "android.screen.capture"),
                listOf(
                    "截图",
                    "屏幕",
                    "查看屏幕",
                    "查看当前手机屏幕",
                    "看一下手机",
                    "看看手机",
                    "当前页面",
                    "视觉",
                    "screenshot"
                )
            ),
            "Automatic_ui_base:get_page_info" to SemanticMetadata(
                "ui.page.inspect",
                "读取当前页面结构",
                listOf("ui.hierarchy.read", "android.ui.inspect"),
                listOf("页面结构", "读取页面结构", "当前页面按钮", "按钮", "控件", "accessibility", "page info")
            ),
            "Automatic_ui_base:click_element" to SemanticMetadata(
                "ui.element.click",
                "点击页面元素",
                listOf("ui.click"),
                listOf("点击", "按钮", "元素", "控件")
            ),
            "Automatic_ui_base:tap" to SemanticMetadata(
                "ui.screen.tap",
                "点击屏幕坐标",
                listOf("ui.tap"),
                listOf("点击", "坐标", "触摸", "tap")
            ),
            "Automatic_ui_base:swipe" to SemanticMetadata(
                "ui.screen.swipe",
                "滑动屏幕",
                listOf("ui.swipe"),
                listOf("滑动", "翻页", "滚动", "swipe")
            ),
            "Automatic_ui_base:set_input_text" to SemanticMetadata(
                "ui.text.input",
                "输入文字",
                listOf("ui.input"),
                listOf("输入", "文字", "文本框", "键盘")
            ),
            "Automatic_ui_base:press_key" to SemanticMetadata(
                "ui.key.press",
                "按下 Android 按键",
                listOf("android.key.press"),
                listOf("返回键", "主页键", "按键", "press key")
            ),
            "Automatic_ui_base:app_launch" to SemanticMetadata(
                "android.app.launch",
                "启动 Android 应用",
                listOf("ui.app.launch"),
                listOf("打开应用", "启动应用", "package")
            )
        )
    }
}
