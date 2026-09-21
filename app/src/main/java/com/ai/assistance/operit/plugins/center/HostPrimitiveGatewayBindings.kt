package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.ToolResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDispatcher
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDocumentId
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDocumentProvider
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionAuthorization
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionPolicyEngine
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsCapabilityRegistry
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal enum class HostGatewayRouteKind { HOST_TOOL, CORE_CAPABILITY, MANAGED_DOCUMENT, LOGGING, KERNEL, COMPONENT_PROXY, UNBOUND }

internal enum class HostGatewayExecutionAffinity {
    CORE_SAFE,
    HOST_FRAMEWORK,
    HOST_UI,
    HOST_SERVICE,
    CROSS_PROCESS_BACKEND,
    UNBOUND
}

internal enum class HostGatewayHostExecution {
    MEDIA_PROJECTION_SCREEN_CAPTURE
}

internal data class HostGatewayOperationBinding(
    val operation: String,
    val kind: HostGatewayRouteKind,
    val target: String? = null,
    val affinity: HostGatewayExecutionAffinity,
    val hostExecution: HostGatewayHostExecution? = null
)

internal data class HostPrimitiveBinding(
    val affinity: HostGatewayExecutionAffinity,
    val operations: Map<String, HostGatewayOperationBinding>,
    val affinityEnforced: Boolean
)

private data class HostGatewayOperationSpec(
    val operation: String,
    val kind: HostGatewayRouteKind,
    val target: String? = null,
    val affinityOverride: HostGatewayExecutionAffinity? = null,
    val hostExecution: HostGatewayHostExecution? = null
)

internal object HostPrimitiveGatewayBindings {
    private fun tool(operation: String, target: String) =
        HostGatewayOperationSpec(operation, HostGatewayRouteKind.HOST_TOOL, target)

    private fun hostTool(
        operation: String,
        target: String,
        hostExecution: HostGatewayHostExecution
    ) =
        HostGatewayOperationSpec(
            operation = operation,
            kind = HostGatewayRouteKind.HOST_TOOL,
            target = target,
            hostExecution = hostExecution
        )

    private fun core(operation: String, target: String) =
        HostGatewayOperationSpec(operation, HostGatewayRouteKind.CORE_CAPABILITY, target)

    private fun document(operation: String, target: AiLimbsDocumentId) =
        HostGatewayOperationSpec(operation, HostGatewayRouteKind.MANAGED_DOCUMENT, target.stableId)

    private fun logging(operation: String) =
        HostGatewayOperationSpec(operation, HostGatewayRouteKind.LOGGING)

    private fun kernel(operation: String) =
        HostGatewayOperationSpec(operation, HostGatewayRouteKind.KERNEL)

    private fun component(operation: String, legacyTarget: String? = null) =
        HostGatewayOperationSpec(operation, HostGatewayRouteKind.COMPONENT_PROXY, legacyTarget)

    private fun pending(operation: String) =
        HostGatewayOperationSpec(
            operation = operation,
            kind = HostGatewayRouteKind.UNBOUND,
            affinityOverride = HostGatewayExecutionAffinity.UNBOUND
        )

    private fun owned(
        affinity: HostGatewayExecutionAffinity,
        item: HostGatewayOperationSpec
    ): HostGatewayOperationSpec {
        require(item.kind != HostGatewayRouteKind.UNBOUND) {
            "UNBOUND operation affinity is fixed by pending()"
        }
        require(item.affinityOverride == null) {
            "Operation affinity already declared: ${item.operation}"
        }
        return item.copy(affinityOverride = affinity)
    }

