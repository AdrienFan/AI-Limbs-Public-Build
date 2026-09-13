package com.ai.limbs.plugins.uieditor

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import org.json.JSONObject

data class UiLayoutStatus(
    val active: Boolean,
    val surface: String?,
    val mode: String?,
    val toolboxOrderCount: Int
)

internal class UiEditorClient(private val host: InProcessPluginHost) {
    suspend fun status(): UiLayoutStatus = parse(invoke("status"))

    suspend fun startToolboxLayout(): UiLayoutStatus = parse(
        invoke(
            "start",
            JSONObject()
                .put("surface", "toolbox")
                .put("mode", "layout")
                .put("navigate", true)
        )
    )

    suspend fun finish(): UiLayoutStatus = parse(invoke("finish"))

    suspend fun resetToolbox(): UiLayoutStatus = parse(
        invoke("reset", JSONObject().put("surface", "toolbox"))
    )

    private suspend fun invoke(operation: String, parameters: JSONObject = JSONObject()): JSONObject {
        val payload = JSONObject(parameters.toString()).put("operation", operation)
        return JSONObject(host.invokeHostCapability(HOST_UI_LAYOUT, payload.toString()))
    }

    private fun parse(root: JSONObject): UiLayoutStatus = UiLayoutStatus(
        active = root.optBoolean("active"),
        surface = root.optString("surface").trim().ifBlank { null },
        mode = root.optString("mode").trim().ifBlank { null },
        toolboxOrderCount = root.optJSONArray("toolbox_order")?.length() ?: 0
    )

    private companion object {
        const val HOST_UI_LAYOUT = "host.ui.layout@1"
    }
}
