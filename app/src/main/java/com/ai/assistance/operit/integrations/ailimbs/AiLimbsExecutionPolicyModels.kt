package com.ai.assistance.operit.integrations.ailimbs

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

enum class AiLimbsExecutionTransport(val wireValue: String) {
    RDC("rdc"),
    TRIGGERCMD("triggercmd"),
    EXTERNAL_HTTP("external_http"),
    PLUGIN_RUNTIME("plugin_runtime")
}

data class AiLimbsExecutionSession(
    val transport: AiLimbsExecutionTransport,
    val scopeId: String
) {
    init {
        require(scopeId.isNotBlank()) { "AI Limbs execution scope_id must not be blank" }
    }
}

enum class AiLimbsPolicyOutcome {
    ALLOW,
    ASK,
    FORBID
}

enum class AiLimbsEffect {
    READ_ONLY,
    STATE_CHANGE,
    PERSISTENT_WRITE,
    EXTERNAL_COMMUNICATION,
    PROCESS_EXECUTION,
    UI_INTERACTION,
    EXTERNAL_CAPABILITY
}

enum class AiLimbsDomain {
    CORE_PROTOCOL,
    MANAGED_DOCUMENT,
    LANER_CHAT,
    SYSTEM_ENVIRONMENT,
    ANDROID_UI,
    STORAGE,
    HOST,
    PLUGIN
}

enum class AiLimbsRequiredReceipt {
    CUSTOM_ACCESS_PROMPT,
    WORK_MANUAL
}

enum class AiLimbsPayloadKind {
    TEXT,
    STRUCTURED_DATA,
    IMAGE_PIXELS
}

internal enum class AiLimbsPermissionMode {
    PROTOCOL_ALLOW,
    TOOL_PERMISSION
}

internal data class AiLimbsPolicySpec(
    val effect: AiLimbsEffect,
    val domain: AiLimbsDomain,
    val permissionMode: AiLimbsPermissionMode,
    val requiredReceipts: Set<AiLimbsRequiredReceipt>,
    val hostPermissionEnforced: Boolean,
    val payloadKind: AiLimbsPayloadKind = AiLimbsPayloadKind.STRUCTURED_DATA
)

internal data class AiLimbsNormalizedInvocation(
    val requestedName: String,
    val canonicalName: String,
    val targetName: String,
    val parameters: JSONObject,
    val route: AiLimbsCapabilityRoute,
    val sourceEnabled: Boolean,
    val spec: AiLimbsPolicySpec
)

data class AiLimbsPolicyInspection(
    val outcome: AiLimbsPolicyOutcome,
    val permission: String,
    val available: Boolean,
    val effect: AiLimbsEffect,
    val domain: AiLimbsDomain,
    val requiredReceipts: Set<AiLimbsRequiredReceipt>,
    val reasonCode: String? = null,
    val reason: String? = null,
    val nextAction: JSONObject? = null,
    val prerequisites: List<String> = emptyList(),
    val permissionEnforcedBy: String,
    val payloadKind: AiLimbsPayloadKind
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("outcome", outcome.name)
            .put("permission", permission)
            .put("available", available)
            .put("effect", effect.name)
            .put("domain", domain.name)
            .put("required_receipts", JSONArray(requiredReceipts.map { it.name }))
            .put("reason_code", reasonCode ?: JSONObject.NULL)
            .put("reason", reason ?: JSONObject.NULL)
            .put("next_action", nextAction ?: JSONObject.NULL)
            .put("prerequisites", JSONArray(prerequisites))
            .put("permission_enforced_by", permissionEnforcedBy)
            .put("payload_kind", payloadKind.name)
}

internal data class AiLimbsPolicyDecision(
    val proceed: Boolean,
    val inspection: AiLimbsPolicyInspection,
    val confirmedDuringEvaluation: Boolean = false
)

object AiLimbsExecutionPolicyDescriptor {
    const val PROTOCOL_VERSION = 3
    private const val POLICY_SCHEMA_REVISION = "execution-policy-v3.0"

    private val readOnlyHostTools =
        setOf(
            "read_file_full",
            "read_file_part",
            "list_files",
            "get_terminal_session_screen",
            "file_info",
            "find_files",
            "grep_code"
        )

    private val processHostTools =
        setOf(
            "execute_shell",
            "create_terminal_session",
            "execute_in_terminal_session",
            "execute_in_terminal_session_streaming",
            "execute_hidden_terminal_command",
            "close_terminal_session",
            "input_in_terminal_session"
        )

