package com.ai.limbs.plugins.visualmanager

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost
import org.json.JSONObject

internal interface VisualManagerPageActions {
    suspend fun call(action: String, parameters: JSONObject = JSONObject()): JSONObject
}

/** Presentation has no device/file authority. Every operation goes through Core-owned plugin capabilities. */
internal class VisualManagerPresentationClient(
    private val host: InProcessPluginPresentationHost
) : VisualManagerPageActions {
    override suspend fun call(action: String, parameters: JSONObject): JSONObject =
        JSONObject(host.invokePluginCapability("$VISUAL_PLUGIN_ID.$action", parameters.toString()))
}
