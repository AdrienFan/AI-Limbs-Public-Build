package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import org.json.JSONObject

internal object TriggerPluginHostBridge {
    @Volatile var host: ChildExtensionHost? = null
    suspend fun invoke(tool: String, args: JSONObject): JSONObject {
        val current = host ?: error("TRIGGERcmd child host is not mounted")
        val request = JSONObject().put("transport", "triggercmd").put("tool", tool).put("args", args)
        return JSONObject(current.invokeHostCapability("core.bridge.remote.invoke", request.toString()))
    }
}
enum class AiLimbsExecutionTransport { TRIGGERCMD }
data class AiLimbsExecutionSession(val transport: AiLimbsExecutionTransport, val scopeId: String)
class AiLimbsRemoteInvocationExecutor(context: Context, val session: AiLimbsExecutionSession) {
    suspend fun execute(name: String, args: JSONObject): JSONObject = TriggerPluginHostBridge.invoke(name, args)
}