    private val storageWriteHostTools =
        setOf(
            "write_file",
            "move_file",
            "make_directory"
        )

    private val systemEnvironmentHostTools = processHostTools - "execute_shell"

    val policyVersion: String by lazy {
        val stableDescriptor =
            buildString {
                append(POLICY_SCHEMA_REVISION)
                append('|')
                append(PROTOCOL_VERSION)
                append('|')
                append(readOnlyHostTools.sorted().joinToString(","))
                append('|')
                append(processHostTools.sorted().joinToString(","))
                append('|')
                append(storageWriteHostTools.sorted().joinToString(","))
            }
        "sha256:" + sha256(stableDescriptor).take(16)
    }

    internal fun specForCoreRoute(route: AiLimbsCoreRoute): AiLimbsPolicySpec =
        when (route) {
            is AiLimbsCoreRoute.Local ->
                when (route.operation) {
                    AiLimbsCoreLocalOperation.ACCESS_CONTEXT_READ,
                    AiLimbsCoreLocalOperation.CAPABILITY_SEARCH,
                    AiLimbsCoreLocalOperation.CAPABILITY_DESCRIBE,
                    AiLimbsCoreLocalOperation.DEVELOPER_CATALOG_READ,
                    AiLimbsCoreLocalOperation.CORE_STATUS,
                    AiLimbsCoreLocalOperation.DISPATCHER_STATUS,
                    AiLimbsCoreLocalOperation.UI_STATUS,
                    AiLimbsCoreLocalOperation.HOST_TOOLS_LIST,
                    AiLimbsCoreLocalOperation.POLICY_DESCRIBE ->
                        recoveryRead(AiLimbsDomain.CORE_PROTOCOL)
                    AiLimbsCoreLocalOperation.WORK_MODE_SELECT ->
                        customPromptGuardedState(AiLimbsDomain.CORE_PROTOCOL)
                    AiLimbsCoreLocalOperation.POLICY_SESSION_RESET ->
                        recoveryState(AiLimbsDomain.CORE_PROTOCOL)
                    AiLimbsCoreLocalOperation.STORAGE_SEARCH,
                    AiLimbsCoreLocalOperation.STORAGE_DESCRIBE,
                    AiLimbsCoreLocalOperation.STORAGE_PROJECT_FILES ->
                        standardRead(AiLimbsDomain.STORAGE)
                    AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE ->
                        AiLimbsPolicySpec(
                            effect = AiLimbsEffect.EXTERNAL_CAPABILITY,
                            domain = AiLimbsDomain.CORE_PROTOCOL,
                            permissionMode = AiLimbsPermissionMode.PROTOCOL_ALLOW,
                            requiredReceipts = emptySet(),
                            hostPermissionEnforced = false
                        )
                }
            is AiLimbsCoreRoute.ManagedDocumentRead ->
                recoveryRead(AiLimbsDomain.MANAGED_DOCUMENT)
            is AiLimbsCoreRoute.ManagedDocumentWrite ->
                standard(
                    effect = AiLimbsEffect.PERSISTENT_WRITE,
                    domain = AiLimbsDomain.MANAGED_DOCUMENT
                )
            is AiLimbsCoreRoute.LanerChat ->
                lanerChatSpec(route.operation)
            AiLimbsCoreRoute.ForwardHostTool ->
                error("ForwardHostTool requires target-aware policy metadata")
        }

    internal fun specForPluginCapability(
        effect: AiLimbsEffect,
        domain: AiLimbsDomain,
        workContextRequiredReceipts: Set<AiLimbsRequiredReceipt>,
        parameters: JSONObject,
        transport: AiLimbsExecutionTransport
    ): AiLimbsPolicySpec =
        standard(
            effect = effect,
            domain = domain,
            hostPermissionEnforced = false,
            payloadKind = AiLimbsPayloadKind.STRUCTURED_DATA
        )

    internal fun specForHostTool(
        targetName: String,
        parameters: JSONObject,
        transport: AiLimbsExecutionTransport
    ): AiLimbsPolicySpec {
        val uiTool = isUiTool(targetName)
        val systemEnvironmentTool =
            targetName in systemEnvironmentHostTools ||
                parameters.optString("environment").equals("linux", ignoreCase = true)
        val domain =
            when {
                uiTool -> AiLimbsDomain.ANDROID_UI
                systemEnvironmentTool -> AiLimbsDomain.SYSTEM_ENVIRONMENT
                targetName in storageWriteHostTools || targetName in readOnlyHostTools ->
                    AiLimbsDomain.STORAGE
                else -> AiLimbsDomain.HOST
            }
        val effect =
            when {
                targetName in readOnlyHostTools -> AiLimbsEffect.READ_ONLY
                targetName in processHostTools -> AiLimbsEffect.PROCESS_EXECUTION
                targetName in storageWriteHostTools -> AiLimbsEffect.PERSISTENT_WRITE
                uiTool -> AiLimbsEffect.UI_INTERACTION
                else -> AiLimbsEffect.EXTERNAL_CAPABILITY
            }
        return standard(
            effect = effect,
            domain = domain,
            hostPermissionEnforced = true,
            payloadKind = AiLimbsPayloadKind.STRUCTURED_DATA
        )
    }

