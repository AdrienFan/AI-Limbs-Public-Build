package com.ai.limbs.extensions.chatgptprobe

import org.json.JSONArray
import org.json.JSONObject

/** In-memory evidence for the current listener, never a claim about a particular client's cache. */
internal data class GatewayAccessObservation(
    val startedAtMs: Long? = null,
    val initializeCount: Long = 0,
    val protocolErrorCount: Long = 0,
    val unadvertisedToolCount: Long = 0,
    val advertisedToolCallCount: Long = 0,
    val capabilityInvokeCount: Long = 0,
    val capabilitySuccessCount: Long = 0,
    val capabilityFailureCount: Long = 0,
    val capabilityUncertainCount: Long = 0,
    val resultPreparationFailureCount: Long = 0,
    val lastInitializeAtMs: Long? = null,
    val lastAdvertisedToolCallAtMs: Long? = null,
    val lastSuccessfulInvokeAtMs: Long? = null,
    val lastUnadvertisedToolAtMs: Long? = null,
    val lastProtocolErrorCode: Int? = null,
    val catalogMismatchSuspected: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("started_at_ms", startedAtMs ?: JSONObject.NULL)
        .put("initialize_count", initializeCount)
        .put("protocol_error_count", protocolErrorCount)
        .put("unadvertised_tool_count", unadvertisedToolCount)
        .put("advertised_tool_call_count", advertisedToolCallCount)
        .put("capability_invoke_count", capabilityInvokeCount)
        .put("capability_success_count", capabilitySuccessCount)
        .put("capability_failure_count", capabilityFailureCount)
        .put("capability_uncertain_count", capabilityUncertainCount)
        .put("result_preparation_failure_count", resultPreparationFailureCount)
        .put("last_initialize_at_ms", lastInitializeAtMs ?: JSONObject.NULL)
        .put("last_advertised_tool_call_at_ms", lastAdvertisedToolCallAtMs ?: JSONObject.NULL)
        .put("last_successful_invoke_at_ms", lastSuccessfulInvokeAtMs ?: JSONObject.NULL)
        .put("last_unadvertised_tool_at_ms", lastUnadvertisedToolAtMs ?: JSONObject.NULL)
        .put("last_protocol_error_code", lastProtocolErrorCode ?: JSONObject.NULL)
        .put("catalog_mismatch_suspected", catalogMismatchSuspected)
}

internal data class GatewayAccessStatus(
    val stage: String,
    val label: String,
    val transportHealthy: Boolean,
    val catalogRequested: Boolean,
    val advertisedToolObserved: Boolean,
    val successfulInvokeObserved: Boolean,
    val catalogMismatchSuspected: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("stage", stage).put("label", label)
        .put("transport_healthy", transportHealthy)
        .put("catalog_requested", catalogRequested)
        .put("advertised_tool_call_observed", advertisedToolObserved)
        .put("successful_capability_result_observed", successfulInvokeObserved)
        .put("catalog_mismatch_suspected", catalogMismatchSuspected)
        .put("client_catalog_refresh_verified", false)
        .put("automatic_client_refresh_supported", false)
        .put("metadata_refresh_steps", JSONArray(GatewayAdmission.REFRESH_STEPS))
        .put("documentation_url", GatewayAdmission.DOCUMENTATION_URL)
}

internal object GatewayAdmission {
    const val DOCUMENTATION_URL = "https://developers.openai.com/plugins/deploy/connect-chatgpt"
    val REFRESH_STEPS = listOf(
        "保持本机桥运行，在 ChatGPT 插件中打开该自定义 MCP 连接。",
        "选择 Refresh，刷新工具信息。",
        "确认工具目录与桥面板列出的当前 ai_limbs_* 工具一致。",
        "新建会话，启用插件后重新测试。"
    )

    fun evaluate(probe: McpGatewayState, config: ChatGptProbeConfig, ingressBound: Boolean,
                 nowMs: Long = System.currentTimeMillis()): GatewayAccessStatus {
        val evidence = probe.access
        val poll = probe.lastSuccessfulPollAtMs
        val healthy = probe.running && probe.phase == "ONLINE" && poll != null &&
            evidence.startedAtMs != null && poll >= evidence.startedAtMs && nowMs - poll in 0L..45_000L
        val stage = when {
            !config.secureStorageAvailable -> "SECURE_STORAGE_UNAVAILABLE"
            !config.configured -> "CONFIGURATION_REQUIRED"
            !ingressBound -> "INGRESS_UNBOUND"
            probe.phase == "AUTH_REQUIRED" -> "AUTH_REQUIRED"
            probe.phase == "ERROR" -> "TRANSPORT_ERROR"
            !probe.running -> "STOPPED"
            !healthy -> "TRANSPORT_PENDING"
            evidence.catalogMismatchSuspected -> "CATALOG_MISMATCH_SUSPECTED"
            evidence.lastSuccessfulInvokeAtMs != null -> "CAPABILITY_RESULT_OBSERVED"
            evidence.lastAdvertisedToolCallAtMs != null -> "ADVERTISED_TOOL_CALL_OBSERVED"
            probe.lastToolsListAtMs != null -> "CATALOG_REQUEST_OBSERVED"
            else -> "AWAITING_TOOL_DISCOVERY"
        }
        val label = when (stage) {
            "SECURE_STORAGE_UNAVAILABLE" -> "安全凭据存储不可用"
            "CONFIGURATION_REQUIRED" -> "等待连接配置"
            "INGRESS_UNBOUND" -> "能力入口尚未就绪"
            "STOPPED" -> "桥已停止"
            "AUTH_REQUIRED" -> "隧道授权需要处理"
            "TRANSPORT_ERROR" -> "隧道连接异常"
            "TRANSPORT_PENDING" -> "等待成功的隧道通信"
            "CATALOG_MISMATCH_SUSPECTED" -> "收到未知工具调用，疑似目录不匹配"
            "CAPABILITY_RESULT_OBSERVED" -> "本次连接已收到成功的能力调用结果"
            "ADVERTISED_TOOL_CALL_OBSERVED" -> "已收到当前工具调用，尚无成功的能力调用结果"
            "CATALOG_REQUEST_OBSERVED" -> "已收到工具目录请求，等待实际调用"
            else -> "隧道已连通，尚未收到目录请求或当前工具调用"
        }
        return GatewayAccessStatus(stage, label, healthy, probe.lastToolsListAtMs != null,
            evidence.lastAdvertisedToolCallAtMs != null, evidence.lastSuccessfulInvokeAtMs != null,
            evidence.catalogMismatchSuspected)
    }
}