    private fun primitive(
        affinity: HostGatewayExecutionAffinity,
        vararg items: HostGatewayOperationSpec,
        enforceAffinity: Boolean = false
    ): HostPrimitiveBinding {
        val requiresOperationOwnership =
            affinity == HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND

        items.forEach { item ->
            if (item.kind == HostGatewayRouteKind.UNBOUND) {
                require(item.affinityOverride == HostGatewayExecutionAffinity.UNBOUND) {
                    "UNBOUND operation must declare UNBOUND affinity: ${item.operation}"
                }
            } else if (requiresOperationOwnership) {
                require(item.affinityOverride != null) {
                    "CROSS_PROCESS_BACKEND operation must declare ownership: ${item.operation}"
                }
            } else {
                require(item.affinityOverride == null) {
                    "Non-cross-process primitive must inherit its affinity: ${item.operation}"
                }
            }
        }

        items.forEach { item ->
            val effectiveAffinity = item.affinityOverride ?: affinity
            if (item.hostExecution != null) {
                require(item.kind == HostGatewayRouteKind.HOST_TOOL) {
                    "Host-local execution strategy requires HOST_TOOL: " + item.operation
                }
                require(
                    effectiveAffinity == HostGatewayExecutionAffinity.HOST_FRAMEWORK ||
                        effectiveAffinity == HostGatewayExecutionAffinity.HOST_UI ||
                        effectiveAffinity == HostGatewayExecutionAffinity.HOST_SERVICE
                ) {
                    "Host-local execution strategy requires Android Host affinity: " + item.operation
                }
            }
            if (
                enforceAffinity &&
                item.kind == HostGatewayRouteKind.HOST_TOOL &&
                (
                    effectiveAffinity == HostGatewayExecutionAffinity.HOST_FRAMEWORK ||
                        effectiveAffinity == HostGatewayExecutionAffinity.HOST_UI ||
                        effectiveAffinity == HostGatewayExecutionAffinity.HOST_SERVICE
                )
            ) {
                require(item.hostExecution != null) {
                    "Enforced Host-affinity HOST_TOOL requires explicit Host execution strategy: " +
                        item.operation
                }
            }
        }

        val operations = linkedMapOf<String, HostGatewayOperationBinding>()
        items.forEach { item ->
            require(item.operation !in operations) {
                "Duplicate Host Primitive operation: ${item.operation}"
            }
            operations[item.operation] =
                HostGatewayOperationBinding(
                    operation = item.operation,
                    kind = item.kind,
                    target = item.target,
                    affinity = item.affinityOverride ?: affinity,
                    hostExecution = item.hostExecution
                )
        }
        return HostPrimitiveBinding(
            affinity = affinity,
            operations = operations,
            affinityEnforced = enforceAffinity
        )
    }