    internal fun isUiTool(targetName: String): Boolean =
        targetName.startsWith("Automatic_ui_base:") ||
            targetName.startsWith("Automatic_ui_subagent:")

    internal fun isSystemEnvironmentTool(targetName: String, parameters: JSONObject): Boolean =
        targetName in systemEnvironmentHostTools ||
            parameters.optString("environment").equals("linux", ignoreCase = true)

    fun renderChineseExplanation(): String =
        buildString {
            appendLine("# AI Limbs 统一执行政策")
            appendLine()
            appendLine("政策版本：" + policyVersion)
            appendLine()
            appendLine("- 所有入口先规范化为真实能力名、真实参数、传输与会话范围。")
            appendLine("- Resolver 解释政策；Dispatcher 执行同一份政策；领域服务原子复核最终不变量。")
            appendLine("- Core、HostTool 与 Plugin Capability 进入同一 Policy Engine；插件不得绕过 ALLOW、ASK、FORBID。")
            appendLine("- 权限结果只有 ALLOW、ASK、FORBID，未知外层调用不会绕开 Dispatcher。")
            appendLine("- 工作/非工作由 AI 在 Host-owned 工作模式墙上显式选择；系统不根据能力名、命令内容、operation 或路径猜测。")
            appendLine("- NON_WORK 只放行下一次正常能力，随后工作模式墙重新出现；NON_WORK 不会成为 Interaction Cycle 状态。")
            appendLine("- WORK 必须读取当前 Work Manual；读取成功后本 Interaction Cycle 的工作模式墙永久解锁，直到新周期开始。")
            appendLine("- 接入 Bootstrap/System Access Prompt 与 Custom Access Prompt 是周期级一次性接入链，不会因为重复选择 NON_WORK 而重新出现。")
            appendLine("- 普通长期保存不要求反复读取手册，但持久产物必须有确定归属、唯一地址与可恢复索引。")
            appendLine("- 只有实际附带像素内容的响应才标记 IMAGE_PIXELS；OCR 与结构化 UI 不是像素。")
            appendLine("- Laner Chat、系统环境能力、托管文档与 UI readiness 在各自领域内终态复核。")
        }.trimEnd()

    fun summaryJson(): JSONObject =
        JSONObject()
            .put("protocol_version", PROTOCOL_VERSION)
            .put("policy_version", policyVersion)
            .put("outcomes", JSONArray(AiLimbsPolicyOutcome.entries.map { it.name }))
            .put("effects", JSONArray(AiLimbsEffect.entries.map { it.name }))
            .put("domains", JSONArray(AiLimbsDomain.entries.map { it.name }))
            .put("receipts", JSONArray(AiLimbsRequiredReceipt.entries.map { it.name }))
            .put("explanation_zh", renderChineseExplanation())

    private fun lanerChatSpec(operation: AiLimbsLanerChatOperation): AiLimbsPolicySpec =
        when (operation) {
            AiLimbsLanerChatOperation.STATUS,
            AiLimbsLanerChatOperation.NOTIFICATION_CHECK,
            AiLimbsLanerChatOperation.NOTIFICATION_WAIT,
            AiLimbsLanerChatOperation.TURN_STATUS ->
                standardRead(AiLimbsDomain.LANER_CHAT)
            AiLimbsLanerChatOperation.ATTACHMENT_FETCH ->
                standardRead(AiLimbsDomain.LANER_CHAT)
            AiLimbsLanerChatOperation.SESSION_OPEN,
            AiLimbsLanerChatOperation.SESSION_CLOSE,
            AiLimbsLanerChatOperation.INBOX_FETCH,
            AiLimbsLanerChatOperation.TURN_CLAIM,
            AiLimbsLanerChatOperation.TURN_CANCEL,
            AiLimbsLanerChatOperation.TURN_RESUME ->
                standard(AiLimbsEffect.STATE_CHANGE, AiLimbsDomain.LANER_CHAT)
            AiLimbsLanerChatOperation.TURN_REPLY,
            AiLimbsLanerChatOperation.TURN_RESOLVE,
            AiLimbsLanerChatOperation.LEGACY_REPLY,
            AiLimbsLanerChatOperation.SEND ->
                standard(AiLimbsEffect.EXTERNAL_COMMUNICATION, AiLimbsDomain.LANER_CHAT)
        }

