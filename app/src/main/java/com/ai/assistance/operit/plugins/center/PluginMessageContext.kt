package com.ai.assistance.operit.plugins.center

import android.os.SystemClock
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsMessageContext
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Uses the canonical mounted Provider identity. No concrete plugin names enter the Host. */
internal object PluginMessageContext {
    suspend fun read(): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val request = JSONObject().put("schema", 1).put("event", "user_message")
            .put("context_id", UUID.randomUUID().toString()).put("requested_at_ms", System.currentTimeMillis())
            .put("requested_elapsed_ms", started).put("deadline_elapsed_ms", started + AiLimbsMessageContext.TIMEOUT_MS)
        return try {
            AiLimbsMessageContext.read(request, providers(), SystemClock::elapsedRealtime)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            JSONObject().put("success", false).put("active", false).put("context_id", request.getString("context_id"))
                .put("error_code", "MESSAGE_CONTEXT_DISCOVERY_FAILED").put("error", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun providers(): List<AiLimbsMessageContext.Provider> {
        if (!PluginPlatformKernel.isInitialized) return emptyList()
        val registry = PluginPlatformKernel.contributions
        return registry.listAll().filter {
            it.kind == PluginContributionKind.PROVIDER && OperationFeedbackProviderPolicy.isMessageContext(it.metadata)
        }.map { record ->
            AiLimbsMessageContext.Provider(record.id, record.ownerPluginId) { request ->
                check(registry.find(PluginContributionKind.PROVIDER, record.id) === record) { "Context provider was unmounted" }
                val executor = record.payload as? InProcessCapabilityExecutor ?: error("Incompatible context Provider payload")
                val response = executor.invoke(request)
                check(registry.find(PluginContributionKind.PROVIDER, record.id) === record) { "Context provider changed during capture" }
                response
            }
        }
    }
}
