package com.ai.limbs.extensions.triggercmd.runtime

import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import org.json.JSONObject

class AiLimbsRemoteInvocationExecutor(
    private val remoteIngress: BridgeRemoteIngress
) {
    suspend fun execute(name: String, args: JSONObject): JSONObject =
        remoteIngress.invoke(name, args)
}