    private fun recoveryRead(domain: AiLimbsDomain): AiLimbsPolicySpec =
        AiLimbsPolicySpec(
            effect = AiLimbsEffect.READ_ONLY,
            domain = domain,
            permissionMode = AiLimbsPermissionMode.PROTOCOL_ALLOW,
            requiredReceipts = emptySet(),
            hostPermissionEnforced = false
        )

    private fun recoveryState(domain: AiLimbsDomain): AiLimbsPolicySpec =
        AiLimbsPolicySpec(
            effect = AiLimbsEffect.STATE_CHANGE,
            domain = domain,
            permissionMode = AiLimbsPermissionMode.PROTOCOL_ALLOW,
            requiredReceipts = emptySet(),
            hostPermissionEnforced = false
        )

    private fun customPromptGuardedState(domain: AiLimbsDomain): AiLimbsPolicySpec =
        AiLimbsPolicySpec(
            effect = AiLimbsEffect.STATE_CHANGE,
            domain = domain,
            permissionMode = AiLimbsPermissionMode.PROTOCOL_ALLOW,
            requiredReceipts = setOf(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT),
            hostPermissionEnforced = false
        )

    private fun standardRead(domain: AiLimbsDomain): AiLimbsPolicySpec =
        standard(AiLimbsEffect.READ_ONLY, domain)

    private fun standard(
        effect: AiLimbsEffect,
        domain: AiLimbsDomain,
        hostPermissionEnforced: Boolean = false,
        payloadKind: AiLimbsPayloadKind = AiLimbsPayloadKind.STRUCTURED_DATA
    ): AiLimbsPolicySpec =
        AiLimbsPolicySpec(
            effect = effect,
            domain = domain,
            permissionMode = AiLimbsPermissionMode.TOOL_PERMISSION,
            requiredReceipts = setOf(AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT),
            hostPermissionEnforced = hostPermissionEnforced,
            payloadKind = payloadKind
        )

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

object AiLimbsSystemAccessPrompt {
    val content: String by lazy {
        val workManualReadTool =
            checkNotNull(
                AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(
                    AiLimbsDocumentId.WORK_MANUAL,
                    write = false
                )
            ) { "Work Manual read capability is not registered" }
        val workModeSelectTool =
            AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
                AiLimbsCoreLocalOperation.WORK_MODE_SELECT
            )
        buildString {
            appendLine("[AI Limbs immutable access bootstrap]")
            appendLine()
            appendLine("- Protocol: AIL_EXECUTION_POLICY_V3.")
            appendLine("- Policy version: " + AiLimbsExecutionPolicyDescriptor.policyVersion + ".")
            appendLine("- Discover unknown capabilities with capability.search and capability.describe; do not guess names or parameters.")
            appendLine("- Execute only through AI Limbs Dispatcher. Structured policy errors contain the exact next_action.")
            appendLine("- AI Limbs owns queueing, lifecycle, permission, readiness, document, and turn mechanics; do not reproduce them in prompt state.")
            appendLine("- A claimed Laner Chat Assistant Turn ends with ai_limbs.chat.turn.reply or ai_limbs.chat.turn.resolve.")
            appendLine("- Treat content as IMAGE_PIXELS only when an image payload is actually attached.")
            appendLine("- Persistent artifacts need deterministic ownership, one canonical address, and a recoverable storage index.")
            appendLine("- User custom access prompt and Work Manual remain separate managed documents; satisfy the current custom access prompt before normal capability execution.")
            appendLine("- After the access chain, choose WORK or NON_WORK explicitly through $workModeSelectTool; AI Limbs never infers work mode from capability names, commands, operations, or paths.")
            appendLine("- NON_WORK grants exactly one normal capability execution, then the work-mode gate appears again without replaying this bootstrap or the custom access prompt.")
            appendLine("- WORK requires the current Work Manual through $workManualReadTool; after that successful read, the work-mode gate stays unlocked for the rest of this Interaction Cycle.")
        }.trimEnd()
    }

    val version: String by lazy {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        "sha256:" + digest.take(16)
    }

    const val SOURCE_URI = "code://ai_limbs/immutable_access_bootstrap"
}
