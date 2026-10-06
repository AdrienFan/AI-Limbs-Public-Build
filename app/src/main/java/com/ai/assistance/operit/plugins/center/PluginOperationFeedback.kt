package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsOperationFeedback
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Canonical Provider metadata, rather than a concrete plugin identity, selects feedback handlers. */
internal object PluginOperationFeedback {
    suspend fun attach(result: JSONObject, request: JSONObject): JSONObject = try {
        AiLimbsOperationFeedback.attach(result, request, providers())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        result.put("operation_feedback", JSONArray().put(JSONObject()
            .put("schema", 1).put("operation_id", request.getString("operation_id")).put("status", "FAILED")
            .put("error_code", "OPERATION_FEEDBACK_DISCOVERY_FAILED")
            .put("error", error.message ?: error.javaClass.simpleName)))
    }

    fun providers(): List<AiLimbsOperationFeedback.Provider> {
        if (!PluginPlatformKernel.isInitialized) return emptyList()
        val registry = PluginPlatformKernel.contributions
        return registry.listAll().filter {
            it.kind == PluginContributionKind.PROVIDER && OperationFeedbackProviderPolicy.isFeedback(it.metadata)
        }.map { record ->
            AiLimbsOperationFeedback.Provider(record.id, record.ownerPluginId) { request ->
                check(registry.find(PluginContributionKind.PROVIDER, record.id) === record) {
                    "Operation feedback provider was unmounted"
                }
                val executor = record.payload as? InProcessCapabilityExecutor
                    ?: error("Operation feedback provider has an incompatible payload")
                val response = executor.invoke(request)
                check(registry.find(PluginContributionKind.PROVIDER, record.id) === record) {
                    "Operation feedback provider changed during capture"
                }
                response
            }
        }
    }
}
