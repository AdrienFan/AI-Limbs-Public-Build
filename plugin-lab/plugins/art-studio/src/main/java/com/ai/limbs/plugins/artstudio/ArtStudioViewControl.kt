package com.ai.limbs.plugins.artstudio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.math.ln

internal data class StudioViewSettings(
    val panelsHidden: Boolean = false,
    val statusBarVisible: Boolean = true,
    val gridVisible: Boolean = false,
    val pixelGridVisible: Boolean = true,
    val presentationMode: String = "normal",
    val zoomToolMode: String = "in"
) {
    val zoomToolBadge: String get() = if (zoomToolMode == "in") "大" else "小"

    fun describe(): JSONObject = JSONObject()
        .put("panelsHidden", panelsHidden)
        .put("statusBarVisible", statusBarVisible)
        .put("gridVisible", gridVisible)
        .put("pixelGridVisible", pixelGridVisible)
        .put("presentationMode", presentationMode)
        .put("zoomToolMode", zoomToolMode)
        .put("zoomToolBadge", zoomToolBadge)
}

internal data class StudioCanvasZoom(
    val documentId: String,
    val relativeScale: Float,
    val fitScale: Float
) {
    val percent: Double get() = relativeScale.toDouble() * fitScale * 100.0
    val minPercent: Double get() = 0.1 * fitScale * 100.0
    val maxPercent: Double get() = 16.0 * fitScale * 100.0
    val sliderPosition: Float get() =
        (ln(relativeScale / 0.1f) / ln(160f)).coerceIn(0f, 1f)

    fun describe(): JSONObject = JSONObject().put("documentId", documentId)
        .put("percent", percent).put("minPercent", minPercent).put("maxPercent", maxPercent)
        .put("relativeScale", relativeScale.toDouble())
}

internal data class StudioZoomRequest(val documentId: String, val percent: Double)

/** The human menu and Laner's capabilities address the same live view state. */
internal object ArtStudioViewControl {
    val state = MutableStateFlow(StudioViewSettings())
    val commands = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val canvasZoom = MutableStateFlow<StudioCanvasZoom?>(null)
    val zoomRequests = MutableSharedFlow<StudioZoomRequest>(extraBufferCapacity = 64)
    @Volatile var canvasAttached: Boolean = false

    fun describe(): JSONObject = state.value.describe()
        .put("toolOptionsWindow", ArtStudioToolOptionsControl.state.value.describe())
        .put("canvasZoom", if (canvasAttached) canvasZoom.value?.describe() else null)

    fun requestZoom(documentId: String, percent: Double): JSONObject {
        check(canvasAttached) { "请先打开画室画布，再操作视图" }
        val current = requireNotNull(canvasZoom.value) { "画布尚未完成布局" }
        require(current.documentId == documentId) { "画布已切换，请重新读取 view.state" }
        require(percent.isFinite() && percent in current.minPercent..current.maxPercent) {
            "当前缩放范围为 ${current.minPercent}–${current.maxPercent}%，请读取 view.state"
        }
        check(zoomRequests.subscriptionCount.value > 0 &&
            zoomRequests.tryEmit(StudioZoomRequest(documentId, percent))) {
            "画室视图暂时无法接收操作"
        }
        return JSONObject().put("accepted", true).put("documentId", documentId)
            .put("requestedPercent", percent)
    }

    fun setOption(name: String, enabled: Boolean): JSONObject {
        require(name in setOf("panelsHidden", "statusBarVisible", "gridVisible", "pixelGridVisible")) {
            "尚未实现的视图选项：$name"
        }
        state.update { current ->
            when (name) {
                "panelsHidden" -> current.copy(panelsHidden = enabled)
                "statusBarVisible" -> current.copy(statusBarVisible = enabled)
                "gridVisible" -> current.copy(gridVisible = enabled)
                else -> current.copy(pixelGridVisible = enabled)
            }
        }
        return state.value.describe()
    }

    // One atomic direction state drives both the toolbox badge and canvas taps.
    // Keeping independent UI/AI flags would let the displayed direction disagree.
    fun setZoomToolMode(mode: String): JSONObject {
        require(mode in setOf("in", "out", "toggle")) { "缩放工具方向必须是 in、out 或 toggle" }
        state.update { current ->
            current.copy(zoomToolMode = if (mode == "toggle") {
                if (current.zoomToolMode == "in") "out" else "in"
            } else mode)
        }
        return state.value.describe()
    }

    fun setPresentationMode(mode: String) {
        require(mode in setOf("normal", "fullscreen_portrait", "fullscreen_landscape"))
        state.update { it.copy(presentationMode = mode) }
    }

    fun command(name: String): JSONObject {
        require(name in setOf(
            "zoom_in", "zoom_out", "zoom_100", "fit", "fit_width", "fit_height",
            "rotate_right", "rotate_left", "reset_rotation", "mirror", "reset_display",
            "refresh"
        )) { "尚未实现的视图命令：$name" }
        check(canvasAttached) { "请先打开画室画布，再操作视图" }
        check(commands.subscriptionCount.value > 0 && commands.tryEmit(name)) {
            "画室视图暂时无法接收操作"
        }
        return JSONObject().put("accepted", true).put("command", name)
    }
}
