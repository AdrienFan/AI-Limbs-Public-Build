package com.ai.limbs.extensions.sentinelx.runtime

import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import org.json.JSONObject

internal class SentinelXRemoteInvocationExecutor(
    private val remoteIngress: BridgeRemoteIngress
) {
    suspend fun execute(tool: String, args: JSONObject): JSONObject =
        remoteIngress.invoke(tool, args)
}
