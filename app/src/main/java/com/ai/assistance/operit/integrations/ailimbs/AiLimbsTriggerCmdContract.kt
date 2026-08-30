package com.ai.assistance.operit.integrations.ailimbs

import java.util.UUID
import org.json.JSONObject

internal object AiLimbsTriggerCmdContract {
    const val PROTOCOL = "AIL_TRIGGER_BRIDGE_V1"
    const val COMMAND_NAME = "AI Limbs Bridge"

    fun requestEnvelope(
        requestId: String,
        tool: String,
        args: JSONObject
    ): JSONObject =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("request_id", requestId)
            .put("tool", tool)
            .put("args", args)

    fun transportInvocation(tool: String, args: JSONObject): JSONObject {
        val request = requestEnvelope("req-" + UUID.randomUUID(), tool, args)
        return JSONObject()
            .put("provider", "triggercmd")
            .put("command", COMMAND_NAME)
            .put("parameters", request.toString())
    }
}
