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
    val inputSchema: String? = null
)

/**
 * Builds one catalog from Operit's structured prompts, runtime registry, ToolPkg metadata, skills,
 * and cached MCP schemas. It never starts Ubuntu or an MCP server merely to discover metadata.
 */
object ToolCapabilityCatalog {
    private val RESERVED_TARGETS = setOf("search", "proxy", "package_proxy")

    suspend fun build(
        context: Context,
        packageManager: PackageManager,
        roleCardToolAccess: ResolvedCharacterCardToolAccess? = null,
        useEnglish: Boolean,
        includeDisabledPackages: Boolean = false,
        forceRefreshPackages: Boolean = false
    ): List<ToolCatalogEntry> {
        val categories = buildBuiltinAndInternalCategories(useEnglish)
        val builtinToolNames = buildBuiltinToolNameSet(useEnglish)
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

    fun search(catalog: List<ToolCatalogEntry>, query: String, limit: Int): List<ToolCatalogEntry> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val terms = buildSearchTerms(normalizedQuery)
        return catalog
            .mapNotNull { entry ->
                val score = scoreEntry(entry, normalizedQuery, terms)
                if (score <= 0) null else score to entry
            }
            .sortedWith(
                compareByDescending<Pair<Int, ToolCatalogEntry>> { it.first }
                    .thenBy { it.second.targetToolName }
                    .thenBy { it.second.displayName }
            )
            .take(limit.coerceIn(1, 20))
            .map { it.second }
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
    ): Int {
        val displayName = normalize(entry.displayName)
        val targetName = normalize(entry.targetToolName)
        val description = normalize(entry.description)
        val params = normalize(entry.parameterHints.joinToString(" "))
        val keywords = normalize(entry.keywords.joinToString(" "))
        val individualKeywords = entry.keywords.map(::normalize).filter { it.length >= 2 }

        var score = 0
        if (displayName == normalizedQuery || targetName == normalizedQuery) score += 300
        if (displayName.startsWith(normalizedQuery) || targetName.startsWith(normalizedQuery)) score += 140
        if (displayName.contains(normalizedQuery) || targetName.contains(normalizedQuery)) score += 100
        if (description.contains(normalizedQuery) || keywords.contains(normalizedQuery)) score += 40
        if (params.contains(normalizedQuery)) score += 25
        individualKeywords.forEach { keyword ->
            if (normalizedQuery.contains(keyword) || keyword.contains(normalizedQuery)) score += 24
        }

        var matchedTerms = 0
        terms.forEach { term ->
            var termMatched = false
            if (displayName.contains(term) || targetName.contains(term)) {
                score += 40
                termMatched = true
            }
            if (keywords.contains(term)) {
                score += 16
                termMatched = true
            }
            if (description.contains(term)) {
                score += 12
                termMatched = true
            }
            if (params.contains(term)) {
                score += 8
                termMatched = true
            }
            if (termMatched) matchedTerms += 1
        }
        if (matchedTerms == terms.size && terms.isNotEmpty()) score += 30
        return score
    }

    private fun entryKey(entry: ToolCatalogEntry): String =
        if (entry.sourceKind == ToolCatalogSourceKind.ACTIVATION) {
            "${entry.sourceKind}:${entry.targetToolName}:${entry.displayName}"
        } else {
            entry.targetToolName
        }

    private fun buildSearchTerms(normalizedQuery: String): List<String> =
        normalizedQuery
            .split(' ')
            .filter { it.isNotBlank() }
            .flatMap { token ->
                if (token.length > 2 && token.any(::isCommonHanCharacter)) {
                    listOf(token) + token.windowed(size = 2, step = 1)
                } else {
                    listOf(token)
                }
            }
            .distinct()

    private fun isCommonHanCharacter(character: Char): Boolean =
        character in '\u3400'..'\u4DBF' || character in '\u4E00'..'\u9FFF'

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}:_./-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
