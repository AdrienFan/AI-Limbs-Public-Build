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
        val groups = org.json.JSONArray()
        if (count > 0) groups.put(JSONObject().put("id", "lifecycle").put("label", "待审批")
            .put("items", org.json.JSONArray().put(JSONObject().put("id", "requests")
                .put("label", "请调用 ai_limbs.self.status 逐项审阅申请").put("count", count).put("semantic_tone", "warning"))))
        val grants = result.optJSONObject("authorizations")?.optJSONArray("effective_grants") ?: org.json.JSONArray()
        val items = org.json.JSONArray()
        for (index in 0 until grants.length()) {
            val grant = grants.getJSONObject(index)
            val name = when (grant.getString("operation")) { "upgrade" -> "升级"; "rollback" -> "回滚"; else -> "迁移" }
            val expiry = if (grant.getString("mode") == "LONG") "长期有效，至撤销" else {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.ROOT)
                format.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                "限时有效至 ${format.format(java.util.Date(grant.getLong("expires_at_ms")))}"
            }
            items.put(JSONObject().put("id", grant.getString("operation")).put("label", "$name：$expiry；请在本次回复提示有效授权")
                .put("count", 1).put("semantic_tone", "info"))
        }
        if (items.length() > 0) groups.put(JSONObject().put("id", "authorizations").put("label", "当前有效授权").put("items", items))
        com.ai.assistance.operit.plugins.center.HostAttentionRegistry.publish("ai_limbs.self", JSONObject()
            .put("label", "自我模块").put("groups", groups))
    }
    fun human(context: Context, operation: String, args: JSONObject): JSONObject = guarded {
        val result = when (operation) {
            "status" -> store(context).status()
            "resources" -> store(context).resources(args)
            "registration" -> store(context).migrationRegistration()
            "complete_registration" -> store(context).completeRegistration(args.getJSONObject("receipt"))
            "install" -> store(context).install(File(args.getString("package_path")))
            "request" -> store(context).request(args.getString("operation"), args.getJSONObject("parameters"), args.optJSONObject("program_binding"))
            "submit" -> store(context).requestBundle(args)
            "execute" -> store(context).humanExecute(args.getString("operation"), args.getJSONObject("parameters"), args.optJSONObject("program_binding"))
            "cancel_request" -> store(context).cancelRequest(args.getString("request_id"), args.optJSONObject("program_binding"))
            else -> error("SELF_HUMAN_DIRECT_OPERATION_FORBIDDEN")
        }
        if (operation == "execute") finishPreparedExport(context, result)
        result
    }
    fun ai(context: Context, session: AiLimbsExecutionSession, operation: String, args: JSONObject): JSONObject = guarded {
        check(session.transport != AiLimbsExecutionTransport.PLUGIN_RUNTIME && session.hostAttestedAiIngress) { "SELF_AI_TRUSTED_CHANNEL_REQUIRED" }
        if (operation == "migrate" && args.optString("phase") == "export") check(args.optString("export_uri").startsWith("content://")) { "SELF_EXPORT_DESTINATION_REQUIRED" }
        val result = when (operation) {
            "status" -> store(context).status()
            "install" -> store(context).install(File(args.getString("package_path")))
            "review" -> store(context).review(args.getString("request_id"), args.getBoolean("approve"), args)
            "revoke" -> store(context).revoke(args.getString("operation"), args.optString("reason"))
            "upgrade", "rollback", "migrate" -> store(context).autonomous(operation, args)
            else -> error("SELF_OPERATION_INVALID")
        }
        if (operation in setOf("review", "migrate")) finishPreparedExport(context, result)
        result
    }
    private fun finishPreparedExport(context: Context, result: JSONObject) {
        val module = result.optJSONObject("result")?.optJSONObject("module") ?: result.optJSONObject("module") ?: return
        if (module.optString("direction") == "outbound" && module.optString("lifecycle_state") == "SEALED" &&
            module.has("export_uri") && !module.optBoolean("saved_export")) exportMigration(context, module.getString("export_uri"))
    }
    /** Staging is private; the user-facing package is written only to the selected SAF document. */
    fun exportMigration(context: Context, uriText: String): JSONObject {
        val destination = android.net.Uri.parse(uriText)
        check(destination.scheme == "content") { "SELF_EXPORT_DOCUMENT_REQUIRED" }
        val descriptor = store(context).migrationRegistration()
        val file = store(context).migrationPackage()
        val resolver = context.applicationContext.contentResolver
        requireNotNull(resolver.openOutputStream(destination, "wt")) { "SELF_EXPORT_OPEN_FAILED" }.use { stream ->
            file.inputStream().use { it.copyTo(stream) }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        requireNotNull(resolver.openInputStream(destination)) { "SELF_EXPORT_VERIFY_OPEN_FAILED" }.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                size += count; check(size <= descriptor.getLong("package_size_bytes")) { "SELF_EXPORT_VERIFY_FAILED" }
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(size == descriptor.getLong("package_size_bytes") && actual == descriptor.getString("package_sha256")) { "SELF_EXPORT_VERIFY_FAILED" }
        return store(context).markMigrationSaved(descriptor.getString("migration_id"), actual, uriText)
    }
    fun migrationPackage(context: Context): File = store(context).migrationPackage()
    fun rejectOrdinaryOperation(context: Context, id: String) {
        check(!store(context).isReservedIdentity(id)) { "SELF_ORDINARY_LIFECYCLE_FORBIDDEN" }
    }
}
