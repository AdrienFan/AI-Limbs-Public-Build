package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject

class AiLimbsRemoteInvocationExecutor(
    private val remoteIngress: BridgeRemoteIngress
) {
    suspend fun execute(name: String, args: JSONObject): JSONObject =
        remoteIngress.invoke(name, args)
}
