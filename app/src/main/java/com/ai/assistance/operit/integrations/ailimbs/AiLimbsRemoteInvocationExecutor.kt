package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import org.json.JSONObject

internal data class AiLimbsDispatcherInvocation(
    val tool: String,
    val args: JSONObject
)

internal fun routeRemoteInvocation(
    requestedTool: String,
    args: JSONObject,
    isCoreCapability: Boolean,
    isResolvedHostCapability: Boolean,
    hostToolExecutor: String
): AiLimbsDispatcherInvocation =
    if (!isCoreCapability && isResolvedHostCapability) {
        AiLimbsDispatcherInvocation(
            tool = hostToolExecutor,
            args =
                JSONObject()
                    .put("name", requestedTool)
                    .put("parameters", args)
        )
    } else {
        AiLimbsDispatcherInvocation(requestedTool, args)
    }

/**
 * Transport-neutral remote execution entry for AI Limbs.
 *
 * Transports own framing, delivery, acknowledgement and retries. This executor owns the shared
 * execution session, policy kernel and Dispatcher path so every remote bridge reaches the same
 * capability and permission system.
 */
class AiLimbsRemoteInvocationExecutor(
    context: Context,
    val session: AiLimbsExecutionSession
) {
    private val appContext = context.applicationContext
    private val policyEngine = AiLimbsExecutionPolicyEngine(appContext, session)
    private val capabilityResolver = AiLimbsCapabilityResolver(appContext, policyEngine)
    private val dispatcher = AiLimbsDispatcher(appContext, policyEngine)
    private val hostToolExecutor =
        AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
            AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE
        )

    suspend fun execute(tool: String, args: JSONObject): JSONObject {
        val requestedTool = tool.trim()
        val isCoreCapability =
            AiLimbsCoreCapabilityRegistry.isRegisteredInvokeName(requestedTool)
        val isResolvedHostCapability =
            if (isCoreCapability || requestedTool.isBlank()) {
                false
            } else {
                capabilityResolver.containsInvokeId(requestedTool)
            }
        val invocation =
            routeRemoteInvocation(
                requestedTool = requestedTool,
                args = args,
                isCoreCapability = isCoreCapability,
                isResolvedHostCapability = isResolvedHostCapability,
                hostToolExecutor = hostToolExecutor
            )
        return dispatcher.execute(invocation.tool, invocation.args)
    }

    fun describePolicy(): JSONObject = policyEngine.describePolicy()
}
