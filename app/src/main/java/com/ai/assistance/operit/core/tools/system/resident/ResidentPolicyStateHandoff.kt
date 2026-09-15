package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import org.json.JSONObject

/** One-shot Host -> Resident Core transfer for Interaction Cycle/gate authority. */
internal object ResidentPolicyStateHandoff {
    private const val FILE_NAME = "policy_handoff.json"
    private const val SCHEMA = 1
    private const val MAX_BYTES = 64 * 1024L

    fun stage(
        context: Context,
        coreSession: String,
        corePid: Int,
        hostPid: Int,
        policyState: JSONObject
    ) {
        require(coreSession.length in 1..64)
        check(hostPid == Process.myPid()) { "Only the current Host may stage policy handoff" }
        check(corePid > 0 && corePid != hostPid) { "Invalid Resident Core PID for policy handoff" }
        val target = file(context)
        check(!target.exists()) { "Policy handoff state already exists; explicit recovery is required" }
        write(
            context,
            JSONObject()
                .put("schema", SCHEMA)
                .put("core_session", coreSession)
                .put("core_pid", corePid)
                .put("host_pid", hostPid)
                .put("policy_state", policyState)
                .put("updated_wall_ms", System.currentTimeMillis())
        )
    }

    fun consume(context: Context, coreSession: String, hostPid: Int): JSONObject {
        val target = file(context)
        val envelope = readBounded(target)
        check(envelope.getInt("schema") == SCHEMA) { "Unsupported policy handoff schema" }
        check(envelope.getString("core_session") == coreSession) { "Policy handoff Core session mismatch" }
        check(envelope.getInt("core_pid") == Process.myPid()) { "Policy handoff Core PID mismatch" }
        check(envelope.getInt("host_pid") == hostPid) { "Policy handoff Host PID mismatch" }
        val state = JSONObject(envelope.getJSONObject("policy_state").toString())
        check(target.delete()) { "Could not consume Resident policy handoff state" }
        return state
    }

    fun clearByHost(context: Context, coreSession: String, hostPid: Int) {
        val target = file(context)
        if (!target.isFile) return
        val envelope = readBounded(target)
        check(envelope.getString("core_session") == coreSession)
        check(envelope.getInt("host_pid") == hostPid && hostPid == Process.myPid())
        check(target.delete()) { "Could not clear cancelled policy handoff" }
    }

    fun clearForExplicitCoreStop(context: Context, coreSession: String) {
        val target = file(context)
        if (!target.isFile) return
        val envelope = readBounded(target)
        check(envelope.getString("core_session") == coreSession) {
            "Refusing to clear another Core session's policy handoff"
        }
        check(envelope.getInt("core_pid") == Process.myPid()) { "Policy handoff Core PID mismatch" }
        check(target.delete()) { "Could not clear Resident policy handoff state" }
    }

    fun snapshot(context: Context): JSONObject? =
        file(context).takeIf { it.isFile }?.let(::readBounded)?.let { envelope ->
            JSONObject()
                .put("schema", envelope.optInt("schema"))
                .put("core_session", envelope.optString("core_session"))
                .put("core_pid", envelope.optInt("core_pid"))
                .put("host_pid", envelope.optInt("host_pid"))
                .put("updated_wall_ms", envelope.optLong("updated_wall_ms"))
        }

    fun clearAfterVerifiedOwnerLoss(context: Context) {
        val target = file(context)
        if (!target.isFile) return
        val envelope = readBounded(target)
        val corePid = envelope.optInt("core_pid", -1)
        check(corePid > 0 && !ResidentProcessLiveness.exists(corePid)) {
            "Refusing to clear policy handoff while recorded Core PID is still alive"
        }
        check(target.delete()) { "Could not clear stale Resident policy handoff state" }
    }

    private fun write(context: Context, value: JSONObject) {
        val target = file(context)
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES.toInt()) { "Policy handoff state is too large" }
        val staged = File(target.parentFile, "$FILE_NAME.tmp.${Process.myPid()}")
        staged.writeBytes(bytes)
        Os.rename(staged.absolutePath, target.absolutePath)
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, "ai_limbs/runtime_owner/$FILE_NAME")

    private fun readBounded(file: File): JSONObject {
        check(file.isFile && file.length() in 1L..MAX_BYTES) { "Resident policy handoff state is invalid" }
        return JSONObject(file.readText())
    }
}
