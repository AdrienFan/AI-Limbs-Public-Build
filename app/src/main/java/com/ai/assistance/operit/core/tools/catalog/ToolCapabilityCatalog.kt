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
    private val RESERVED_TARGETS = setOf("search", "proxy", "package_proxy")
    private val ENGLISH_STOP_WORDS = setOf(
        "a", "an", "the", "by", "to", "for", "of", "with", "from", "on", "in", "at", "via"
    )
    private val GENERIC_KEYWORDS = setOf(
        "package", "cached", "activate", "activation", "internal", "built-in", "builtin", "内部工具"
    )
    private const val MIN_SEARCH_SCORE = 20
    private const val LOW_CONFIDENCE_SCORE = 70

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

        val availablePackages = packageManager.getAvailablePackages(forceRefreshPackages)
        val packageNames =
            if (includeDisabledPackages) {
                availablePackages.keys
            } else {
                packageManager.getEnabledPackageNames()
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
                    packageManager.getEffectivePackageTools(packageName)
                        ?: availablePackages[packageName]
                        ?: return@forEach
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

        val skillPackages =
            SkillRepository.getInstance(context)
                .getAiVisibleSkillPackages()
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

        val mcpServers =
            packageManager.getAvailableServerPackages()
                .filterKeys { roleCardToolAccess?.isExternalSourceAllowed(it) != false }
        val mcpLocalServer = MCPLocalServer.getInstance(context)

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

        return entries.values.toList()
    }

    fun search(catalog: List<ToolCatalogEntry>, query: String, limit: Int): List<ToolCatalogEntry> =
        searchDetailed(catalog, query, limit).matches.map { it.entry }

    fun searchDetailed(
        catalog: List<ToolCatalogEntry>,
        query: String,
        limit: Int
    ): ToolCatalogSearchResult {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return ToolCatalogSearchResult(emptyList(), lowConfidence = true)
        }
        val terms = buildSearchTerms(normalizedQuery)
        if (terms.isEmpty()) {
            return ToolCatalogSearchResult(emptyList(), lowConfidence = true)
        }

        val matches = catalog
            .mapNotNull { entry ->
                val scored = scoreEntry(entry, normalizedQuery, terms)
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
            .take(limit.coerceIn(1, 20))

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

    private fun runtimeParameterSchemas(toolName: String): List<ToolParameterSchema> = when (toolName) {
        "ubuntu.idle.set" -> listOf(
            ToolParameterSchema(
                name = "mode",
                type = "string",
                description = "KEEP_RUNNING, MINUTES_10, MINUTES_15, MINUTES_30, MINUTES_60, or CUSTOM",
                required = true
            ),
            ToolParameterSchema(
                name = "custom_minutes",
                type = "integer",
                description = "Required for CUSTOM; allowed range is 1 to 1440 minutes",
                required = false
            )
        )
        else -> emptyList()
    }

    private fun runtimeKeywords(toolName: String): List<String> = when {
        toolName.startsWith("ubuntu.") ->
            listOf("ubuntu", "linux", "沙箱", "开机", "关机", "启动", "停止", "空闲")
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

    private fun scoreEntry(
        entry: ToolCatalogEntry,
        normalizedQuery: String,
        terms: List<String>
    ): SearchScore {
        val displayName = normalize(entry.displayName)
        val targetName = normalize(entry.targetToolName)
        val description = normalize(entry.description)
        val parameterNames = normalize(entry.parameters.joinToString(" ") { it.name })
        val parameterDescriptions = normalize(
            entry.parameters.joinToString(" ") { it.description } +
                " " + entry.parameterHints.joinToString(" ")
        )
        val metadata = normalize(entry.searchMetadata.joinToString(" "))

        val displayTokens = tokenize(entry.displayName).toSet()
        val targetTokens = tokenize(entry.targetToolName).toSet()
        val descriptionTokens = tokenize(entry.description).toSet()
        val parameterNameTokens = entry.parameters.flatMap { tokenize(it.name) }.toSet()
        val parameterDescriptionTokens =
            (entry.parameters.flatMap { tokenize(it.description) } +
                entry.parameterHints.flatMap(::tokenize)).toSet()
        val metadataTokens = entry.searchMetadata.flatMap(::tokenize).toSet()

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
        if (entry.keywords.any {
                normalize(it) == normalizedQuery && normalize(it) !in GENERIC_KEYWORDS
            }
        ) {
            score += 100
        }
        if (entry.searchMetadata.any { normalize(it) == normalizedQuery }) score += 90
        if (entry.parameters.any { normalize(it.name) == normalizedQuery }) score += 70

        var matchedTerms = 0
        terms.forEach { term ->
            var termMatched = false
            if (matchesTerm(displayName, displayTokens, term) ||
                matchesTerm(targetName, targetTokens, term)
            ) {
                score += 55
                termMatched = true
            }

            val keywordWeight = entry.keywords
                .filter { keyword ->
                    matchesTerm(normalize(keyword), tokenize(keyword).toSet(), term)
                }
                .maxOfOrNull { keyword ->
                    if (normalize(keyword) in GENERIC_KEYWORDS) 4 else 30
                } ?: 0
            if (keywordWeight > 0) {
                score += keywordWeight
                termMatched = true
            }
            if (matchesTerm(description, descriptionTokens, term)) {
                score += 18
                termMatched = true
            }
            if (matchesTerm(parameterNames, parameterNameTokens, term)) {
                score += 24
                termMatched = true
            }
            if (matchesTerm(parameterDescriptions, parameterDescriptionTokens, term)) {
                score += 10
                termMatched = true
            }
            if (matchesTerm(metadata, metadataTokens, term)) {
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
        term: String
    ): Boolean =
        if (term.any(::isCommonHanCharacter)) {
            normalizedField.contains(term)
        } else {
            fieldTokens.contains(term)
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
        normalize(value)
            .replace(Regex("[:_./-]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() }

    private fun isCommonHanCharacter(character: Char): Boolean =
        character in '\u3400'..'\u4DBF' || character in '\u4E00'..'\u9FFF'

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}:_./-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
