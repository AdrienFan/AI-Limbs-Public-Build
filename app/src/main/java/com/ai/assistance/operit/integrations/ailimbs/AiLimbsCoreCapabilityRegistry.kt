package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema

/**
 * Authoritative catalog for capabilities implemented directly by AI Limbs.
 *
 * Registration is intentionally centralized: adding a dispatcher route without a matching entry
 * makes the feature undiscoverable to Capability Resolver clients. Every new AI Limbs-owned
 * callable must therefore be added here in the same change that introduces its route.
 */
object AiLimbsCoreCapabilityRegistry {
    const val CORE_PROVIDER = "ai_limbs_core"

    private val registrations: List<ToolCatalogEntry> = listOf(
        entry(
            name = "capability.search",
            displayName = "AI Limbs Capability Resolver · 能力搜索",
            description = "Search the current AI Limbs capability catalog without executing a capability.",
            parameters = listOf(
                ToolParameterSchema("query", "string", "Capability intent or known tool or module name", true),
                ToolParameterSchema("limit", "integer", "Maximum result count from 1 to 5", false, "5")
            ),
            keywords = listOf("能力", "查找工具", "resolver", "capability resolver", "AI Limbs Core")
        ),
        entry(
            name = "capability.describe",
            displayName = "AI Limbs Capability Resolver · 能力详情",
            description = "Describe one capability, including schema, permissions, prerequisites, and invocation address.",
            parameters = listOf(
                ToolParameterSchema("capability_id", "string", "ID returned by capability.search", true)
            ),
            keywords = listOf("能力详情", "参数", "schema", "调用地址", "AI Limbs Core")
        ),
        entry(
            name = "ai_limbs.core.status",
            displayName = "AI Limbs Core 状态",
            description = "Read the AI Limbs core version and its registered first-party modules.",
            keywords = listOf("AI Limbs Core", "核心", "core module", "核心模块")
        ),
        entry(
            name = "ai_limbs.dispatcher.status",
            displayName = "AI Limbs Tool Dispatcher 状态",
            description = "Describe the AI Limbs dispatcher route and its enforced Operit permission chain.",
            keywords = listOf("dispatcher", "分发器", "调度器", "权限链", "AI Limbs Core")
        ),
        entry("ai_limbs.access_context.read", "AI Limbs 系统接入策略", "Read the compiled system access policy and user access prompt."),
        entry("ai_limbs.access_prompt.read", "读取自定义接入提示", "Read the editable AI Limbs user access prompt."),
        entry(
            "ai_limbs.access_prompt.write",
            "保存自定义接入提示",
            "Save the editable AI Limbs user access prompt.",
            listOf(ToolParameterSchema("content", "string", "Complete prompt body", true))
        ),
        entry("ai_limbs.work_manual.read", "读取工作手册", "Read the protected AI Limbs work manual."),
        entry(
            "ai_limbs.work_manual.write",
            "保存工作手册",
            "Save the editable body of the protected AI Limbs work manual.",
            listOf(ToolParameterSchema("content", "string", "Complete editable manual body", true))
        ),
        entry("ai_limbs.tool_manual.read", "读取工具手册", "Read the protected AI Limbs tool manual."),
        entry(
            "ai_limbs.tool_manual.write",
            "保存工具手册",
            "Save the protected AI Limbs tool manual.",
            listOf(ToolParameterSchema("content", "string", "Complete manual body", true))
        ),
        entry("ai_limbs.ui.status", "AI Limbs 视觉与触觉状态", "Read live AI Limbs UI and visual-control readiness."),
        entry(
            "ai_limbs.ubuntu.share.status",
            "兰儿 Ubuntu 共享窗口状态",
            "Read whether the read-only shared Ubuntu operation window currently has an active hidden command.",
            keywords = listOf("共享窗口", "眼睛", "只读", "兰儿操作", "Ubuntu share")
        ),
        entry("operit.tools.list", "Operit 原生工具 Registry", "List currently registered native Operit tool names.", keywords = listOf("registry", "dispatcher", "工具注册表")),
        ubuntuEntry("ubuntu.status", "查询 Ubuntu 状态", "Read the lifecycle state of the AI Limbs Ubuntu sandbox.", listOf("Ubuntu", "Linux", "沙箱", "生命周期")),
        ubuntuEntry("ubuntu.start", "启动 Ubuntu", "Start the AI Limbs Ubuntu sandbox.", listOf("Ubuntu", "Linux", "开机", "启动沙箱")),
        ubuntuEntry("ubuntu.stop", "停止 Ubuntu", "Stop the AI Limbs Ubuntu sandbox.", listOf("Ubuntu", "Linux", "关机", "停止沙箱")),
        ubuntuEntry("ubuntu.idle.get", "查询 Ubuntu 空闲策略", "Read the Ubuntu idle auto-stop policy.", listOf("Ubuntu", "自动关机", "空闲时间")),
        ubuntuEntry(
            "ubuntu.idle.set",
            "修改 Ubuntu 空闲策略",
            "Change the Ubuntu idle auto-stop policy.",
            listOf("Ubuntu", "自动关机", "保持开机", "自定义时间"),
            listOf(
                ToolParameterSchema("mode", "string", "KEEP_RUNNING, MINUTES_10, MINUTES_15, MINUTES_30, MINUTES_60, or CUSTOM", true),
                ToolParameterSchema("custom_minutes", "integer", "Required for CUSTOM; allowed range is 1 to 1440 minutes", false)
            )
        )
    )

