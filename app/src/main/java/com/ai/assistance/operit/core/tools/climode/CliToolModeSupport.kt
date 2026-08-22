package com.ai.assistance.operit.core.tools.climode

import android.content.Context
import com.ai.assistance.operit.core.tools.catalog.ToolCapabilityCatalog
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess

enum class ToolExposureMode {
    FULL,
    CLI;

    companion object {
        fun resolve(providerType: ApiProviderType): ToolExposureMode {
            return when (providerType) {
                ApiProviderType.LMSTUDIO,
                ApiProviderType.OLLAMA,
                ApiProviderType.OPENAI_LOCAL,
                ApiProviderType.MNN,
                ApiProviderType.LLAMA_CPP -> CLI
                else -> FULL
            }
        }
    }
}

typealias HiddenToolSourceKind = ToolCatalogSourceKind
typealias HiddenToolCatalogEntry = ToolCatalogEntry

object CliToolModeSupport {
    const val SEARCH_TOOL_NAME = "search"
    const val PROXY_TOOL_NAME = "proxy"
    const val PACKAGE_PROXY_TOOL_NAME = "package_proxy"
    private const val DEFAULT_SEARCH_LIMIT = 8
    private val PUBLIC_TOOL_NAMES = linkedSetOf(SEARCH_TOOL_NAME, PROXY_TOOL_NAME)
    private val RESERVED_PROXY_TARGETS =
        linkedSetOf(SEARCH_TOOL_NAME, PROXY_TOOL_NAME, PACKAGE_PROXY_TOOL_NAME)

    fun isCliPublicTool(toolName: String): Boolean {
        return PUBLIC_TOOL_NAMES.contains(toolName.trim())
    }

    fun isReservedProxyTarget(toolName: String): Boolean {
        return RESERVED_PROXY_TARGETS.contains(toolName.trim())
    }

    fun defaultSearchLimit(): Int = DEFAULT_SEARCH_LIMIT

    fun buildCliPublicToolPrompts(useEnglish: Boolean): List<ToolPrompt> {
        return if (useEnglish) {
            listOf(
                ToolPrompt(
                    name = SEARCH_TOOL_NAME,
                    description = "Search the hidden tool catalog only. Use this first to discover hidden tool names and parameter shapes.",
                    parametersStructured = listOf(
                        ToolParameterSchema(
                            name = "query",
                            type = "string",
                            description = "tool capability or hidden tool name to search for",
                            required = true
                        ),
                        ToolParameterSchema(
                            name = "limit",
                            type = "integer",
                            description = "optional, max results to return",
                            required = false,
                            default = DEFAULT_SEARCH_LIMIT.toString()
                        )
                    )
                ),
                ToolPrompt(
                    name = PROXY_TOOL_NAME,
                    description = "Execute a hidden tool after you discover its target tool name and parameter shape via search.",
                    parametersStructured = listOf(
                        ToolParameterSchema(
                            name = "tool_name",
                            type = "string",
                            description = "hidden target tool name, for example read_file or packageName:toolName",
                            required = true
                        ),
                        ToolParameterSchema(
                            name = "params",
                            type = "object",
                            description = "JSON object of parameters to forward to the hidden target tool",
                            required = true
                        )
                    )
                )
            )
        } else {
            listOf(
                ToolPrompt(
                    name = SEARCH_TOOL_NAME,
                    description = "Search the hidden tool catalog only. Use this first to discover hidden tool names and parameter shapes.",
                    parametersStructured = listOf(
                        ToolParameterSchema(
                            name = "query",
                            type = "string",
                            description = "tool capability or hidden tool name to search for",
                            required = true
                        ),
                        ToolParameterSchema(
                            name = "limit",
                            type = "integer",
                            description = "optional, max results to return",
                            required = false,
                            default = DEFAULT_SEARCH_LIMIT.toString()
                        )
                    )
                ),
                ToolPrompt(
                    name = PROXY_TOOL_NAME,
                    description = "Execute a hidden tool after you discover its target tool name and parameter shape via search.",
                    parametersStructured = listOf(
                        ToolParameterSchema(
                            name = "tool_name",
                            type = "string",
                            description = "hidden target tool name, e.g. read_file or packageName:toolName",
                            required = true
                        ),
                        ToolParameterSchema(
                            name = "params",
                            type = "object",
                            description = "JSON params object forwarded to the hidden target tool",
                            required = true
                        )
                    )
                )
            )
        }
    }

    fun buildCliModePrompt(useEnglish: Boolean): String {
        val intro =
            if (useEnglish) {
                """
                CLI TOOL MODE
                - Only two public tools are available: `search` and `proxy`.
                - `search` only searches the hidden tool catalog. It does not read files, search code, or browse the web.
                - All real capabilities are hidden behind `proxy`.
                - Do not call hidden tools directly. Use `search` first, then call `proxy` with the discovered target tool name and JSON params.
                """.trimIndent()
            } else {
                """
                CLI TOOL MODE
                - Only two public tools are available: `search` and `proxy`.
                - `search` only searches the hidden tool catalog. It does not read files, search code, or browse the web.
                - All real capabilities are hidden behind `proxy`.
                - Do not call hidden tools directly. Use `search` first, then call `proxy` with the discovered target tool name and JSON params.
                """.trimIndent()
            }

        val category =
            SystemToolPromptCategory(
                categoryName = "Public tools",
                tools = buildCliPublicToolPrompts(useEnglish)
            ).toString()

        return "$intro\n\n$category"
    }

