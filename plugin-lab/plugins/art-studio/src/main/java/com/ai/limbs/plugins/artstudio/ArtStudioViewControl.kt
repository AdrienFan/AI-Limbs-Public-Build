package com.ai.limbs.plugins.artstudio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal data class StudioViewSettings(
    val panelsHidden: Boolean = false,
    val statusBarVisible: Boolean = true,
    val gridVisible: Boolean = false,
    val pixelGridVisible: Boolean = true,
    val presentationMode: String = "normal"
) {
    fun describe(): JSONObject = JSONObject()
        .put("panelsHidden", panelsHidden)
        .put("statusBarVisible", statusBarVisible)
        .put("gridVisible", gridVisible)
        .put("pixelGridVisible", pixelGridVisible)
        .put("presentationMode", presentationMode)
}

/** The human menu and Laner's capabilities address the same live view state. */
internal object ArtStudioViewControl {
    val state = MutableStateFlow(StudioViewSettings())
    val commands = MutableSharedFlow<String>(extraBufferCapacity = 64)
    @Volatile var canvasAttached: Boolean = false

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