    init {
        check(registrations.map { it.targetToolName }.distinct().size == registrations.size) {
            "Duplicate AI Limbs core capability registration"
        }
    }

    fun entries(): List<ToolCatalogEntry> = registrations

    fun registeredToolNames(): List<String> = registrations.map { it.targetToolName }

    /** Merge registered metadata over runtime entries while keeping third-party catalog entries. */
    fun mergeInto(runtimeCatalog: List<ToolCatalogEntry>): List<ToolCatalogEntry> {
        val registrationsByName = registrations.associateBy { it.targetToolName }
        val mergedNames = linkedSetOf<String>()
        val merged = runtimeCatalog.map { runtime ->
            val registered = registrationsByName[runtime.targetToolName]
            if (registered == null) {
                runtime
            } else {
                mergedNames += registered.targetToolName
                registered.copy(
                    keywords = (runtime.keywords + registered.keywords).distinct(),
                    sourceEnabled = runtime.sourceEnabled,
                    inputSchema = runtime.inputSchema ?: registered.inputSchema
                )
            }
        }
        return merged + registrations.filterNot { mergedNames.contains(it.targetToolName) }
    }

    private fun ubuntuEntry(
        name: String,
        displayName: String,
        description: String,
        keywords: List<String>,
        parameters: List<ToolParameterSchema> = emptyList()
    ): ToolCatalogEntry = entry(
        name = name,
        displayName = displayName,
        description = description,
        parameters = parameters,
        keywords = keywords,
        sourceName = "ubuntu",
        sourceLocator = "ubuntu://lifecycle/${name.substringAfterLast('.')}"
    )

    private fun entry(
        name: String,
        displayName: String,
        description: String,
        parameters: List<ToolParameterSchema> = emptyList(),
        keywords: List<String> = emptyList(),
        sourceName: String = CORE_PROVIDER,
        sourceLocator: String = "ai-limbs://core/$name"
    ) = ToolCatalogEntry(
        targetToolName = name,
        displayName = displayName,
        description = description,
        parameterHints = parameters.map { parameter ->
            "${parameter.name} [${parameter.type}, ${if (parameter.required) "required" else "optional"}]: ${parameter.description}"
        },
        sourceKind = ToolCatalogSourceKind.INTERNAL,
        keywords = keywords,
        parameters = parameters,
        sourceName = sourceName,
        sourceLocator = sourceLocator
    )
}
