package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDispatcher
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionPolicyEngine
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsCapabilityRegistry
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject

internal enum class HostGatewayRouteKind { HOST_TOOL, CORE_CAPABILITY, LOGGING, UNBOUND }

internal data class HostGatewayOperationBinding(
    val operation: String,
    val kind: HostGatewayRouteKind,
    val target: String? = null
)

internal object HostPrimitiveGatewayBindings {
    private fun tool(operation: String, target: String) = HostGatewayOperationBinding(operation, HostGatewayRouteKind.HOST_TOOL, target)
    private fun core(operation: String, target: String) = HostGatewayOperationBinding(operation, HostGatewayRouteKind.CORE_CAPABILITY, target)
    private fun logging(operation: String) = HostGatewayOperationBinding(operation, HostGatewayRouteKind.LOGGING)
    private fun pending(operation: String) = HostGatewayOperationBinding(operation, HostGatewayRouteKind.UNBOUND)
    private fun ops(vararg items: HostGatewayOperationBinding) = items.associateBy { it.operation }

    val all: Map<String, Map<String, HostGatewayOperationBinding>> = linkedMapOf(
        "host.filesystem@1" to ops(tool("list", "list_files"), tool("read", "read_file"), tool("read_range", "read_file_part"), tool("read_full", "read_file_full"), tool("read_binary", "read_file_binary"), tool("write", "write_file"), tool("write_binary", "write_file_binary"), tool("delete", "delete_file"), tool("move", "move_file"), tool("copy", "copy_file"), tool("mkdir", "make_directory"), tool("stat", "file_info"), tool("find", "find_files"), tool("grep", "grep_code"), tool("open", "open_file"), tool("share", "share_file")),
        "host.process@1" to ops(tool("execute", "execute_shell"), tool("create_session", "create_terminal_session"), tool("session_execute", "execute_in_terminal_session"), tool("session_stream", "execute_in_terminal_session_streaming"), tool("hidden_execute", "execute_hidden_terminal_command"), tool("session_input", "input_in_terminal_session"), tool("session_screen", "get_terminal_session_screen"), tool("session_close", "close_terminal_session")),
        "host.ubuntu.runtime@1" to ops(core("status", "ubuntu.status"), core("start", "ubuntu.start"), core("stop", "ubuntu.stop"), core("idle_get", "ubuntu.idle.get"), core("idle_set", "ubuntu.idle.set")),
        "host.ui.automation@1" to ops(tool("snapshot", "get_page_info"), tool("click", "click_element"), tool("tap", "tap"), tool("long_press", "long_press"), tool("set_text", "set_input_text"), tool("key", "press_key"), tool("swipe", "swipe")),
        "host.screen.capture@1" to ops(tool("capture", "capture_screenshot")),
        "host.network@1" to ops(tool("http", "http_request"), tool("multipart", "multipart_request"), tool("cookies", "manage_cookies"), pending("listen")),
        "host.background.runtime@1" to ops(pending("acquire_lease"), pending("update_lease"), pending("release_lease"), pending("status")),
        "host.notification@1" to ops(tool("publish", "send_notification"), tool("observe", "get_notifications")),
        "host.android.settings@1" to ops(tool("get", "get_system_setting"), tool("set", "modify_system_setting")),
        "host.android.package@1" to ops(tool("list", "list_installed_apps"), tool("install", "install_app"), tool("uninstall", "uninstall_app"), tool("launch", "start_app"), tool("stop", "stop_app")),
        "host.bluetooth@1" to ops(tool("permission", "request_bluetooth_permission"), tool("state", "get_bluetooth_state"), tool("enable", "request_enable_bluetooth"), tool("bonded", "list_bluetooth_bonded_devices"), tool("scan", "scan_bluetooth_devices"), tool("connect", "bluetooth_connect"), tool("listen", "bluetooth_listen"), tool("accept", "bluetooth_accept"), tool("send", "bluetooth_send"), tool("read", "bluetooth_read"), tool("transact", "bluetooth_send_and_read"), tool("close", "bluetooth_close"), tool("ble_connect", "bluetooth_ble_connect"), tool("ble_discover", "bluetooth_ble_discover_services"), tool("ble_read", "bluetooth_ble_read_characteristic"), tool("ble_write", "bluetooth_ble_write_characteristic"), tool("ble_transact", "bluetooth_ble_write_and_read_characteristic"), tool("ble_subscribe", "bluetooth_ble_subscribe_characteristic"), tool("ble_notifications", "bluetooth_ble_read_notifications")),
        "host.location@1" to ops(tool("locate", "get_device_location")),
        "host.clipboard@1" to ops(pending("read"), pending("write"), pending("clear"), pending("observe")),
        "host.permission@1" to ops(pending("check"), pending("request")),
        "host.audio.capture@1" to ops(pending("start"), pending("read"), pending("stop")),
        "host.audio.playback@1" to ops(pending("play"), pending("pause"), pending("resume"), pending("stop"), pending("seek"), pending("volume")),
        "host.android.component@1" to ops(tool("invoke", "execute_intent"), tool("broadcast", "send_broadcast")),
        "host.event@1" to ops(pending("snapshot"), pending("subscribe"), pending("unsubscribe")),
        "host.device.state@1" to ops(tool("snapshot", "device_info")),
        "host.scheduler@1" to ops(pending("schedule_once"), pending("schedule_periodic"), pending("cancel"), pending("list")),
        "host.ai.inference@1" to ops(pending("invoke"), pending("stream"), pending("estimate_tokens")),
        "host.chat@1" to ops(tool("create", "create_new_chat"), tool("list", "list_chats"), tool("find", "find_chat"), tool("switch", "switch_chat"), tool("title", "update_chat_title"), tool("delete", "delete_chat"), tool("messages", "get_chat_messages"), tool("messages_range", "get_chat_messages_range"), tool("send", "send_message_to_ai"), tool("stream", "send_message_to_ai_streaming")),
        "host.logging@1" to ops(logging("read")),
        "host.secrets@1" to ops(pending("read"), pending("revoke"), pending("rotate")),
        "host.ui.surface@1" to ops(pending("list"), pending("register"), pending("open"), pending("remove")),
        "host.window.overlay@1" to ops(pending("create"), pending("update"), pending("remove"), pending("list")),
        "host.capability@1" to ops(core("search", "capability.search"), core("describe", "capability.describe"), pending("invoke")),
        "host.plugin.service@1" to ops(pending("list"), pending("describe"), pending("call")),
        "host.extension.routing@1" to ops(pending("list_points"), pending("list_bindings"), pending("bind"), pending("unbind")),
        "host.plugin.runtime@1" to ops(pending("list"), pending("status"), pending("mount"), pending("stop")),
        "host.pipeline.hook@1" to ops(pending("list"), pending("register"), pending("unregister")),
        "host.android.usage@1" to ops(tool("query", "get_app_usage_time")),
        "host.content@1" to ops(pending("pick"), pending("open"), pending("read"), pending("write"), pending("share")),
        "host.web.runtime@1" to ops(tool("visit", "visit_web"), tool("navigate", "browser_navigate"), tool("back", "browser_navigate_back"), tool("snapshot", "browser_snapshot"), tool("screenshot", "browser_take_screenshot"), tool("click", "browser_click"), tool("type", "browser_type"), tool("fill", "browser_fill_form"), tool("evaluate", "browser_evaluate"), tool("run_code", "browser_run_code"), tool("tabs", "browser_tabs"), tool("close", "browser_close"), tool("close_all", "browser_close_all"), tool("network", "browser_network_requests")),
        "host.ingress@1" to ops(pending("list"), pending("register"), pending("unregister"), pending("status")),
        "host.authorization@1" to ops(core("describe", "ai_limbs.policy.describe"), pending("evaluate")),
        "kernel.plugin.trust@1" to ops(pending("status"), pending("verify_package")),
        "host.ui.widget@1" to ops(pending("list"), pending("register"), pending("update"), pending("remove")),
        "host.camera.capture@1" to ops(pending("capture")),
    )

