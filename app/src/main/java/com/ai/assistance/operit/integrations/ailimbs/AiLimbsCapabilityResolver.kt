package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.catalog.ToolCapabilityCatalog
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

private data class AiLimbsCapabilityAvailability(
    val available: Boolean,
    val permission: PermissionLevel,
    val requiresConfirmation: Boolean,
    val reason: String? = null,
    val resolution: String? = null,
    val prerequisites: List<String> = emptyList()
)

private data class AiLimbsCapabilitySearchResult(
    val matches: List<AiLimbsCapabilityDefinition>,
    val lowConfidence: Boolean
)

/** Read-only discovery surface for all AI Limbs capabilities. */
class AiLimbsCapabilityResolver(context: Context) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val packageManager = handler.getOrCreatePackageManager()
    private val permissionSystem = ToolPermissionSystem.getInstance(appContext)
    private val uiCapabilities = AiLimbsUiCapabilityService(appContext)

    suspend fun search(query: String, requestedLimit: Int): JSONObject {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return error("Missing capability search query")
        val limit = requestedLimit.coerceIn(1, MAX_SEARCH_RESULTS)

        var definitions = buildDefinitions(forceRefreshPackages = false)
        var searchResult = searchDefinitions(definitions, normalizedQuery, limit)
        var usedLiveDiscovery = false
        if (searchResult.lowConfidence) {
            definitions = buildDefinitions(forceRefreshPackages = true)
            searchResult = searchDefinitions(definitions, normalizedQuery, limit)
            usedLiveDiscovery = true
        }

        val results = JSONArray()
        for (definition in searchResult.matches) {
            results.put(compactCard(definition, readAvailability(definition)))
        }
        return ok()
            .put("module", MODULE_NAME)
            .put("protocol_version", CAPABILITY_PROTOCOL_VERSION)
            .put("query", normalizedQuery)
            .put("count", results.length())
            .put("live_discovery", usedLiveDiscovery)
            .put("results", results)
            .put(
                "next",
                if (results.length() == 0) {
                    "No matching capability is currently installed or registered."
                } else {
                    "Call capability.describe with a capability_id when parameters or prerequisites are needed."
                }
            )
    }

    suspend fun describe(identifier: String): JSONObject {
        val normalizedIdentifier = identifier.trim()
        if (normalizedIdentifier.isEmpty()) return error("Missing capability_id")

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
            .put("resolution", availability.resolution ?: JSONObject.NULL)
            .put("source_locator", sourceLocator(definition))
            .put("version", BuildConfig.VERSION_NAME)
            .put("minimal_example", minimalExample(definition))
            .put("error_guidance", errorGuidance(definition, availability))
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
        val catalog = AiLimbsCoreCapabilityRegistry.mergeInto(runtimeCatalog)

        return catalog
            .distinctBy { catalogIdentity(it) }
            .map(::toDefinition)
    }

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
            definitionsByIdentity[catalogIdentity(match.entry)]
        }
        return AiLimbsCapabilitySearchResult(matches, catalogResult.lowConfidence)
    }

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
        val coreRegistration = AiLimbsCoreCapabilityRegistry.registrationForInvokeName(invokeId)
        val semantic = semanticMetadata(invokeId)
        val capabilityId = coreRegistration?.capabilityId ?: semantic?.capabilityId ?: generatedId
        val aliases =
            buildList {
                add(generatedId)
                coreRegistration?.invokeAliases?.let(::addAll)
                coreRegistration?.capabilityAliases?.let(::addAll)
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
    ): AiLimbsCapabilityAvailability {
        val entry = definition.catalogEntry
        val coreRegistration = AiLimbsCoreCapabilityRegistry.registrationForInvokeName(definition.invokeId)
        val permission = effectivePermission(definition)
        val prerequisites = mutableListOf<String>()

        if (!entry.sourceEnabled) {
            val packageOrServer = entry.sourceName ?: definition.provider
            return AiLimbsCapabilityAvailability(
                available = false,
                permission = permission,
                requiresConfirmation = false,
                reason = "$packageOrServer is disabled.",
                resolution = "Enable $packageOrServer in AI Limbs, then search again.",
                prerequisites = listOf("source_enabled")
            )
        }

        if (permission == PermissionLevel.FORBID) {
            return AiLimbsCapabilityAvailability(
                available = false,
                permission = permission,
                requiresConfirmation = false,
                reason = "ToolPermissionSystem forbids ${definition.invokeId}.",
                resolution = "Change the tool permission from FORBID before invoking it.",
                prerequisites = listOf("permission_not_forbid")
            )
        }

        if (definition.invokeId.startsWith(AUTOMATIC_UI_BASE_PREFIX)) {
            val status = uiCapabilities.readStatus()
            prerequisites += "Automatic_ui_base enabled"
            prerequisites += "direct_ui_ready=true"
            if (!status.automaticUiBaseEnabled) {
                return AiLimbsCapabilityAvailability(
                    available = false,
                    permission = permission,
                    requiresConfirmation = false,
                    reason = "Automatic_ui_base is disabled.",
                    resolution = "Enable the Automatic_ui_base ToolPkg.",
                    prerequisites = prerequisites
                )
            }
            if (!status.directUiReady) {
                return AiLimbsCapabilityAvailability(
                    available = false,
                    permission = permission,
                    requiresConfirmation = false,
                    reason = "The selected UI backend is not ready.",
                    resolution = status.nextAction ?: "Authorize an AI Limbs UI backend.",
                    prerequisites = prerequisites
                )
            }
        }

        if (definition.invokeId.startsWith(AUTOMATIC_UI_SUBAGENT_PREFIX)) {
            val status = uiCapabilities.readStatus()
            prerequisites += "Automatic_ui_subagent enabled"
            prerequisites += "ui_subagent_ready=true"
            if (!status.uiSubagentReady) {
                return AiLimbsCapabilityAvailability(
                    available = false,
                    permission = permission,
                    requiresConfirmation = false,
                    reason = "The UI visual subagent is not ready.",
                    resolution = status.nextAction ?: "Configure the UI controller model.",
                    prerequisites = prerequisites
                )
            }
        }

        if (definition.provider == PROVIDER_BRIDGE) {
            val bridge = AiLimbsBridgeManager.runtimeState.value
            prerequisites += "Bridge provider: ${bridge.providerId}"
            prerequisites += "Bridge phase: ${bridge.phase.name}"
            if (
                coreRegistration?.availabilityPolicy == AiLimbsCoreAvailabilityPolicy.BRIDGE_RECONNECT &&
                    BridgeAction.RECONNECT !in AiLimbsBridgeManager.availableActions(appContext, bridge)
            ) {
                return AiLimbsCapabilityAvailability(
                    available = false,
                    permission = permission,
                    requiresConfirmation = false,
                    reason = "Bridge reconnect is not available while ${bridge.providerId} is ${bridge.phase.name}.",
                    resolution = "Wait for a reconnectable Bridge phase or use the local Bridge Center recovery controls.",
                    prerequisites = prerequisites
                )
            }
        }

        if (definition.provider == PROVIDER_UBUNTU) {
            val terminal = Terminal.getInstance(appContext)
            val phase = terminal.currentUbuntuRuntimeState().phase.name
            prerequisites += "Ubuntu runtime state: $phase"
            when (coreRegistration?.availabilityPolicy ?: AiLimbsCoreAvailabilityPolicy.DEFAULT) {
                AiLimbsCoreAvailabilityPolicy.UBUNTU_START -> when (phase) {
                    "STARTING", "STOPPING" ->
                        return transitionalUbuntuAvailability(permission, phase)
                    "RUNNING" ->
                        return AiLimbsCapabilityAvailability(
                            available = false,
                            permission = permission,
                            requiresConfirmation = false,
                            reason = "Ubuntu is already RUNNING.",
                            resolution = "Use the existing Ubuntu runtime or call ubuntu.status.",
                            prerequisites = prerequisites
                        )
                    else -> Unit
                }
                AiLimbsCoreAvailabilityPolicy.UBUNTU_STOP -> {
                    if (phase != "RUNNING") {
                        return AiLimbsCapabilityAvailability(
                            available = false,
                            permission = permission,
                            requiresConfirmation = false,
                            reason = "Ubuntu is $phase.",
                            resolution = "Call ubuntu.start before requesting a stop.",
                            prerequisites = prerequisites
                        )
                    }
                    val usage = terminal.currentUbuntuUsageState()
                    prerequisites += "Ubuntu participants: ${usage.participantCount}"
                    if (usage.userInterfaceClients > 0 || usage.hiddenAiOperations > 0) {
                        return AiLimbsCapabilityAvailability(
                            available = false,
                            permission = permission,
                            requiresConfirmation = false,
                            reason = "Ubuntu is still being used by another interface or hidden operation.",
                            resolution = "Wait until the other Ubuntu participant leaves before calling ubuntu.stop.",
                            prerequisites = prerequisites
                        )
                    }
                }
                AiLimbsCoreAvailabilityPolicy.UBUNTU_STATUS,
                AiLimbsCoreAvailabilityPolicy.UBUNTU_IDLE_POLICY -> Unit
                AiLimbsCoreAvailabilityPolicy.DEFAULT,
                AiLimbsCoreAvailabilityPolicy.BRIDGE_RECONNECT -> if (phase != "RUNNING") {
                    return AiLimbsCapabilityAvailability(
                        available = false,
                        permission = permission,
                        requiresConfirmation = false,
                        reason = "Ubuntu is $phase.",
                        resolution = "Call ubuntu.start first.",
                        prerequisites = prerequisites
                    )
                }
            }
        }

        return AiLimbsCapabilityAvailability(
            available = true,
            permission = permission,
            requiresConfirmation = permission == PermissionLevel.ASK,
            prerequisites = prerequisites
        )
    }

    private suspend fun effectivePermission(definition: AiLimbsCapabilityDefinition): PermissionLevel {
        if (definition.provider == PROVIDER_CORE) return PermissionLevel.ALLOW
        return permissionSystem.getToolPermissionOverride(definition.invokeId)
            ?: permissionSystem.masterSwitchFlow.first()
    }

    private fun transitionalUbuntuAvailability(
        permission: PermissionLevel,
        phase: String
    ) = AiLimbsCapabilityAvailability(
        available = false,
        permission = permission,
        requiresConfirmation = false,
        reason = "Ubuntu is currently $phase.",
        resolution = "Wait for the lifecycle transition to finish, then query ubuntu.status.",
        prerequisites = listOf("Ubuntu lifecycle transition complete")
    )

    private fun compactCard(
        definition: AiLimbsCapabilityDefinition,
        availability: AiLimbsCapabilityAvailability
    ): JSONObject =
        JSONObject()
            .put("capability_id", definition.capabilityId)
            .put("display_name", definition.displayName)
            .put("provider", definition.provider)
            .put("invoke_id", definition.invokeId)
            .put("availability", availabilityLabel(availability))
            .put("requires_confirmation", availability.requiresConfirmation)
            .apply {
                availability.reason?.let { put("reason", it) }
                availability.resolution?.let { put("resolution", it) }
            }

    private fun availabilityLabel(availability: AiLimbsCapabilityAvailability): String =
        if (availability.available) "available" else "unavailable"

    private fun permissionJson(availability: AiLimbsCapabilityAvailability): JSONObject =
        JSONObject()
            .put("effective", availability.permission.name)
            .put("requires_confirmation", availability.requiresConfirmation)
            .put("enforced_by", "ToolPermissionSystem")

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
        return JSONObject()
            .put("transport", "shell=operit")
            .put("request", JSONObject().put("name", definition.invokeId).put("parameters", parameters))
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
        availability: AiLimbsCapabilityAvailability
    ): JSONObject = JSONObject()
        .put(
            "before_invoke",
            availability.resolution
                ?: "Invoke through the normal dispatcher; ALLOW / ASK / FORBID remains enforced."
        )
        .put(
            "on_unknown_invocation",
            "Call capability.search again; do not guess a replacement tool name."
        )
        .put("invoke_id", definition.invokeId)

    private fun providerFor(entry: ToolCatalogEntry): String {
        AiLimbsCoreCapabilityRegistry.registrationForInvokeName(entry.targetToolName)?.let { registration ->
            return when (registration.provider) {
                AiLimbsCoreProvider.CORE -> PROVIDER_CORE
                AiLimbsCoreProvider.BRIDGE -> PROVIDER_BRIDGE
                AiLimbsCoreProvider.UBUNTU -> PROVIDER_UBUNTU
            }
        }
        return when {
            isUbuntuBackedTool(entry.targetToolName) -> PROVIDER_UBUNTU
            entry.sourceKind == ToolCatalogSourceKind.PACKAGE -> "toolpkg"
            entry.sourceKind == ToolCatalogSourceKind.MCP -> "mcp"
            entry.sourceKind == ToolCatalogSourceKind.ACTIVATION -> "activation"
            else -> "native"
        }
    }

    private fun sourceLocator(definition: AiLimbsCapabilityDefinition): String = when {
        AiLimbsCoreCapabilityRegistry.isRegisteredInvokeName(definition.invokeId) ->
            definition.catalogEntry.sourceLocator ?: "registry://${definition.invokeId}"
        definition.invokeId.startsWith(AUTOMATIC_UI_BASE_PREFIX) ->
            "assets://packages/automatic_ui_base.js#${definition.invokeId.substringAfter(':')}"
        definition.invokeId.startsWith(AUTOMATIC_UI_SUBAGENT_PREFIX) ->
            "assets://packages/automatic_ui_subagent.js#${definition.invokeId.substringAfter(':')}"
        definition.provider == PROVIDER_UBUNTU -> "ubuntu://terminal/${definition.invokeId}"
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

    private fun isUbuntuBackedTool(invokeId: String): Boolean =
        UBUNTU_TERMINAL_TOOLS.contains(invokeId)

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
        const val CAPABILITY_PROTOCOL_VERSION = 1
        const val MAX_SEARCH_RESULTS = 5
        const val PROVIDER_CORE = AiLimbsCoreCapabilityRegistry.CORE_PROVIDER
        const val PROVIDER_BRIDGE = AiLimbsCoreCapabilityRegistry.BRIDGE_PROVIDER
        const val PROVIDER_UBUNTU = AiLimbsCoreCapabilityRegistry.UBUNTU_PROVIDER
        const val AUTOMATIC_UI_BASE_PREFIX = "Automatic_ui_base:"
        const val AUTOMATIC_UI_SUBAGENT_PREFIX = "Automatic_ui_subagent:"

        val UBUNTU_TERMINAL_TOOLS = setOf(
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
