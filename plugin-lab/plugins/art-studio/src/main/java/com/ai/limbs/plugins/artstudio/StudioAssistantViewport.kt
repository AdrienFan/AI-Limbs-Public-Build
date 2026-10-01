package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** An actual bitmap viewport owned by the assistant, never an Android page observation. */
internal data class StudioAssistantViewport(
    val documentId: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Double = minOf(1024.0 / sourceWidth, 768.0 / sourceHeight) * 0.98,
    val rotation: Double = 0.0,
    val mirrored: Boolean = false
) {
    init { require(documentId.isNotBlank() && sourceWidth > 0 && sourceHeight > 0) }
    val width get() = 1024
    val height get() = 768
    private val fitScale get() = minOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
    val minScale get() = fitScale * 0.1
    val maxScale get() = fitScale * 16.0

    fun zoom(document: String, percent: Double): StudioAssistantViewport {
        require(document == documentId) { "画布已切换，请重新读取 view.state" }
        require(percent.isFinite() && percent / 100.0 in minScale..maxScale) {
            "缩放比例超出当前 AI 视图范围，请读取 view.state"
        }
        return copy(scale = percent / 100.0)
    }

    fun command(name: String): StudioAssistantViewport {
        require(name in ArtStudioViewControl.commandNames) { "未知视图命令：$name" }
        val radians = Math.toRadians(rotation)
        val rotatedWidth = sourceWidth * abs(cos(radians)) + sourceHeight * abs(sin(radians))
        val rotatedHeight = sourceWidth * abs(sin(radians)) + sourceHeight * abs(cos(radians))
        fun scaled(value: Double) = copy(scale = value.coerceIn(minScale, maxScale))
        return when (name) {
            "zoom_in" -> scaled(scale * 1.25)
            "zoom_out" -> scaled(scale / 1.25)
            "zoom_100" -> scaled(1.0)
            "fit" -> scaled(minOf(width / rotatedWidth, height / rotatedHeight) * 0.98)
            "fit_width" -> scaled(width / rotatedWidth)
            "fit_height" -> scaled(height / rotatedHeight)
            "rotate_right" -> copy(rotation = (rotation + 15.0) % 360.0)
            "rotate_left" -> copy(rotation = (rotation - 15.0) % 360.0)
            "reset_rotation" -> copy(rotation = 0.0)
            "mirror" -> copy(mirrored = !mirrored)
            "reset_display" -> copy(rotation = 0.0, mirrored = false).command("fit")
            "refresh" -> this
            else -> error("未知视图命令：$name")
        }
    }

    fun describe(): JSONObject = JSONObject().put("target", "assistant")
        .put("canvasAttached", true).put("pageVisible", false)
        .put("viewSurface", "offscreen_bitmap")
        .put("viewport", JSONObject().put("width", width).put("height", height)
            .put("rotation", rotation).put("mirrored", mirrored))
        .put("canvasZoom", JSONObject().put("documentId", documentId)
            .put("percent", scale * 100.0).put("minPercent", minScale * 100.0)
            .put("maxPercent", maxScale * 100.0).put("relativeScale", scale / fitScale))
}
