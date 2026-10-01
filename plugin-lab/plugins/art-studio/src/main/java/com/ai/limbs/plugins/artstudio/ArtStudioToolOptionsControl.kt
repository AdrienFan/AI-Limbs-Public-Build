package com.ai.limbs.plugins.artstudio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal data class StudioToolWindowState(
    val activeTool: String = "ink",
    val toolId: String = "ink",
    val open: Boolean = false,
    val minimized: Boolean = false,
    val xDp: Float = 12f,
    val yDp: Float = 12f
) {
    fun describe(): JSONObject = JSONObject().put("activeTool", activeTool).put("toolId", toolId)
        .put("open", open).put("minimized", minimized)
        .put("xDp", xDp.toDouble()).put("yDp", yDp.toDouble())
        .put("coordinateSpace", "studio-content-dp")
        .put("visible", open && ArtStudioViewControl.canvasAttached)
}

/** UI and AI share tool selection and window state; no host window or document mutation. */
internal object ArtStudioToolOptionsControl {
    val state = MutableStateFlow(StudioToolWindowState())

    fun select(id: String) {
        require(ArtToolCatalog.implemented.any { it.first == id }) { "工具尚未实现：$id" }
        state.update { it.copy(activeTool = id, toolId = if (it.open) id else it.toolId) }
    }

    fun show(id: String) {
        val implemented = ArtToolCatalog.implemented.any { it.first == id }
        require(implemented || ArtToolCatalog.pending.any { it.id == id }) { "未知工具：$id" }
        state.update { it.copy(activeTool = if (implemented) id else it.activeTool,
            toolId = id, open = true, minimized = false) }
    }

    fun minimize() {
        state.update { check(it.open) { "参数浮窗尚未打开" }; it.copy(minimized = true) }
    }

    fun restore() {
        state.update { check(it.open) { "参数浮窗尚未打开" }; it.copy(minimized = false) }
    }

    fun close() { state.update { it.copy(open = false) } }

    fun move(xDp: Float, yDp: Float) {
        require(xDp.isFinite() && yDp.isFinite() && xDp in 0f..1_000_000f && yDp in 0f..1_000_000f) {
            "浮窗坐标须为有限、非负的 dp 值"
        }
        state.update { it.copy(xDp = xDp, yDp = yDp) }
    }

    fun command(p: JSONObject): JSONObject {
        val action = p.getString("action")
        require(action in setOf("show", "minimize", "restore", "close", "move")) {
            "未知浮窗操作：$action"
        }
        check(ArtStudioViewControl.canvasAttached) { "请先打开画室画布" }
        when (action) {
            "show" -> show(p.getString("toolId"))
            "minimize" -> minimize()
            "restore" -> restore()
            "close" -> close()
            "move" -> move(p.getDouble("xDp").toFloat(), p.getDouble("yDp").toFloat())
        }
        return state.value.describe().put("accepted", true)
    }
}
