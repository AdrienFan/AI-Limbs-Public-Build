package com.ai.assistance.operit.integrations.ailimbs

import java.util.Locale
import org.json.JSONObject

/** Module aliases are derived from registered owner metadata and shared invoke namespaces. */
internal object AiLimbsScopeQuery {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val hanNames = Regex("[㐀-䶿一-鿿]{2,}")
    fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(separators, " ").trim()

    private fun aliases(scope: AiLimbsCapabilityScope): List<String> {
        val paths = scope.invokeIds.map { it.split('.').dropLast(1) }
        val common = if (paths.isEmpty()) emptyList() else paths.first().indices.takeWhile { index ->
            paths.all { it.getOrNull(index) == paths.first()[index] }
        }.map { paths.first()[it] }
        return (listOf(scope.scopeId, scope.ownerPluginId, scope.displayName,
            scope.ownerPluginId.substringAfterLast('.')) +
            listOfNotNull(common.lastOrNull()?.takeIf { common.size > 1 }) +
            hanNames.findAll(scope.displayName).filter { scope.displayName.trim().endsWith(it.value) }
                .map { it.value }.toList())
            .map(::normalize).filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
    }

    // Whole token boundaries avoid matching "studio" inside "studiobook"; Han owner names may
    // be embedded in a sentence without spaces, such as a registered 画室 owner followed by an intent.
    private fun mentionedAlias(query: String, scope: AiLimbsCapabilityScope): String? {
        val normalized = normalize(query)
        return aliases(scope).firstOrNull { alias ->
            if (hanNames.containsMatchIn(alias)) normalized.contains(alias)
            else " $normalized ".contains(" $alias ")
        }
    }

    fun mentions(query: String, scope: AiLimbsCapabilityScope): Boolean = mentionedAlias(query, scope) != null
    fun isIdentity(query: String, scope: AiLimbsCapabilityScope): Boolean = normalize(query) in aliases(scope)
    fun intent(query: String, scope: AiLimbsCapabilityScope): String {
        val normalized = normalize(query)
        val alias = mentionedAlias(query, scope) ?: return query
        val reduced = if (hanNames.containsMatchIn(alias)) normalized.replaceFirst(alias, " ").trim()
            else " $normalized ".replaceFirst(" $alias ", " ").trim()
        return reduced.ifBlank { query }
    }

    fun exactCapability(query: String, definition: AiLimbsCapabilityDefinition): Boolean =
        sequenceOf(definition.capabilityId, definition.invokeId, definition.displayName)
            .plus(definition.aliases.asSequence()).any { it.equals(query.trim(), ignoreCase = true) }
}

internal data class AiLimbsCapabilitySearchPlan(
    val mode: String,
    val definitions: List<AiLimbsCapabilityDefinition>,
    val query: String,
    val scopeIds: List<String> = emptyList()
)

/** Routes read-only discovery before collecting unrelated package, skill or MCP sources. */
internal object AiLimbsCapabilitySearchPlanner {
    suspend fun plan(
        query: String,
        scope: AiLimbsCapabilityScope?,
        registered: List<AiLimbsCapabilityDefinition>,
        scopes: List<AiLimbsCapabilityScope>,
        hostCatalog: suspend () -> List<AiLimbsCapabilityDefinition>,
        fullCatalog: suspend () -> List<AiLimbsCapabilityDefinition>
    ): AiLimbsCapabilitySearchPlan {
        if (scope != null) {
            val intent = AiLimbsScopeQuery.intent(query, scope)
            val owned = registered.filter { it.ownerPluginId == scope.ownerPluginId }
            return AiLimbsCapabilitySearchPlan("scope", preferExactAction(owned, intent), intent, listOf(scope.scopeId))
        }
        exactDefinition(registered, query)?.let {
            return AiLimbsCapabilitySearchPlan("exact_identity", listOf(it), query)
        }
        val named = scopes.filter { AiLimbsScopeQuery.mentions(query, it) }
        if (named.isNotEmpty()) {
            val owners = named.map { it.ownerPluginId }.toSet()
            var intent = query
            named.forEach { intent = AiLimbsScopeQuery.intent(intent, it) }
            val owned = registered.filter { it.ownerPluginId in owners }
            return AiLimbsCapabilitySearchPlan("named_scope", preferExactAction(owned, intent), intent, named.map { it.scopeId })
        }
        if (query.matches(Regex("[A-Za-z0-9._:-]+"))) {
            exactDefinition(hostCatalog(), query)?.let {
                return AiLimbsCapabilitySearchPlan("exact_identity", listOf(it), query)
            }
        }
        return AiLimbsCapabilitySearchPlan("global", fullCatalog(), query)
    }

    private fun exactDefinition(
        definitions: List<AiLimbsCapabilityDefinition>,
        query: String
    ): AiLimbsCapabilityDefinition? {
        val ids = definitions.filter { definition ->
            sequenceOf(definition.capabilityId, definition.invokeId).plus(definition.aliases.asSequence())
                .any { it.equals(query.trim(), true) }
        }
        if (ids.isNotEmpty()) return ids.singleOrNull()
        return definitions.filter { it.displayName.equals(query.trim(), true) }.singleOrNull()
    }

    private fun preferExactAction(
        definitions: List<AiLimbsCapabilityDefinition>,
        intent: String
    ): List<AiLimbsCapabilityDefinition> {
        exactDefinition(definitions, intent)?.let { return listOf(it) }
        val needle = AiLimbsScopeQuery.normalize(intent)
        val exact = definitions.filter { definition ->
            sequenceOf(definition.invokeId).plus(definition.aliases.asSequence())
                .any { AiLimbsScopeQuery.normalize(it.substringAfterLast('.')) == needle }
        }
        return if (exact.size == 1) exact else definitions
    }
}

/** Navigation follows the first combined ranked item, rather than the first legacy leaf array. */
internal object AiLimbsSearchGuidance {
    fun nextAction(
        items: List<AiLimbsOrganizedSearchItem>,
        query: String,
        activeScopeId: String?,
        describe: (String) -> JSONObject,
        search: (String, String?) -> JSONObject
    ): JSONObject = when (val first = items.firstOrNull()) {
        is AiLimbsOrganizedSearchItem.Capability -> describe(first.match.definition.capabilityId)
        is AiLimbsOrganizedSearchItem.Scope -> search(AiLimbsScopeQuery.intent(query, first.scope), first.scope.scopeId)
        null -> search("<refined capability intent>", activeScopeId)
    }
}
