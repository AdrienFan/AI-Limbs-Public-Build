package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

internal class VisualOperationFailure(
    val code: String,
    override val message: String,
    val details: JSONObject = JSONObject()
) : IllegalStateException(message)

/** A completed transport call is not proof that the requested operation succeeded. */
internal object VisualOperationResult {
    fun requireHost(result: JSONObject): JSONObject {
        for (key in listOf("success", "ok", "available")) {
            if (result.has(key) && !result.getBoolean(key)) {
                throw VisualOperationFailure("HOST_OPERATION_FAILED",
                    result.optString("error", "宿主视觉操作未完成"), result)
            }
        }
        return result
    }

    fun requireFlag(result: JSONObject, flag: String, message: String): JSONObject {
        requireHost(result)
        if (!result.has(flag) || !result.getBoolean(flag)) {
            throw VisualOperationFailure(
                if (result.optBoolean("permission_granted", true)) "OPERATION_NOT_COMPLETED" else "NEEDS_PERMISSION",
                message, result)
        }
        return result
    }

    fun requireSuccess(result: JSONObject): JSONObject {
        if (!result.getBoolean("success")) {
            throw VisualOperationFailure(result.getString("error_code"), result.getString("error"), result)
        }
        return result
    }

    fun requireStopped(result: JSONObject): JSONObject {
        requireHost(result)
        if (!result.has("active") || result.getBoolean("active")) {
            throw VisualOperationFailure("STOP_NOT_COMPLETED", "会话仍在运行，停止未完成", result)
        }
        return result
    }
}
