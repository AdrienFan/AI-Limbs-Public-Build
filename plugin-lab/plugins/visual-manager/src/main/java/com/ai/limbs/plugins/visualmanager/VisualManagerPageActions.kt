package com.ai.limbs.plugins.visualmanager

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost
import org.json.JSONObject

internal interface VisualManagerPageActions {
    suspend fun dashboard(): JSONObject
    suspend fun screenCapture(parameters: JSONObject = JSONObject()): JSONObject
    suspend fun screenStart(parameters: JSONObject): JSONObject
    suspend fun screenStop(parameters: JSONObject): JSONObject
    suspend fun screenFrame(parameters: JSONObject): JSONObject
    suspend fun cameraStart(parameters: JSONObject): JSONObject
    suspend fun cameraStop(parameters: JSONObject): JSONObject
    suspend fun cameraFrame(parameters: JSONObject): JSONObject
    suspend fun cameraCapture(parameters: JSONObject): JSONObject
    suspend fun stopAll(): JSONObject
    suspend fun deleteAsset(parameters: JSONObject): JSONObject
    suspend fun clearAssets(): JSONObject
}

internal class VisualManagerPresentationClient(
    private val host: InProcessPluginPresentationHost
) : VisualManagerPageActions {
    override suspend fun dashboard(): JSONObject =
        invoke("status")

    override suspend fun screenCapture(parameters: JSONObject): JSONObject =
        invoke("screen.capture", parameters)

    override suspend fun screenStart(parameters: JSONObject): JSONObject =
        invoke("screen.start", parameters)

    override suspend fun screenStop(parameters: JSONObject): JSONObject =
        invoke("screen.stop", parameters)

    override suspend fun screenFrame(parameters: JSONObject): JSONObject =
        invoke("screen.frame", parameters)

    override suspend fun cameraStart(parameters: JSONObject): JSONObject =
        invoke("camera.start", parameters)

    override suspend fun cameraStop(parameters: JSONObject): JSONObject =
        invoke("camera.stop", parameters)

    override suspend fun cameraFrame(parameters: JSONObject): JSONObject =
        invoke("camera.frame", parameters)

    override suspend fun cameraCapture(parameters: JSONObject): JSONObject =
        invoke("camera.capture", parameters)

    override suspend fun stopAll(): JSONObject {
        val screen = runCatching { invoke("screen.stop") }
            .fold(onSuccess = { it }, onFailure = { errorJson(it) })
        val camera = runCatching { invoke("camera.stop") }
            .fold(onSuccess = { it }, onFailure = { errorJson(it) })
        return JSONObject()
            .put("screen", screen)
            .put("camera", camera)
    }

    override suspend fun deleteAsset(parameters: JSONObject): JSONObject =
        invoke("assets.delete", parameters)

    override suspend fun clearAssets(): JSONObject =
        invoke("assets.clear")

    private suspend fun invoke(
        name: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject = JSONObject(
        host.invokePluginCapability(
            "$VISUAL_PLUGIN_ID.$name",
            parameters.toString()
        )
    )

    private fun errorJson(error: Throwable): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", error.message ?: error.javaClass.simpleName)
}
