package com.ai.assistance.operit.core.tools.catalog

import android.content.Context
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.PackageToolParameter
import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.mcp.MCPLocalServer
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess
import com.ai.assistance.operit.data.skill.SkillRepository
import com.ai.assistance.operit.util.AppLogger
import java.util.Locale
import org.json.JSONObject

/** The provider-neutral source kinds shared by CLI search and AI Limbs capability discovery. */
enum class ToolCatalogSourceKind {
    BUILTIN,
    INTERNAL,
    PACKAGE,
    MCP,
    ACTIVATION;

    fun label(useEnglish: Boolean): String = when (this) {
        BUILTIN -> "built-in"
        INTERNAL -> "internal"
        PACKAGE -> "package"
        MCP -> if (useEnglish) "mcp" else "MCP"
        ACTIVATION -> "activation"
    }
}

/** A normalized tool record. Transport-specific resolvers may enrich its live availability. */
data class ToolCatalogEntry(
    val targetToolName: String,
    val displayName: String,
    val description: String,
    val parameterHints: List<String>,
    val sourceKind: ToolCatalogSourceKind,
    val keywords: List<String> = emptyList(),
    val suggestedParamsJson: String? = null,
    val parameters: List<ToolParameterSchema> = emptyList(),
    val sourceName: String? = null,
    val sourceLocator: String? = null,
    val sourceEnabled: Boolean = true,
    val inputSchema: String? = null,
    val searchMetadata: List<String> = emptyList()
)

data class ToolCatalogSearchMatch(
    val entry: ToolCatalogEntry,
    val score: Int,
    val matchedTerms: Int,
    val totalTerms: Int,
    val strongIdentityMatch: Boolean
) {
    val coverage: Double
        get() = if (totalTerms == 0) 0.0 else matchedTerms.toDouble() / totalTerms.toDouble()
}

data class ToolCatalogSearchResult(
    val matches: List<ToolCatalogSearchMatch>,
    val lowConfidence: Boolean
)

/**
 * Builds one catalog from Operit's structured prompts, runtime registry, ToolPkg metadata, skills,
 * and cached MCP schemas. It never starts Ubuntu or an MCP server merely to discover metadata.
 */
object ToolCapabilityCatalog {
    private const val TAG = "ToolCapabilityCatalog"
    // Android's Unicode pattern compilation is costly; compile once, outside each field/entry.
    private val NON_SEARCH_CHARACTERS = Regex("[^\\p{L}\\p{N}:_./-]+")
    private val WHITESPACE = Regex("\\s+")
    private val TOKEN_SEPARATORS = Regex("[:_./-]+")
    private val RESERVED_TARGETS = setOf("search", "proxy", "package_proxy")
    private val ENGLISH_STOP_WORDS = setOf(
        "a", "an", "the", "by", "to", "for", "of", "with", "from", "on", "in", "at", "via"
    )
    private val GENERIC_KEYWORDS = setOf(
        "package", "cached", "activate", "activation", "internal", "built-in", "builtin", "内部工具"
    )
    private const val MIN_SEARCH_SCORE = 20
    private const val LOW_CONFIDENCE_SCORE = 70

    internal data class PreparedEntry(
        val entry: ToolCatalogEntry,
        val displayName: String,
        val targetName: String,
        val description: String,
        val parameterNames: String,
        val parameterDescriptions: String,
        val metadata: String,
        val displayTokens: Set<String>,
        val targetTokens: Set<String>,
        val descriptionTokens: Set<String>,
        val parameterNameTokens: Set<String>,
        val parameterDescriptionTokens: Set<String>,
        val metadataTokens: Set<String>,
        val keywords: List<Pair<String, Set<String>>>,
        val exactMetadata: Set<String>,
        val exactParameterNames: Set<String>
    )

    internal class PreparedIndex(val documents: List<PreparedEntry>)

    internal fun prepareIndex(catalog: List<ToolCatalogEntry>): PreparedIndex =
        PreparedIndex(catalog.map(::prepareEntry))

    private data class SearchScore(
        val score: Int,
        val matchedTerms: Int,
        val totalTerms: Int,
        val strongIdentityMatch: Boolean
    )