    private val definitions: Map<String, HostPrimitiveBinding> = linkedMapOf(
        "host.filesystem@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("list", "list_files")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("read", "read_file")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("read_range", "read_file_part")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("read_full", "read_file_full")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("read_binary", "read_file_binary")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("write", "write_file")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("write_binary", "write_file_binary")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("delete", "delete_file")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("move", "move_file")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("copy", "copy_file")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("mkdir", "make_directory")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("stat", "file_info")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("find", "find_files")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("grep", "grep_code")), owned(HostGatewayExecutionAffinity.HOST_FRAMEWORK, tool("open", "open_file")), owned(HostGatewayExecutionAffinity.HOST_FRAMEWORK, tool("share", "share_file"))),
        "host.process@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE,
            tool("execute", "execute_shell"),
            pending("start"),
            pending("read"),
            pending("input"),
            pending("terminate"),
            pending("list")
        ),
        "host.ui.automation@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("snapshot", "get_page_info")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("click", "click_element")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("tap", "tap")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("long_press", "long_press")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("set_text", "set_input_text")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("key", "press_key")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("swipe", "swipe"))),
        "host.screen.capture@1" to primitive(
            HostGatewayExecutionAffinity.HOST_FRAMEWORK,
            hostTool(
                "capture",
                "capture_screenshot",
                HostGatewayHostExecution.MEDIA_PROJECTION_SCREEN_CAPTURE
            ),
            enforceAffinity = true
        ),
        "host.network@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, tool("http", "http_request"), tool("multipart", "multipart_request"), tool("cookies", "manage_cookies"), kernel("listeners"), pending("listen")),
        "host.background.runtime@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("acquire_lease"), pending("update_lease"), pending("release_lease"), pending("status")),
        "host.notification@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("publish", "send_notification")), owned(HostGatewayExecutionAffinity.HOST_SERVICE, tool("observe", "get_notifications"))),
        "host.android.settings@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("get", "get_system_setting")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("set", "modify_system_setting"))),
        "host.android.package@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("list", "list_installed_apps")), owned(HostGatewayExecutionAffinity.HOST_FRAMEWORK, tool("install", "install_app")), owned(HostGatewayExecutionAffinity.HOST_FRAMEWORK, tool("uninstall", "uninstall_app")), owned(HostGatewayExecutionAffinity.HOST_FRAMEWORK, tool("launch", "start_app")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("stop", "stop_app"))),
        "host.bluetooth@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.HOST_FRAMEWORK, tool("permission", "request_bluetooth_permission")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("state", "get_bluetooth_state")), owned(HostGatewayExecutionAffinity.HOST_FRAMEWORK, tool("enable", "request_enable_bluetooth")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("bonded", "list_bluetooth_bonded_devices")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("scan", "scan_bluetooth_devices")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("connect", "bluetooth_connect")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("listen", "bluetooth_listen")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("accept", "bluetooth_accept")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("send", "bluetooth_send")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("read", "bluetooth_read")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("transact", "bluetooth_send_and_read")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("close", "bluetooth_close")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("ble_connect", "bluetooth_ble_connect")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("ble_discover", "bluetooth_ble_discover_services")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("ble_read", "bluetooth_ble_read_characteristic")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("ble_write", "bluetooth_ble_write_characteristic")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("ble_transact", "bluetooth_ble_write_and_read_characteristic")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("ble_subscribe", "bluetooth_ble_subscribe_characteristic")), owned(HostGatewayExecutionAffinity.CORE_SAFE, tool("ble_notifications", "bluetooth_ble_read_notifications"))),
        "host.location@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, tool("locate", "get_device_location")),
        "host.clipboard@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("read"), pending("write"), pending("clear"), pending("observe")),
        "host.permission@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("check"), pending("request")),
        "host.audio.capture@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("start"), pending("read"), pending("stop")),
        "host.audio.playback@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, tool("play", "music_play"), tool("pause", "music_pause"), tool("resume", "music_resume"), tool("stop", "music_stop"), tool("seek", "music_seek"), tool("volume", "music_set_volume")),
        "host.android.component@1" to primitive(HostGatewayExecutionAffinity.HOST_FRAMEWORK,
            component("invoke", "execute_intent"),
            component("broadcast", "send_broadcast"),
            component("activity_result"),
            component("activity_presence"),
            component("window_lease"),
            component("window_flags")
        ),
        "host.event@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("snapshot"), pending("subscribe"), pending("unsubscribe")),
        "host.device.state@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, tool("snapshot", "device_info")),
        "host.scheduler@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("schedule_once"), pending("schedule_periodic"), pending("cancel"), pending("list")),
        "host.ai.inference@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("invoke"), pending("stream"), pending("estimate_tokens")),
        "host.chat@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, tool("create", "create_new_chat"), tool("list", "list_chats"), tool("find", "find_chat"), tool("switch", "switch_chat"), tool("title", "update_chat_title"), tool("delete", "delete_chat"), tool("messages", "get_chat_messages"), tool("messages_range", "get_chat_messages_range"), tool("send", "send_message_to_ai"), tool("stream", "send_message_to_ai_streaming")),
        "host.logging@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, logging("sources"), logging("read"), logging("export"), logging("clear"), logging("write")),
        "host.secrets@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("read"), pending("revoke"), pending("rotate")),
        "host.ui.surface@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CORE_SAFE, kernel("list")), owned(HostGatewayExecutionAffinity.CORE_SAFE, kernel("register")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, kernel("open")), owned(HostGatewayExecutionAffinity.CORE_SAFE, kernel("remove"))),
        "host.window.overlay@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("create"), pending("update"), pending("remove"), pending("list")),
        "host.capability@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, core("search", "capability.search"), core("describe", "capability.describe"), kernel("invoke")),
        "host.plugin.service@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, kernel("list"), kernel("describe"), kernel("call")),
        "host.extension.routing@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, kernel("list_points"), kernel("list_bindings"), kernel("bind"), kernel("unbind")),
        "host.plugin.runtime@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, kernel("list"), kernel("status"), kernel("mount"), kernel("stop")),
        "host.pipeline.hook@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("list"), pending("register"), pending("unregister")),
        "host.android.usage@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, tool("query", "get_app_usage_time"))),
        "host.content@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("pick"), pending("open"), pending("read"), pending("write"), pending("share")),
        "host.web.runtime@1" to primitive(HostGatewayExecutionAffinity.HOST_UI, tool("visit", "visit_web"), tool("navigate", "browser_navigate"), tool("back", "browser_navigate_back"), tool("snapshot", "browser_snapshot"), tool("screenshot", "browser_take_screenshot"), tool("click", "browser_click"), tool("type", "browser_type"), tool("fill", "browser_fill_form"), tool("evaluate", "browser_evaluate"), tool("run_code", "browser_run_code"), tool("tabs", "browser_tabs"), tool("close", "browser_close"), tool("close_all", "browser_close_all"), tool("network", "browser_network_requests")),
        "host.ingress@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("list"), pending("register"), pending("unregister"), pending("status")),
        "host.authorization@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, core("describe", "ai_limbs.policy.describe"), kernel("evaluate")),
        "kernel.plugin.trust@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, kernel("status"), kernel("verify_package"), kernel("verify_detached"), kernel("install_keyring")),
        "host.ui.widget@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("list"), pending("register"), pending("update"), pending("remove")),
        "host.camera.capture@1" to primitive(HostGatewayExecutionAffinity.UNBOUND, pending("capture")),
        "host.custom_access_prompt@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, document("read", AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT), document("write", AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT), document("snapshots", AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT), document("restore", AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)),
        "host.work_manual@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, document("read", AiLimbsDocumentId.WORK_MANUAL), document("write", AiLimbsDocumentId.WORK_MANUAL), document("snapshots", AiLimbsDocumentId.WORK_MANUAL), document("restore", AiLimbsDocumentId.WORK_MANUAL)),
        "host.privileged.runtime@1" to primitive(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, kernel("status")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, kernel("pair")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, kernel("prepare")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, kernel("stop")), owned(HostGatewayExecutionAffinity.CROSS_PROCESS_BACKEND, kernel("select"))),
        "host.resident.runtime@1" to primitive(HostGatewayExecutionAffinity.HOST_FRAMEWORK, kernel("status"), kernel("set_enabled"), kernel("start"), kernel("stop"), kernel("core_status"), kernel("core_probe"), kernel("core_stop")),
        "host.ui.layout@1" to primitive(HostGatewayExecutionAffinity.HOST_UI, kernel("status"), kernel("start"), kernel("finish"), kernel("reset")),
        "host.interaction.cycle@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE, kernel("status"), kernel("set_timeout"), kernel("reset"), kernel("release_gate"), kernel("close")),
    )

    val all: Map<String, Map<String, HostGatewayOperationBinding>> =
        definitions.mapValues { (_, definition) -> definition.operations }

    fun primitiveAffinity(primitiveId: String): HostGatewayExecutionAffinity? =
        definitions[primitiveId.trim().lowercase()]?.affinity

    fun operations(primitiveId: String): Map<String, HostGatewayOperationBinding> =
        definitions[primitiveId.trim().lowercase()]?.operations.orEmpty()

    fun operationNames(primitiveId: String): List<String> = operations(primitiveId).keys.sorted()

    fun affinityEnforced(primitiveId: String): Boolean =
        definitions[primitiveId.trim().lowercase()]?.affinityEnforced == true

    fun requiresAndroidHost(primitiveId: String, operation: String): Boolean {
        val normalizedId = primitiveId.trim().lowercase()
        val definition = definitions[normalizedId] ?: return false
        if (!definition.affinityEnforced) return false
        val affinity = definition.operations[operation.trim().lowercase()]?.affinity ?: return false
        return affinity == HostGatewayExecutionAffinity.HOST_FRAMEWORK ||
            affinity == HostGatewayExecutionAffinity.HOST_UI ||
            affinity == HostGatewayExecutionAffinity.HOST_SERVICE
    }

    fun isCallable(primitiveId: String): Boolean =
        operations(primitiveId).values.any { it.kind != HostGatewayRouteKind.UNBOUND }
}

