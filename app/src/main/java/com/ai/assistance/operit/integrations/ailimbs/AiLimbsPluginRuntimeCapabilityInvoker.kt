package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvoker
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvokerFactory
import org.json.JSONObject

internal class AiLimbsPluginRuntimeCapabilityInvokerFactory(
    context: Context
) : PluginCapabilityInvokerFactory {
    private val appContext = context.applicationContext

    override fun create(ownerPluginId: String): PluginCapabilityInvoker {
        val executor =
            AiLimbsRemoteInvocationExecutor(
                appContext,
                AiLimbsExecutionSession(
                    transport = AiLimbsExecutionTransport.PLUGIN_RUNTIME,
                    scopeId = "plugin:$ownerPluginId"
                )
            )
        return PluginCapabilityInvoker { capabilityId, parameters ->
            executor.execute(capabilityId, JSONObject(parameters.toString()))
        }
    }
}
