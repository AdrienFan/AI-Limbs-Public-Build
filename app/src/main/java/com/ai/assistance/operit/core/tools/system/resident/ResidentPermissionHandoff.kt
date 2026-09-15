package com.ai.assistance.operit.core.tools.system.resident

import android.os.IBinder
import android.os.Process
import com.ai.assistance.operit.core.tools.system.ShizukuConnectionInfo
import com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime
import org.json.JSONObject

/** A kernel-only retention permit, pinned to one acknowledged Core and backend instance. */
internal class ResidentPermissionHandoff private constructor(
    private val backend: IBinder,
    private val backendInstance: String,
    private val backendPid: Int,
    private val corePid: Int,
    private val coreSession: String
) {
    internal fun coreSessionId(): String = coreSession
    internal fun coreProcessId(): Int = corePid

    fun verify(current: ShizukuConnectionInfo) {
        check(PrivilegeRuntime.isSelected()) { "AI Limbs permission backend was deselected during handoff" }
        check(current.binder == backend && current.binder.isBinderAlive) { "Handoff permission backend changed" }
        val state = ResidentPermissionWire.describe(current.binder)
        check(state.getString("instance_id") == backendInstance && state.getInt("pid") == backendPid &&
            state.getInt("uid") == current.uid && state.getInt("core_pid") == corePid &&
            state.getString("core_session") == coreSession && state.getBoolean("core_lifetime_alive") &&
            state.getString("runtime_owner") == "handoff_prepared") {
            "Prepared Core no longer holds this permission backend; retirement is not authorized"
        }
    }

    companion object {
        /** The controller supplies a socket-authenticated response, never a plugin request body. */
        fun fromPreparedCore(core: JSONObject): ResidentPermissionHandoff {
            val pid = core.getInt("pid")
            check(pid > 0 && pid != Process.myPid() && core.getInt("uid") == Process.myUid())
            val state = core.getJSONObject("backend")
            check(state.getString("state") == "prepared") { "Core has not prepared backend ownership" }
            val connection = checkNotNull(PrivilegeRuntime.connection()) { "Permission backend is disconnected" }
            return ResidentPermissionHandoff(
                connection.binder, state.getString("instance_id"), state.getInt("pid"),
                pid, core.getString("session_id")
            ).also { it.verify(connection) }
        }
    }
}