internal class SystemHostPrimitiveExecutor(
    context: Context,
    private val loggingService: HostLoggingService,
    private val runtimeRole: PluginRuntimeRole = PluginRuntimeRole.LEGACY_HOST
) {
    private val appContext = context.applicationContext
    private val toolHandler = AIToolHandler.getInstance(appContext)
    private val kernelAdapter = KernelHostPrimitiveAdapter(appContext, runtimeRole)
    private val documents = AiLimbsDocumentProvider(appContext)
    private val toolResultJson = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "__type"
    }

    init {
        toolHandler.registerDefaultTools()
    }

    fun operationNames(primitiveId: String): List<String> = HostPrimitiveGatewayBindings.operationNames(primitiveId)

    fun isCallable(primitiveId: String): Boolean = HostPrimitiveGatewayBindings.isCallable(primitiveId)

    fun isOperationAvailable(primitiveId: String, operation: String): Boolean {
        val binding = HostPrimitiveGatewayBindings.operations(primitiveId)[operation.trim().lowercase()] ?: return false
        return when (binding.kind) {
            HostGatewayRouteKind.HOST_TOOL -> binding.target in toolHandler.getAllToolNames()
            HostGatewayRouteKind.CORE_CAPABILITY -> binding.target?.let(AiLimbsCapabilityRegistry::isRegisteredInvokeName) == true
            HostGatewayRouteKind.MANAGED_DOCUMENT -> binding.target in setOf(
                AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT.stableId,
                AiLimbsDocumentId.WORK_MANUAL.stableId
            )
            HostGatewayRouteKind.LOGGING -> true
            HostGatewayRouteKind.KERNEL -> kernelAdapter.isAvailable(primitiveId, binding.operation)
            HostGatewayRouteKind.COMPONENT_PROXY ->
                runtimeRole == PluginRuntimeRole.BUSINESS || binding.target?.let { it in toolHandler.getAllToolNames() } == true
            HostGatewayRouteKind.UNBOUND -> false
        }
    }

    suspend fun invoke(ownerPluginId: String, primitiveId: String, operation: String, parameters: JSONObject): JSONObject {
        val normalizedId = primitiveId.trim().lowercase()
        val normalizedOperation = operation.trim().lowercase()
        val binding = HostPrimitiveGatewayBindings.operations(normalizedId)[normalizedOperation]
            ?: throw PluginInstallException("HOST_OPERATION_UNKNOWN", "Unknown operation $normalizedOperation for $normalizedId")
        if (!isOperationAvailable(normalizedId, normalizedOperation)) {
            throw PluginInstallException("HOST_PRIMITIVE_OPERATION_NOT_BOUND", "Operation is not runtime-bound: $normalizedId/$normalizedOperation")
        }
        AppLogger.d("HostGateway", "System invoke: $ownerPluginId -> $normalizedId/$normalizedOperation")
        return when (binding.kind) {
            HostGatewayRouteKind.HOST_TOOL -> {
                val target = requireNotNull(binding.target)
                if (isApprovedScopeRead(normalizedId, normalizedOperation)) {
                    AiLimbsExecutionAuthorization.withApprovedScopeRead(
                        ownerPluginId = ownerPluginId,
                        primitiveId = normalizedId,
                        operation = normalizedOperation,
                        targetName = target
                    ) {
                        invokeHostTool(ownerPluginId, normalizedId, normalizedOperation, target, parameters)
                    }
                } else {
                    invokeHostTool(ownerPluginId, normalizedId, normalizedOperation, target, parameters)
                }
            }
            HostGatewayRouteKind.CORE_CAPABILITY -> {
                val target = requireNotNull(binding.target)
                if (isApprovedScopeRead(normalizedId, normalizedOperation)) {
                    AiLimbsExecutionAuthorization.withApprovedScopeRead(
                        ownerPluginId = ownerPluginId,
                        primitiveId = normalizedId,
                        operation = normalizedOperation,
                        targetName = target
                    ) {
                        dispatcher(ownerPluginId).execute(target, JSONObject(parameters.toString()))
                    }
                } else {
                    dispatcher(ownerPluginId).execute(target, JSONObject(parameters.toString()))
                }
            }
            HostGatewayRouteKind.MANAGED_DOCUMENT -> invokeManagedDocument(
                requireNotNull(binding.target),
                normalizedOperation,
                parameters
            )
            HostGatewayRouteKind.LOGGING -> loggingService.invoke(ownerPluginId, normalizedOperation, parameters)
            HostGatewayRouteKind.KERNEL -> kernelAdapter.invoke(ownerPluginId, normalizedId, normalizedOperation, JSONObject(parameters.toString()))
            HostGatewayRouteKind.COMPONENT_PROXY -> {
                if (runtimeRole == PluginRuntimeRole.BUSINESS) {
                    invokeResidentComponentProxy(normalizedOperation, parameters)
                } else {
                    val target = binding.target ?: throw PluginInstallException(
                        "HOST_PRIMITIVE_OPERATION_NOT_BOUND",
                        "Component proxy operation requires Resident Core: $normalizedId/$normalizedOperation"
                    )
                    invokeHostTool(ownerPluginId, normalizedId, normalizedOperation, target, parameters)
                }
            }
            HostGatewayRouteKind.UNBOUND -> error("unreachable")
        }
    }

    private fun invokeResidentComponentProxy(operation: String, parameters: JSONObject): JSONObject {
        val brokerKind = when (operation) {
            "activity_result" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_ACTIVITY_RESULT
            "activity_presence" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_ACTIVITY_PRESENCE
            "window_lease" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_WINDOW_LEASE
            "window_flags" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_WINDOW_FLAGS
            "broadcast" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_SEND_BROADCAST
            "invoke" -> when (parameters.optString("type", "activity").trim().lowercase()) {
                "activity", "" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_START_ACTIVITY
                "broadcast" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_SEND_BROADCAST
                "service" -> com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_START_SERVICE
                else -> throw PluginInstallException("HOST_COMPONENT_TYPE_INVALID", "Unsupported Android component type")
            }
            else -> throw PluginInstallException("HOST_COMPONENT_OPERATION_INVALID", "Unsupported component operation: $operation")
        }
        val payload = when (brokerKind) {
            com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_ACTIVITY_PRESENCE,
            com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_WINDOW_LEASE,
            com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_WINDOW_FLAGS -> JSONObject(parameters.toString())
            else -> neutralIntentPayload(parameters)
        }
        return com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy.request(brokerKind, payload)
    }

    private fun neutralIntentPayload(parameters: JSONObject): JSONObject {
        val payload = JSONObject()
        fun text(source: String, target: String) {
            parameters.optString(source).trim().takeIf { it.isNotEmpty() }?.let { payload.put(target, it) }
        }
        text("action", "action")
        if (parameters.has("data_uri")) text("data_uri", "data_uri") else text("uri", "data_uri")
        if (parameters.has("package_name")) text("package_name", "package_name") else text("package", "package_name")
        if (parameters.has("mime_type")) text("mime_type", "mime_type") else text("mime", "mime_type")
        val componentName = parameters.optString("component").trim()
        if (componentName.isNotEmpty()) {
            val parts = componentName.split('/', limit = 2)
            require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) { "Invalid component name" }
            payload.put("component_package", parts[0].trim()).put("component_class", parts[1].trim())
        } else {
            text("component_package", "component_package")
            text("component_class", "component_class")
        }
        parameters.optJSONArray("categories")?.let { payload.put("categories", JSONArray(it.toString())) }
        if (parameters.has("flags")) {
            val raw = parameters.opt("flags")
            val flags = when (raw) {
                is Number -> raw.toInt()
                is JSONArray -> (0 until raw.length()).fold(0) { acc, index -> acc or raw.getInt(index) }
                is String -> runCatching {
                    val array = JSONArray(raw)
                    (0 until array.length()).fold(0) { acc, index -> acc or array.getInt(index) }
                }.getOrElse { raw.toIntOrNull() ?: 0 }
                else -> 0
            }
            payload.put("flags", flags)
        }
        val extras = when (val raw = parameters.opt("extras")) {
            is JSONObject -> JSONObject(raw.toString())
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.let(::JSONObject)
            else -> null
        }
        extras?.let { payload.put("extras", it) }
        return payload
    }

    private suspend fun invokeHostTool(
        ownerPluginId: String,
        primitiveId: String,
        operation: String,
        toolName: String,
        parameters: JSONObject
    ): JSONObject {
        val executionOverride =
            if (runtimeRole == PluginRuntimeRole.BUSINESS &&
                HostPrimitiveGatewayBindings.requiresAndroidHost(primitiveId, operation)) {
                val binding = HostPrimitiveGatewayBindings.operations(primitiveId)[operation]
                    ?: throw PluginInstallException(
                        "HOST_OPERATION_UNKNOWN",
                        "Unknown operation $operation for $primitiveId"
                    )
                check(binding.kind == HostGatewayRouteKind.HOST_TOOL && binding.target == toolName) {
                    "Host-affinity tool binding mismatch: $primitiveId/$operation -> $toolName"
                }
                check(binding.hostExecution != null) {
                    "Host-affinity operation has no Host-local execution strategy: $primitiveId/$operation"
                }
                ToolExecutionManager.ToolExecutionOverride { invocation ->
                    invokeResidentHostAffinityTool(
                        ownerPluginId = ownerPluginId,
                        primitiveId = primitiveId,
                        operation = operation,
                        expectedToolName = toolName,
                        tool = invocation.tool
                    )
                }
            } else {
                null
            }

        return dispatcher(ownerPluginId, executionOverride).execute(
            "ai_limbs.host_tool.execute",
            JSONObject().put("name", toolName).put("parameters", JSONObject(parameters.toString()))
        )
    }

    private fun invokeResidentHostAffinityTool(
        ownerPluginId: String,
        primitiveId: String,
        operation: String,
        expectedToolName: String,
        tool: AITool
    ): Flow<ToolResult> = flow {
        check(tool.name == expectedToolName) {
            "Host-affinity tool changed after authorization: expected $expectedToolName, got " + tool.name
        }
        val wireParameters = JSONArray().apply {
            tool.parameters.forEach { parameter ->
                put(
                    JSONObject()
                        .put("name", parameter.name)
                        .put("value", parameter.value)
                )
            }
        }
        val response = withContext(Dispatchers.IO) {
            com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy.request(
                com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_HOST_AFFINITY_OPERATION,
                JSONObject()
                    .put("owner_plugin_id", ownerPluginId)
                    .put("primitive_id", primitiveId)
                    .put("operation", operation)
                    .put("tool_name", expectedToolName)
                    .put("parameters", wireParameters)
            )
        }
        check(response.optBoolean("ok", false)) {
            response.optString(
                "error",
                "Host-affinity tool failed: $primitiveId/$operation"
            )
        }
        val results = response.optJSONArray("results") ?: JSONArray()
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val resultData =
                toolResultJson.decodeFromString<ToolResultData>(
                    item.getString("result_data")
                )
            emit(
                ToolResult(
                    toolName = item.optString("tool_name", expectedToolName),
                    success = item.optBoolean("success", false),
                    result = resultData,
                    error = if (item.isNull("error")) null else item.optString("error")
                )
            )
        }
    }

    private fun isApprovedScopeRead(primitiveId: String, operation: String): Boolean =
        when (primitiveId to operation) {
            else -> false
        }

    private fun dispatcher(
        ownerPluginId: String,
        executionOverride: ToolExecutionManager.ToolExecutionOverride? = null
    ): AiLimbsDispatcher {
        val session = AiLimbsExecutionSession(AiLimbsExecutionTransport.PLUGIN_RUNTIME, "system:$ownerPluginId")
        return AiLimbsDispatcher(
            appContext,
            AiLimbsExecutionPolicyEngine(appContext, session),
            preserveHostToolResultData = true,
            toolExecutionOverride = executionOverride
        )
    }

    private suspend fun invokeManagedDocument(
        target: String,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        val documentId = when (target) {
            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT.stableId -> AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT
            AiLimbsDocumentId.WORK_MANUAL.stableId -> AiLimbsDocumentId.WORK_MANUAL
            else -> throw PluginInstallException("HOST_DOCUMENT_UNKNOWN", "Unsupported managed document: $target")
        }
        return when (operation) {
            "read" -> managedDocumentState(documentId)
            "write" -> {
                if (!parameters.has("content")) {
                    throw PluginInstallException("HOST_DOCUMENT_CONTENT_REQUIRED", "write requires content")
                }
                val content = parameters.optString("content")
                val changed = when (documentId) {
                    AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT -> documents.writeCustomAccessPrompt(content)
                    AiLimbsDocumentId.WORK_MANUAL -> documents.writeWorkManual(content)
                    AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT -> error("unreachable")
                }
                managedDocumentState(documentId).put("changed", changed)
            }
            "snapshots" -> {
                val items = JSONArray()
                documents.listSnapshots(documentId).forEach { snapshot ->
                    items.put(
                        JSONObject()
                            .put("id", snapshot.id)
                            .put("created_at_epoch_ms", snapshot.createdAtEpochMillis)
                            .put("sha256", snapshot.sha256)
                    )
                }
                JSONObject().put("document", documentId.stableId).put("snapshots", items)
            }
            "restore" -> {
                val snapshotId = parameters.optString("snapshot_id").trim()
                if (snapshotId.isBlank()) {
                    throw PluginInstallException("HOST_DOCUMENT_SNAPSHOT_REQUIRED", "restore requires snapshot_id")
                }
                val changed = documents.restoreSnapshot(documentId, snapshotId)
                managedDocumentState(documentId)
                    .put("changed", changed)
                    .put("restored_snapshot_id", snapshotId)
            }
            else -> throw PluginInstallException("HOST_OPERATION_UNSUPPORTED", "Unsupported managed-document operation: $operation")
        }
    }

    private suspend fun managedDocumentState(documentId: AiLimbsDocumentId): JSONObject {
        val reference = documents.documentReference(documentId)
        val result = JSONObject()
            .put("document", reference.documentId)
            .put("version", reference.version)
            .put("path", reference.path)
        return when (documentId) {
            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT -> result
                .put("empty", reference.isEmpty)
                .put("content", documents.readCustomAccessPrompt())
            AiLimbsDocumentId.WORK_MANUAL -> {
                val editable = documents.readWorkManual()
                result
                    .put("content", documents.readWorkManualForAgent())
                    .put("editable_content", editable)
                    .put("editable_empty", editable.isBlank())
                    .put("protected_header_present", true)
            }
            AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT -> error("System Access Prompt is not exposed as a Host Primitive")
        }
    }


}