    fun operations(primitiveId: String): Map<String, HostGatewayOperationBinding> =
        all[primitiveId.trim().lowercase()].orEmpty()

    fun operationNames(primitiveId: String): List<String> = operations(primitiveId).keys.sorted()

    fun isCallable(primitiveId: String): Boolean =
        operations(primitiveId).values.any { it.kind != HostGatewayRouteKind.UNBOUND }
}

internal class SystemHostPrimitiveExecutor(context: Context) {
    private val appContext = context.applicationContext
    private val toolHandler = AIToolHandler.getInstance(appContext)

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
            HostGatewayRouteKind.LOGGING -> true
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
            HostGatewayRouteKind.HOST_TOOL -> invokeHostTool(ownerPluginId, requireNotNull(binding.target), parameters)
            HostGatewayRouteKind.CORE_CAPABILITY -> dispatcher(ownerPluginId).execute(requireNotNull(binding.target), JSONObject(parameters.toString()))
            HostGatewayRouteKind.LOGGING -> readLogs(parameters)
            HostGatewayRouteKind.UNBOUND -> error("unreachable")
        }
    }

    private suspend fun invokeHostTool(ownerPluginId: String, toolName: String, parameters: JSONObject): JSONObject =
        dispatcher(ownerPluginId).execute(
            "ai_limbs.host_tool.execute",
            JSONObject().put("name", toolName).put("parameters", JSONObject(parameters.toString()))
        )

    private fun dispatcher(ownerPluginId: String): AiLimbsDispatcher {
        val session = AiLimbsExecutionSession(AiLimbsExecutionTransport.PLUGIN_RUNTIME, "system:$ownerPluginId")
        return AiLimbsDispatcher(appContext, AiLimbsExecutionPolicyEngine(appContext, session))
    }

    private fun readLogs(parameters: JSONObject): JSONObject {
        val maximum = parameters.optInt("max_chars", 60_000).coerceIn(1_000, 120_000)
        val logFile = AppLogger.getLogFile()
        val full = if (logFile?.isFile == true) logFile.readText() else ""
        val content = if (full.length > maximum) full.takeLast(maximum) else full
        return JSONObject().put("content", content).put("truncated", full.length > content.length).put("characters", content.length)
    }
}
