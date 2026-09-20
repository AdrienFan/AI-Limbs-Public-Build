package com.ai.assistance.operit.core.tools.system.resident

import java.io.File
import org.json.JSONObject

internal object ResidentProcessIsolation {
    private const val ADBD_CGROUP_PREFIX = "/system/uid_0/pid_"

    fun snapshot(pid: Int): JSONObject {
        val path = File("/proc/$pid/cgroup")
        val content = runCatching { path.readText().trim() }.getOrElse { error ->
            return JSONObject()
                .put("pid", pid)
                .put("readable", false)
                .put("isolated_from_adbd", false)
                .put("error", error.toString().take(512))
        }
        val adbdBound = content.lineSequence().any { it.contains(ADBD_CGROUP_PREFIX) }
        return JSONObject()
            .put("pid", pid)
            .put("readable", true)
            .put("isolated_from_adbd", !adbdBound)
            .put("cgroup", content)
    }

    fun requireDetachedFromAdbd(pid: Int, role: String): JSONObject {
        val state = snapshot(pid)
        check(state.optBoolean("readable", false)) {            "$role cgroup could not be inspected: ${state.optString("error", "unknown")}"
        }
        check(state.optBoolean("isolated_from_adbd", false)) {
            "$role is still attached to the adbd cgroup: ${state.optString("cgroup")}"
        }
        return state
    }
}