    suspend fun buildHiddenToolCatalog(
        context: Context,
        packageManager: PackageManager,
        roleCardToolAccess: ResolvedCharacterCardToolAccess,
        useEnglish: Boolean
    ): List<HiddenToolCatalogEntry> =
        ToolCapabilityCatalog.build(
            context = context,
            packageManager = packageManager,
            roleCardToolAccess = roleCardToolAccess,
            useEnglish = useEnglish
        )

    fun searchHiddenToolCatalog(
        catalog: List<HiddenToolCatalogEntry>,
        query: String,
        limit: Int
    ): List<HiddenToolCatalogEntry> = ToolCapabilityCatalog.search(catalog, query, limit)

    fun formatSearchResults(
        query: String,
        results: List<HiddenToolCatalogEntry>,
        useEnglish: Boolean
    ): String {
        if (results.isEmpty()) {
            return if (useEnglish) {
                "No hidden tools matched \"$query\". Try a broader capability keyword, then call proxy with a discovered target tool name."
            } else {
                "No hidden tools matched \"$query\". Try a broader capability keyword, then call proxy with a discovered target tool name."
            }
        }

        return buildString {
            if (useEnglish) {
                appendLine("Hidden tool search results for \"$query\":")
            } else {
                appendLine("Hidden tool search results for \"$query\":")
            }
            results.forEachIndexed { index, entry ->
                append(index + 1)
                append(". `")
                append(entry.displayName)
                append("` [")
                append(entry.sourceKind.label(useEnglish))
                appendLine("]")
                append("   ")
                appendLine(entry.description.ifBlank {
                    "No description."
                })
                append("   ")
                append("Target: `")
                append(entry.targetToolName)
                appendLine("`")
                if (!entry.suggestedParamsJson.isNullOrBlank()) {
                    append("   ")
                    append("Params hint: `")
                    append(entry.suggestedParamsJson)
                    appendLine("`")
                } else if (entry.parameterHints.isNotEmpty()) {
                    append("   ")
                    append("Params: ")
                    appendLine(entry.parameterHints.joinToString("; "))
                }
            }
        }.trimEnd()
    }

    fun buildCliTopLevelRestrictionErrorMessage(
        attemptedToolName: String,
        useEnglish: Boolean
    ): String {
        return if (useEnglish) {
            "Tool '$attemptedToolName' is hidden in CLI tool mode. Use 'search' to find the hidden target tool, then call 'proxy'."
        } else {
            "Tool '$attemptedToolName' is hidden in CLI tool mode. Use 'search' to find the hidden target tool, then call 'proxy'."
        }
    }

    fun buildCliModeUnavailableMessage(useEnglish: Boolean): String {
        return if (useEnglish) {
            "This tool is only available in CLI tool mode."
        } else {
            "This tool is only available in CLI tool mode."
        }
    }

    fun buildProxyTargetUnavailableMessage(
        targetToolName: String,
        useEnglish: Boolean
    ): String {
        return if (useEnglish) {
            "Hidden target tool '$targetToolName' is unavailable. Use 'search' first to discover a valid hidden tool name and params."
        } else {
            "Hidden target tool '$targetToolName' is unavailable. Use 'search' first to discover a valid hidden tool name and params."
        }
    }

    fun buildReservedProxyTargetMessage(
        targetToolName: String,
        useEnglish: Boolean
    ): String {
        return if (useEnglish) {
            "Hidden target tool '$targetToolName' is reserved and cannot be called through proxy."
        } else {
            "Hidden target tool '$targetToolName' is reserved and cannot be called through proxy."
        }
    }

    fun buildRoleAccessDeniedMessage(useEnglish: Boolean): String {
        return if (useEnglish) {
            "The current role card is not allowed to access this hidden tool."
        } else {
            "The current role card is not allowed to access this hidden tool."
        }
    }

    fun isToolNameAllowedForRoleCard(
        toolName: String,
        usePackageSourceName: String?,
        roleCardToolAccess: ResolvedCharacterCardToolAccess
    ): Boolean {
        return when {
            toolName == "use_package" -> {
                if (!roleCardToolAccess.isBuiltinToolAllowed("use_package")) {
                    false
                } else {
                    usePackageSourceName.isNullOrBlank() ||
                        roleCardToolAccess.isExternalSourceAllowed(usePackageSourceName)
                }
            }
            toolName.contains(':') -> {
                val sourceName = toolName.substringBefore(':').trim()
                sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
            }
            else -> roleCardToolAccess.isBuiltinToolAllowed(toolName)
        }
    }

}
