package com.ai.limbs.plugins.artstudio

import org.json.JSONObject

/** Plugin-owned persistent state; callers serialize transitions with ArtStore's process/file lock. */
internal object ArtDockPanels {
    val ids = listOf("color", "layers", "brushes", "footprints", "animation")
    fun initial(): JSONObject = JSONObject().put("revision", 0L)
        .put("visible", JSONObject().apply { ids.forEach { put(it, true) } })
        .put("activePane", "color").put("restorePane", JSONObject.NULL)
        .put("allCollapsed", false).put("confirmClose", true)

    fun change(current: JSONObject, command: String, panel: String?, enabled: Boolean?,
        suppressCloseConfirmation: Boolean = false): JSONObject {
        val state = JSONObject(current.toString())
        val visible = state.getJSONObject("visible")
        if (command in setOf("set_visible", "expand", "collapse")) require(panel in ids) { "未知停靠面板" }
        when (command) {
            "set_visible" -> {
                requireNotNull(enabled) { "set_visible 需要 enabled" }
                visible.put(requireNotNull(panel), enabled)
                if (enabled) {
                    state.put("activePane", panel).put("allCollapsed", false)
                } else if (state.optString("activePane") == panel) state.put("activePane", JSONObject.NULL)
                if (suppressCloseConfirmation) {
                    require(!enabled) { "只有确认关闭时才能关闭提示" }
                    state.put("confirmClose", false)
                }
            }
            "expand" -> {
                require(visible.getBoolean(requireNotNull(panel))) { "请先显示面板" }
                state.put("activePane", panel).put("allCollapsed", false)
            }
            "collapse" -> if (state.optString("activePane") == panel) state.put("activePane", JSONObject.NULL)
            "collapse_all" -> if (!state.getBoolean("allCollapsed")) {
                state.put("restorePane", state.get("activePane"))
                    .put("activePane", JSONObject.NULL).put("allCollapsed", true)
            }
            "restore" -> if (state.getBoolean("allCollapsed")) {
                val previous = state.optString("restorePane")
                state.put("activePane", if (previous in ids && visible.getBoolean(previous)) previous else JSONObject.NULL)
                    .put("allCollapsed", false).put("restorePane", JSONObject.NULL)
            }
            "set_confirmation" -> state.put("confirmClose", requireNotNull(enabled) { "需要 enabled" })
            else -> error("未知停靠面板命令")
        }
        state.put("revision", current.getLong("revision") + 1)
        return state
    }
}
