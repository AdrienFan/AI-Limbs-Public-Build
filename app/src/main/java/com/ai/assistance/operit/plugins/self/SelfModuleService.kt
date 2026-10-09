package com.ai.assistance.operit.plugins.self

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import java.io.File
import org.json.JSONObject

/** Only trusted Dispatcher session metadata can choose autonomous/review authority. */
internal object SelfModuleService {
    private fun store(context: Context) = SelfModuleStore(File(context.applicationContext.filesDir, "ai_limbs/self-module"))
    private inline fun guarded(block: () -> JSONObject): JSONObject = try { block() } catch (error: Exception) {
        JSONObject().put("success", false).put("error_code", error.message ?: "SELF_OPERATION_FAILED")
            .put("error", error.message ?: error.javaClass.simpleName)
    }
    fun refreshAttention(context: Context) {
        val result = guarded { store(context).status() }
        val requests = result.optJSONArray("requests") ?: org.json.JSONArray()
        val count = (0 until requests.length()).count { requests.getJSONObject(it).optString("status") == "PENDING" }
        com.ai.assistance.operit.plugins.center.HostAttentionRegistry.publish("ai_limbs.self", JSONObject()
            .put("label", "自我模块操作申请")
            .put("groups", org.json.JSONArray().put(JSONObject().put("id", "lifecycle").put("label", "待审批")
                .put("items", org.json.JSONArray().put(JSONObject().put("id", "requests").put("label", "请调用 ai_limbs.self.status 审阅")
                    .put("count", count).put("semantic_tone", "warning")))))
    }
    fun human(context: Context, operation: String, args: JSONObject): JSONObject = guarded {
        when (operation) {
            "status" -> store(context).status()
            "install" -> store(context).install(File(args.getString("package_path")))
            "request" -> store(context).request(args.getString("operation"), args.getJSONObject("parameters"))
            "cancel_request" -> store(context).cancelRequest(args.getString("request_id"))
            else -> error("SELF_HUMAN_DIRECT_OPERATION_FORBIDDEN")
        }
    }
    fun ai(context: Context, session: AiLimbsExecutionSession, operation: String, args: JSONObject): JSONObject = guarded {
        check(session.transport != AiLimbsExecutionTransport.PLUGIN_RUNTIME && session.hostAttestedAiIngress) { "SELF_AI_TRUSTED_CHANNEL_REQUIRED" }
        when (operation) {
            "status" -> store(context).status()
            "install" -> store(context).install(File(args.getString("package_path")))
            "review" -> store(context).review(args.getString("request_id"), args.getBoolean("approve"))
            "upgrade", "rollback", "migrate" -> store(context).autonomous(operation, args)
            else -> error("SELF_OPERATION_INVALID")
        }
    }
    fun migrationPackage(context: Context): File = store(context).migrationPackage()
    fun rejectOrdinaryOperation(context: Context, id: String) {
        check(!store(context).isReservedIdentity(id)) { "SELF_ORDINARY_LIFECYCLE_FORBIDDEN" }
    }
}