    suspend fun build(
        context: Context,
        packageManager: PackageManager,
        roleCardToolAccess: ResolvedCharacterCardToolAccess? = null,
        useEnglish: Boolean,
        includeDisabledPackages: Boolean = false,
        forceRefreshPackages: Boolean = false,
        includeAlternateLanguageMetadata: Boolean = false,
        phaseTimings: MutableMap<String, Long>? = null
    ): List<ToolCatalogEntry> {
        var phaseStarted = System.nanoTime()
        fun finishPhase(name: String) {
            phaseTimings?.put(name, (System.nanoTime() - phaseStarted) / 1_000_000)
            phaseStarted = System.nanoTime()
        }
        val entries = LinkedHashMap<String, ToolCatalogEntry>()
        buildHostCatalog(context, roleCardToolAccess, useEnglish, includeAlternateLanguageMetadata)
            .forEach { entries[entryKey(it)] = it }
        finishPhase("host")

        // ToolPkg is an optional discovery source. A broken package/cache/runtime must never make
        // native/core capabilities such as shell, press_key or plugin ingress undiscoverable.
        val packageDiscovery = runCatching {
            packageManager.getPackageCatalogSnapshot(forceRefreshPackages)
        }.onFailure { error ->
            AppLogger.w(
                TAG,
                "Tool package catalog unavailable; continuing with native/core capabilities",
                error
            )
        }
        val availablePackages = packageDiscovery.getOrDefault(emptyMap())
        val packageNames: Collection<String> =
            if (packageDiscovery.isFailure) {
                emptyList()
            } else if (includeDisabledPackages) {
                availablePackages.keys
            } else {
                runCatching { packageManager.getEnabledPackageNames() }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Enabled ToolPkg discovery unavailable; skipping package entries", error)
                    }
                    .getOrDefault(emptyList())
            }

        packageNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !packageManager.isToolPkgContainer(it) }
            .filter { roleCardToolAccess?.isExternalSourceAllowed(it) != false }
            .distinct()
            .forEach { packageName ->
                val toolPackage =
                    availablePackages[packageName] ?: return@forEach
                val enabled = packageManager.isPackageEnabled(packageName)
                if (toolPackage.tools.isEmpty()) {
                    addActivationEntry(
                        entries = entries,
                        displayName = packageName,
                        description = toolPackage.description.resolve(context),
                        keywordTag = "package",
                        sourceKind = ToolCatalogSourceKind.ACTIVATION,
                        sourceEnabled = enabled
                    )
                } else {
                    addPackageToolEntries(
                        context = context,
                        entries = entries,
                        prefix = packageName,
                        toolPackage = toolPackage,
                        sourceEnabled = enabled
                    )
                }
            }

        finishPhase("packages")
        val skillPackages =
            runCatching { SkillRepository.getInstance(context).getAiVisibleSkillPackages() }
                .onFailure { error ->
                    AppLogger.w(TAG, "Skill catalog unavailable; continuing without skill entries", error)
                }
                .getOrDefault(emptyMap())
                .filterKeys { roleCardToolAccess?.isExternalSourceAllowed(it) != false }

        skillPackages.forEach { (skillName, skillPackage) ->
            addActivationEntry(
                entries = entries,
                displayName = skillName,
                description = skillPackage.description,
                keywordTag = "skill",
                sourceKind = ToolCatalogSourceKind.ACTIVATION,
                sourceEnabled = true
            )
        }

        finishPhase("skills")
        val mcpServers =
            if (packageDiscovery.isFailure) {
                emptyMap()
            } else {
                runCatching { packageManager.getAvailableServerPackages() }
                    .onFailure { error ->
                        AppLogger.w(TAG, "MCP catalog unavailable; continuing without MCP entries", error)
                    }
                    .getOrDefault(emptyMap())
            }.filterKeys { roleCardToolAccess?.isExternalSourceAllowed(it) != false }
        val mcpLocalServer =
            if (mcpServers.isEmpty()) {
                null
            } else {
                runCatching { MCPLocalServer.getInstance(context) }
                    .onFailure { error ->
                        AppLogger.w(TAG, "MCP local catalog unavailable; continuing without MCP entries", error)
                    }
                    .getOrNull()
            }

        if (mcpLocalServer != null) {
            mcpServers.forEach { (serverName, serverConfig) ->
                val enabled = mcpLocalServer.isServerEnabled(serverName)
                val cachedTools = mcpLocalServer.getCachedTools(serverName).orEmpty()
                if (cachedTools.isEmpty()) {
                    addActivationEntry(
                        entries = entries,
                        displayName = serverName,
                        description = serverConfig.description,
                        keywordTag = "mcp",
                        sourceKind = ToolCatalogSourceKind.ACTIVATION,
                        sourceEnabled = enabled
                    )
                    return@forEach
                }

                addCachedMcpToolEntries(
                    entries = entries,
                    serverName = serverName,
                    serverDescription = serverConfig.description,
                    cachedTools = cachedTools,
                    sourceEnabled = enabled
                )
            }
        }

        finishPhase("mcp")
        return entries.values.toList()
    }

    internal fun buildHostCatalog(
        context: Context,
        roleCardToolAccess: ResolvedCharacterCardToolAccess? = null,
        useEnglish: Boolean,
        includeAlternateLanguageMetadata: Boolean = false
    ): List<ToolCatalogEntry> {
        val categories = buildBuiltinAndInternalCategories(useEnglish)
        val builtinToolNames = buildBuiltinToolNameSet(useEnglish)
        val alternateSearchMetadata =
            if (includeAlternateLanguageMetadata) buildAlternateSearchMetadata(!useEnglish) else emptyMap()
        val entries = LinkedHashMap<String, ToolCatalogEntry>()

        categories.forEach { category ->
            category.tools.forEach { tool ->
                if (tool.name == "use_package" || RESERVED_TARGETS.contains(tool.name)) {
                    return@forEach
                }
                if (!isToolNameAllowed(tool.name, null, roleCardToolAccess)) {
                    return@forEach
                }

                val sourceKind =
                    if (builtinToolNames.contains(tool.name)) {
                        ToolCatalogSourceKind.BUILTIN
                    } else {
                        ToolCatalogSourceKind.INTERNAL
                    }
                val entry =
                    ToolCatalogEntry(
                        targetToolName = tool.name,
                        displayName = tool.name,
                        description = tool.description,
                        parameterHints = buildParameterHints(tool),
                        sourceKind = sourceKind,
                        keywords = listOf(category.categoryName),
                        parameters = tool.parametersStructured.orEmpty(),
                        searchMetadata = alternateSearchMetadata[tool.name].orEmpty(),
                        sourceLocator =
                            if (sourceKind == ToolCatalogSourceKind.BUILTIN) {
                                "native://SystemToolPrompts/${tool.name}"
                            } else {
                                "internal://SystemToolPrompts/${tool.name}"
                            }
                    )
                entries.putIfAbsent(entryKey(entry), entry)
            }
        }

        addRuntimeRegistryEntries(
            context = context,
            entries = entries,
            roleCardToolAccess = roleCardToolAccess
        )

        return entries.values.toList()
    }

    fun search(catalog: List<ToolCatalogEntry>, query: String, limit: Int): List<ToolCatalogEntry> =
        searchDetailed(catalog, query, limit).matches.map { it.entry }

    fun searchDetailed(
        catalog: List<ToolCatalogEntry>,
        query: String,
        limit: Int
    ): ToolCatalogSearchResult = searchPrepared(prepareIndex(catalog), query, limit.coerceIn(1, 20))

    // The resolver applies its limit after owner routing/grouping; other consumers keep Top 20.
    internal fun searchAll(index: PreparedIndex, query: String): ToolCatalogSearchResult =
        searchPrepared(index, query, null)

    private fun searchPrepared(
        index: PreparedIndex,
        query: String,
        limit: Int?
    ): ToolCatalogSearchResult {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return ToolCatalogSearchResult(emptyList(), lowConfidence = true)
        }
        val terms = buildSearchTerms(normalizedQuery)
        if (terms.isEmpty()) {
            return ToolCatalogSearchResult(emptyList(), lowConfidence = true)
        }

        val tokenMatches = HashMap<Pair<String, String>, Boolean>()
        val matches = index.documents
            .mapNotNull { document ->
                val entry = document.entry
                val scored = scoreEntry(document, normalizedQuery, terms, tokenMatches)
                if (!isRelevant(scored)) {
                    null
                } else {
                    ToolCatalogSearchMatch(
                        entry = entry,
                        score = scored.score,
                        matchedTerms = scored.matchedTerms,
                        totalTerms = scored.totalTerms,
                        strongIdentityMatch = scored.strongIdentityMatch
                    )
                }
            }
            .sortedWith(
                compareByDescending<ToolCatalogSearchMatch> { it.score }
                    .thenByDescending { it.coverage }
                    .thenBy { it.entry.targetToolName }
                    .thenBy { it.entry.displayName }
            )
            .let { matches -> if (limit == null) matches else matches.take(limit) }

        val top = matches.firstOrNull()
        val lowConfidence = top == null ||
            (!top.strongIdentityMatch &&
                (top.score < LOW_CONFIDENCE_SCORE ||
                    (top.totalTerms >= 6 && top.matchedTerms < 3)))
        return ToolCatalogSearchResult(matches, lowConfidence)
    }

    private fun addRuntimeRegistryEntries(
        context: Context,
        entries: MutableMap<String, ToolCatalogEntry>,
        roleCardToolAccess: ResolvedCharacterCardToolAccess?
    ) {
        val handler = AIToolHandler.getInstance(context.applicationContext)
        handler.registerDefaultTools()
        handler.getAllToolNames()
            .asSequence()
            .filter { it.isNotBlank() && !it.contains(':') }
            .filter { it != "use_package" && !RESERVED_TARGETS.contains(it) }
            .filter { isToolNameAllowed(it, null, roleCardToolAccess) }
            .forEach { toolName ->
                if (entries.values.any { it.targetToolName == toolName }) return@forEach
                val parameters = runtimeParameterSchemas(toolName)
                val entry =
                    ToolCatalogEntry(
                        targetToolName = toolName,
                        displayName = toolName,
                        description = handler.getToolDescription(toolName),
                        parameterHints = parameters.map(::buildParameterHint),
                        sourceKind = ToolCatalogSourceKind.INTERNAL,
                        keywords = runtimeKeywords(toolName),
                        parameters = parameters,
                        sourceLocator = "native://AIToolHandler/$toolName"
                    )
                entries.putIfAbsent(entryKey(entry), entry)
            }
    }

    // Concrete System Environment plugins publish their own rich parameter/keyword metadata.
    // Native Base tools must not hardcode Ubuntu lifecycle semantics here.
    private fun runtimeParameterSchemas(toolName: String): List<ToolParameterSchema> = emptyList()

    private fun runtimeKeywords(toolName: String): List<String> = when {
        toolName.startsWith("ai_limbs.") -> listOf("ai limbs", "兰儿")
        else -> emptyList()
    }

    private fun isToolNameAllowed(
        toolName: String,
        usePackageSourceName: String?,
        roleCardToolAccess: ResolvedCharacterCardToolAccess?
    ): Boolean {
        roleCardToolAccess ?: return true
        return when {
            toolName == "use_package" ->
                roleCardToolAccess.isBuiltinToolAllowed("use_package") &&
                    (usePackageSourceName.isNullOrBlank() ||
                        roleCardToolAccess.isExternalSourceAllowed(usePackageSourceName))
            toolName.contains(':') -> {
                val sourceName = toolName.substringBefore(':').trim()
                sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
            }
            else -> roleCardToolAccess.isBuiltinToolAllowed(toolName)
        }
    }

    private fun buildBuiltinAndInternalCategories(useEnglish: Boolean): List<SystemToolPromptCategory> =
        if (useEnglish) SystemToolPrompts.getAllCategoriesEn() else SystemToolPrompts.getAllCategoriesCn()

    private fun buildAlternateSearchMetadata(useEnglish: Boolean): Map<String, List<String>> {
        val metadata = linkedMapOf<String, MutableList<String>>()
        buildBuiltinAndInternalCategories(useEnglish).forEach { category ->
            category.tools.forEach { tool ->
                metadata.getOrPut(tool.name) { mutableListOf() }.apply {
                    add(category.categoryName)
                    add(tool.description)
                    addAll(buildParameterHints(tool))
                }
            }
        }
        return metadata.mapValues { (_, values) -> values.filter { it.isNotBlank() }.distinct() }
    }

    private fun buildBuiltinToolNameSet(useEnglish: Boolean): Set<String> {
        val categories =
            if (useEnglish) SystemToolPrompts.getAIAllCategoriesEn()
            else SystemToolPrompts.getAIAllCategoriesCn()
        return categories.flatMap { it.tools }.mapTo(linkedSetOf()) { it.name }
    }

    private fun buildParameterHints(tool: ToolPrompt): List<String> {
        val structured = tool.parametersStructured.orEmpty()
        if (structured.isNotEmpty()) return structured.map(::buildParameterHint)
        return tool.parameters.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun buildParameterHint(parameter: ToolParameterSchema): String {
        val requiredText = if (parameter.required) "required" else "optional"
        return "${parameter.name} [${parameter.type}, $requiredText]: ${parameter.description}"
    }

    private fun addActivationEntry(
        entries: MutableMap<String, ToolCatalogEntry>,
        displayName: String,
        description: String,
        keywordTag: String,
        sourceKind: ToolCatalogSourceKind,
        sourceEnabled: Boolean
    ) {
        val parameters =
            listOf(
                ToolParameterSchema(
                    name = "package_name",
                    type = "string",
                    description = displayName,
                    required = true
                )
            )
        val entry =
            ToolCatalogEntry(
                targetToolName = "use_package",
                displayName = displayName,
                description = description,
                parameterHints = parameters.map(::buildParameterHint),
                sourceKind = sourceKind,
                keywords = listOf(keywordTag, "use_package", "activate"),
                suggestedParamsJson = "{\"package_name\":\"$displayName\"}",
                parameters = parameters,
                sourceName = displayName,
                sourceLocator = "activation://$keywordTag/$displayName",
                sourceEnabled = sourceEnabled
            )
        entries.putIfAbsent(entryKey(entry), entry)
    }

    private fun addPackageToolEntries(
        context: Context,
        entries: MutableMap<String, ToolCatalogEntry>,
        prefix: String,
        toolPackage: ToolPackage,
        sourceEnabled: Boolean
    ) {
        toolPackage.tools.filter { !it.advice }.forEach { packageTool ->
            val targetToolName = "$prefix:${packageTool.name}"
            val parameters = packageTool.parameters.map { it.toSchema(context) }
            val entry =
                ToolCatalogEntry(
                    targetToolName = targetToolName,
                    displayName = targetToolName,
                    description = packageTool.description.resolve(context),
                    parameterHints = parameters.map(::buildParameterHint),
                    sourceKind = ToolCatalogSourceKind.PACKAGE,
                    keywords = listOf(prefix, "package", toolPackage.name),
                    parameters = parameters,
                    sourceName = prefix,
                    sourceLocator = "toolpkg://$prefix/${packageTool.name}",
                    sourceEnabled = sourceEnabled
                )
            entries.putIfAbsent(entryKey(entry), entry)
        }
    }

    private fun PackageToolParameter.toSchema(context: Context) =
        ToolParameterSchema(
            name = name,
            type = type,
            description = description.resolve(context),
            required = required
        )

    private fun addCachedMcpToolEntries(
        entries: MutableMap<String, ToolCatalogEntry>,
        serverName: String,
        serverDescription: String,
        cachedTools: List<MCPLocalServer.CachedToolInfo>,
        sourceEnabled: Boolean
    ) {
        cachedTools.forEach { cachedTool ->
            val toolName = cachedTool.name.trim()
            if (toolName.isEmpty()) return@forEach
            val targetToolName = "$serverName:$toolName"
            val parameters = buildCachedMcpParameters(cachedTool.inputSchema)
            val entry =
                ToolCatalogEntry(
                    targetToolName = targetToolName,
                    displayName = targetToolName,
                    description = cachedTool.description.ifBlank { serverDescription },
                    parameterHints = parameters.map(::buildParameterHint),
                    sourceKind = ToolCatalogSourceKind.MCP,
                    keywords = listOf(serverName, "mcp", "cached"),
                    parameters = parameters,
                    sourceName = serverName,
                    sourceLocator = "mcp://$serverName/$toolName",
                    sourceEnabled = sourceEnabled,
                    inputSchema = cachedTool.inputSchema
                )
            entries.putIfAbsent(entryKey(entry), entry)
        }
    }

    private fun buildCachedMcpParameters(inputSchemaJson: String): List<ToolParameterSchema> {
        val schema = runCatching { JSONObject(inputSchemaJson) }.getOrNull() ?: return emptyList()
        val properties = schema.optJSONObject("properties") ?: return emptyList()
        val requiredNames = linkedSetOf<String>()
        schema.optJSONArray("required")?.let { requiredArray ->
            for (index in 0 until requiredArray.length()) {
                requiredArray.optString(index).takeIf { it.isNotBlank() }?.let(requiredNames::add)
            }
        }

        val parameters = mutableListOf<ToolParameterSchema>()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val value = properties.optJSONObject(name)
            parameters +=
                ToolParameterSchema(
                    name = name,
                    type = value?.optString("type").takeUnless { it.isNullOrBlank() } ?: "string",
                    description = value?.optString("description").orEmpty(),
                    required = requiredNames.contains(name),
                    default = value?.opt("default")?.toString()
                )
        }
        return parameters
    }

    internal fun prepareEntry(entry: ToolCatalogEntry): PreparedEntry {
        val normalized = HashMap<String, String>()
        val tokenSets = HashMap<String, Set<String>>()
        fun text(value: String): String = normalized.getOrPut(value) { normalize(value) }
        fun tokens(value: String): Set<String> = tokenSets.getOrPut(value) {
            tokenizeNormalized(text(value)).toSet()
        }
        val displayName = text(entry.displayName)
        val targetName = text(entry.targetToolName)
        val description = text(entry.description)
        val parameterNames = text(entry.parameters.joinToString(" ") { it.name })
        val parameterDescriptions = text(
            entry.parameters.joinToString(" ") { it.description } +
                " " + entry.parameterHints.joinToString(" ")
        )
        val metadata = text(
            (entry.searchMetadata + listOfNotNull(entry.sourceName)).joinToString(" ")
        )

        val displayTokens = tokens(entry.displayName)
        val targetTokens = tokens(entry.targetToolName)
        val descriptionTokens = tokens(entry.description)
        val parameterNameTokens = entry.parameters.flatMap { tokens(it.name) }.toSet()
        val parameterDescriptionTokens =
            (entry.parameters.flatMap { tokens(it.description) } +
                entry.parameterHints.flatMap { tokens(it) }).toSet()
        val metadataTokens =
            (entry.searchMetadata + listOfNotNull(entry.sourceName)).flatMap { tokens(it) }.toSet()

        return PreparedEntry(entry, displayName, targetName, description, parameterNames,
            parameterDescriptions, metadata, displayTokens, targetTokens, descriptionTokens,
            parameterNameTokens, parameterDescriptionTokens, metadataTokens,
            entry.keywords.map { text(it) to tokens(it) },
            entry.searchMetadata.map { text(it) }.toSet(),
            entry.parameters.map { text(it.name) }.toSet())
    }

    private fun scoreEntry(
        document: PreparedEntry,
        normalizedQuery: String,
        terms: List<String>,
        tokenMatches: MutableMap<Pair<String, String>, Boolean>
    ): SearchScore {
        val displayName = document.displayName
        val targetName = document.targetName
        val description = document.description
        val parameterNames = document.parameterNames
        val parameterDescriptions = document.parameterDescriptions
        val metadata = document.metadata
        val displayTokens = document.displayTokens
        val targetTokens = document.targetTokens
        val descriptionTokens = document.descriptionTokens
        val parameterNameTokens = document.parameterNameTokens
        val parameterDescriptionTokens = document.parameterDescriptionTokens
        val metadataTokens = document.metadataTokens

        var score = 0
        var strongIdentityMatch = false
        if (displayName == normalizedQuery || targetName == normalizedQuery) {
            score += 400
            strongIdentityMatch = true
        } else if (displayName.startsWith(normalizedQuery) || targetName.startsWith(normalizedQuery)) {
            score += 180
            strongIdentityMatch = true
        }

        val phraseLike = normalizedQuery.contains(' ') || normalizedQuery.any(::isCommonHanCharacter)
        if (phraseLike) {
            if (description.contains(normalizedQuery)) score += 70
            if (parameterDescriptions.contains(normalizedQuery)) score += 45
            if (metadata.contains(normalizedQuery)) score += 55
        }
        if (document.keywords.any { (keyword, _) ->
                keyword == normalizedQuery && keyword !in GENERIC_KEYWORDS
            }
        ) {
            score += 100
        }
        if (normalizedQuery in document.exactMetadata) score += 90
        if (normalizedQuery in document.exactParameterNames) score += 70

        var matchedTerms = 0
        terms.forEach { term ->
            var termMatched = false
            if (matchesTerm(displayName, displayTokens, term, tokenMatches) ||
                matchesTerm(targetName, targetTokens, term, tokenMatches)
            ) {
                score += 55
                termMatched = true
            }

            val keywordWeight = document.keywords
                .filter { (keyword, tokens) -> matchesTerm(keyword, tokens, term, tokenMatches) }
                .maxOfOrNull { (keyword, _) -> if (keyword in GENERIC_KEYWORDS) 4 else 30 } ?: 0
            if (keywordWeight > 0) {
                score += keywordWeight
                termMatched = true
            }
            if (matchesTerm(description, descriptionTokens, term, tokenMatches)) {
                score += 18
                termMatched = true
            }
            if (matchesTerm(parameterNames, parameterNameTokens, term, tokenMatches)) {
                score += 24
                termMatched = true
            }
            if (matchesTerm(parameterDescriptions, parameterDescriptionTokens, term, tokenMatches)) {
                score += 10
                termMatched = true
            }
            if (matchesTerm(metadata, metadataTokens, term, tokenMatches)) {
                score += 22
                termMatched = true
            }
            if (termMatched) matchedTerms += 1
        }

        if (terms.isNotEmpty()) {
            score += matchedTerms * 50 / terms.size
            if (matchedTerms == terms.size) score += 60
            else if (matchedTerms >= 2) score += 20
        }
        return SearchScore(score, matchedTerms, terms.size, strongIdentityMatch)
    }

    private fun isRelevant(score: SearchScore): Boolean {
        if (score.strongIdentityMatch) return true
        if (score.score < MIN_SEARCH_SCORE) return false
        if (score.totalTerms >= 4 && score.matchedTerms < 2) return false
        return score.matchedTerms > 0
    }

    private fun matchesTerm(
        normalizedField: String,
        fieldTokens: Set<String>,
        term: String,
        tokenMatches: MutableMap<Pair<String, String>, Boolean>
    ): Boolean {
        if (term.any(::isCommonHanCharacter)) {
            return normalizedField.contains(term)
        }
        if (fieldTokens.contains(term)) return true
        return fieldTokens.any { candidate ->
            tokenMatches.getOrPut(candidate to term) { fuzzyTokenMatch(candidate, term) }
        }
    }

    private fun fuzzyTokenMatch(candidate: String, query: String): Boolean {
        if (candidate == query) return true
        val shorter = minOf(candidate.length, query.length)
        val longer = maxOf(candidate.length, query.length)
        if (shorter >= 3 && (candidate.startsWith(query) || query.startsWith(candidate))) {
            return shorter.toDouble() / longer.toDouble() >= 0.6
        }
        if (shorter < 4) return false

        val maxDistance = when {
            longer <= 5 -> 1
            longer <= 9 -> 2
            else -> 3
        }
        if (kotlin.math.abs(candidate.length - query.length) > maxDistance) return false
        val distance = boundedDamerauLevenshtein(candidate, query, maxDistance)
        if (distance > maxDistance) return false
        return 1.0 - distance.toDouble() / longer.toDouble() >= 0.68
    }

    private fun boundedDamerauLevenshtein(left: String, right: String, maxDistance: Int): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        if (kotlin.math.abs(left.length - right.length) > maxDistance) return maxDistance + 1

        var previousPrevious = IntArray(right.length + 1)
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..right.length) {
                val substitutionCost = if (left[i - 1] == right[j - 1]) 0 else 1
                var value = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + substitutionCost
                )
                if (i > 1 && j > 1 &&
                    left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]
                ) {
                    value = minOf(value, previousPrevious[j - 2] + 1)
                }
                current[j] = value
                rowMin = minOf(rowMin, value)
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previousPrevious
            previousPrevious = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun entryKey(entry: ToolCatalogEntry): String =
        if (entry.sourceKind == ToolCatalogSourceKind.ACTIVATION) {
            "${entry.sourceKind}:${entry.targetToolName}:${entry.displayName}"
        } else {
            entry.targetToolName
        }

    private fun buildSearchTerms(normalizedQuery: String): List<String> =
        tokenize(normalizedQuery)
            .filterNot { it in ENGLISH_STOP_WORDS }
            .filter { it.any(::isCommonHanCharacter) || it.length >= 2 }
            .flatMap { token ->
                if (token.length > 2 && token.any(::isCommonHanCharacter)) {
                    listOf(token) + token.windowed(size = 2, step = 1)
                } else {
                    listOf(token)
                }
            }
            .distinct()

    private fun tokenize(value: String): List<String> =
        tokenizeNormalized(normalize(value))

    private fun tokenizeNormalized(value: String): List<String> =
        value.replace(TOKEN_SEPARATORS, " ")
            .split(' ')
            .filter { it.isNotBlank() }

    private fun isCommonHanCharacter(character: Char): Boolean =
        character in '\u3400'..'\u4DBF' || character in '\u4E00'..'\u9FFF'

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(NON_SEARCH_CHARACTERS, " ")
            .replace(WHITESPACE, " ")
            .trim()
}
