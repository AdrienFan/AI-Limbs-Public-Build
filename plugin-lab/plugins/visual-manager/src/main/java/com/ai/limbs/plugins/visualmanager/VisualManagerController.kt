package com.ai.limbs.plugins.visualmanager

import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal const val VISUAL_PLUGIN_ID = "plugin.system.visual_manager"
internal const val VISUAL_PAGE_ID = "$VISUAL_PLUGIN_ID.page"
internal const val VISUAL_SCREEN_ID = "$VISUAL_PLUGIN_ID.screen"
internal const val VISUAL_TILE_ID = "$VISUAL_PLUGIN_ID.tile"

internal class VisualManagerController(
    private val host: InProcessPluginUiHost
) : VisualManagerPageActions {
    override suspend fun dashboard(): JSONObject = JSONObject()
        .put("screen", safeHost(HOST_SCREEN_SESSION, "status"))
        .put("screen_targets", safeHost(HOST_SCREEN_SESSION, "list_targets"))
        .put("camera", safeHost(HOST_CAMERA_SESSION, "status"))
        .put("camera_sources", safeHost(HOST_CAMERA_SESSION, "list_sources"))
        .put("assets", listAssets())

    suspend fun screenTargets(): JSONObject =
        invokeHost(HOST_SCREEN_SESSION, "list_targets")

    override suspend fun screenCapture(parameters: JSONObject): JSONObject {
        val result = invokeHost(HOST_SCREEN_CAPTURE, "capture", parameters)
        return attachManagedAsset("screen", result)
    }

    override suspend fun screenStart(parameters: JSONObject): JSONObject {
        val request = JSONObject(parameters.toString())
        val prime = if (request.has("prime")) request.optBoolean("prime", true) else true
        request.remove("prime")
        val started = invokeHost(HOST_SCREEN_SESSION, "start", request)
        val sessionId = started.optString("session_id").trim()
        if (prime && started.optBoolean("active", false) && sessionId.isNotEmpty()) {
            try {
                val firstFrame = screenFrame(JSONObject().put("session_id", sessionId))
                started.put("first_frame", firstFrame)
            } catch (error: Throwable) {
                runCatching {
                    invokeHost(
                        HOST_SCREEN_SESSION,
                        "stop",
                        JSONObject().put("session_id", sessionId)
                    )
                }
                throw error
            }
        }
        return started
    }

    override suspend fun screenStop(parameters: JSONObject): JSONObject =
        invokeHost(HOST_SCREEN_SESSION, "stop", parameters)

    override suspend fun screenFrame(parameters: JSONObject): JSONObject {
        val result = invokeHost(HOST_SCREEN_SESSION, "frame", parameters)
        return attachManagedAsset("screen", result)
    }

    suspend fun cameraSources(): JSONObject =
        invokeHost(HOST_CAMERA_SESSION, "list_sources")

    override suspend fun cameraStart(parameters: JSONObject): JSONObject =
        invokeHost(HOST_CAMERA_SESSION, "start", parameters)

    suspend fun cameraConfigure(parameters: JSONObject): JSONObject =
        invokeHost(HOST_CAMERA_SESSION, "configure", parameters)

    override suspend fun cameraStop(parameters: JSONObject): JSONObject =
        invokeHost(HOST_CAMERA_SESSION, "stop", parameters)

    override suspend fun cameraFrame(parameters: JSONObject): JSONObject {
        val result = invokeHost(HOST_CAMERA_SESSION, "frame", parameters)
        return attachManagedAsset("camera", result)
    }

    override suspend fun cameraCapture(parameters: JSONObject): JSONObject {
        val result = invokeHost(HOST_CAMERA_CAPTURE, "capture", parameters)
        return attachManagedAsset("camera", result)
    }

    override suspend fun stopAll(): JSONObject {
        val screen = runCatching {
            invokeHost(HOST_SCREEN_SESSION, "stop")
        }.fold(
            onSuccess = { it },
            onFailure = { errorJson(it) }
        )
        val camera = runCatching {
            invokeHost(HOST_CAMERA_SESSION, "stop")
        }.fold(
            onSuccess = { it },
            onFailure = { errorJson(it) }
        )
        return JSONObject().put("screen", screen).put("camera", camera)
    }

    fun listAssets(): JSONObject {
        val root = assetsRoot()
        val items = root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { metadataFile ->
                runCatching {
                    val meta = JSONObject(metadataFile.readText(Charsets.UTF_8))
                    val payloadName = meta.optString("file_name").trim()
                    val payload = File(root, payloadName)
                    if (!payload.isFile) {
                        metadataFile.delete()
                        null
                    } else {
                        JSONObject(meta.toString())
                            .put("bytes", payload.length())
                            .put("file_path", payload.absolutePath)
                    }
                }.getOrNull()
            }
            .sortedByDescending { it.optLong("captured_at_ms", 0L) }
            .toList()

        var totalBytes = 0L
        val array = JSONArray()
        items.forEach {
            totalBytes += it.optLong("bytes", 0L)
            array.put(it)
        }
        return JSONObject()
            .put("count", items.size)
            .put("bytes", totalBytes)
            .put("items", array)
    }

    override suspend fun deleteAsset(parameters: JSONObject): JSONObject {
        val assetId = required(parameters, "asset_id")
        require(assetId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) {
            "Invalid visual asset id"
        }
        val root = assetsRoot()
        val metadata = File(root, "$assetId.json")
        if (!metadata.isFile) {
            return JSONObject().put("deleted", false).put("asset_id", assetId)
        }
        val json = runCatching {
            JSONObject(metadata.readText(Charsets.UTF_8))
        }.getOrNull()
        val payload = json?.optString("file_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { File(root, it) }
        val payloadDeleted = payload?.let { !it.exists() || it.delete() } ?: true
        val metadataDeleted = !metadata.exists() || metadata.delete()
        return JSONObject()
            .put("deleted", payloadDeleted && metadataDeleted)
            .put("asset_id", assetId)
    }

    override suspend fun clearAssets(): JSONObject {
        val root = assetsRoot()
        val count = listAssets().optInt("count", 0)
        root.deleteRecursively()
        root.mkdirs()
        hostScratchRoot().deleteRecursively()
        return JSONObject().put("cleared", true).put("deleted_count", count)
    }

    private suspend fun safeHost(
        primitiveId: String,
        operation: String
    ): JSONObject = runCatching {
        invokeHost(primitiveId, operation)
    }.fold(
        onSuccess = { it },
        onFailure = { errorJson(it).put("available", false) }
    )

    private suspend fun invokeHost(
        primitiveId: String,
        operation: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject {
        val payload = JSONObject(parameters.toString()).put("operation", operation)
        return JSONObject(host.invokeHostCapability(primitiveId, payload.toString()))
    }

    private fun attachManagedAsset(kind: String, result: JSONObject): JSONObject {
        val source = findExistingFile(result)
        if (source == null) {
            return JSONObject(result.toString())
                .put("managed_asset", JSONObject.NULL)
                .put("asset_archived", false)
        }
        val capturedAt = findCapturedAt(result).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val extension = resolveExtension(result, source)
        val assetId = "$kind-$capturedAt-${UUID.randomUUID().toString().take(8)}"
        val payload = File(assetsRoot(), "$assetId.$extension")
        source.copyTo(payload, overwrite = false)

        val meta = JSONObject()
            .put("asset_id", assetId)
            .put("kind", kind)
            .put("captured_at_ms", capturedAt)
            .put("mime_type", findMimeType(result))
            .put("file_name", payload.name)
            .put("bytes", payload.length())

        File(assetsRoot(), "$assetId.json")
            .writeText(meta.toString(2), Charsets.UTF_8)

        deleteOwnedHostScratch(source)

        return JSONObject(result.toString())
            .put(
                "managed_asset",
                JSONObject(meta.toString()).put("file_path", payload.absolutePath)
            )
            .put("asset_archived", true)
    }

    private fun findExistingFile(value: Any?): File? = when (value) {
        is JSONObject -> {
            val preferred = listOf(
                "file_path",
                "path",
                "image_path",
                "screenshot_path",
                "output_path"
            )
            preferred.asSequence()
                .mapNotNull { key ->
                    if (!value.has(key) || value.isNull(key)) null
                    else fileFromString(value.optString(key))
                }
                .firstOrNull()
                ?: value.keys().asSequence()
                    .mapNotNull { key -> findExistingFile(value.opt(key)) }
                    .firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { index -> findExistingFile(value.opt(index)) }
            .firstOrNull()
        is String -> fileFromString(value)
        else -> null
    }

    private fun fileFromString(value: String): File? {
        val text = value.trim()
        if (text.isEmpty() || !text.startsWith("/")) return null
        return runCatching { File(text).takeIf { it.isFile } }.getOrNull()
    }

    private fun findCapturedAt(value: Any?): Long = when (value) {
        is JSONObject -> {
            val direct = value.optLong("captured_at_ms", 0L)
            if (direct > 0L) direct
            else value.keys().asSequence()
                .map { key -> findCapturedAt(value.opt(key)) }
                .firstOrNull { it > 0L } ?: 0L
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .map { index -> findCapturedAt(value.opt(index)) }
            .firstOrNull { it > 0L } ?: 0L
        else -> 0L
    }

    private fun findMimeType(value: Any?): String = when (value) {
        is JSONObject -> {
            value.optString("mime_type").trim().takeIf { it.isNotEmpty() }
                ?: value.keys().asSequence()
                    .map { key -> findMimeType(value.opt(key)) }
                    .firstOrNull { it.isNotEmpty() }
                ?: ""
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .map { index -> findMimeType(value.opt(index)) }
            .firstOrNull { it.isNotEmpty() } ?: ""
        else -> ""
    }

    private fun resolveExtension(result: JSONObject, source: File): String {
        val mime = findMimeType(result).lowercase()
        return when {
            "png" in mime -> "png"
            "jpeg" in mime || "jpg" in mime -> "jpg"
            "webp" in mime -> "webp"
            source.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") ->
                source.extension.lowercase().replace("jpeg", "jpg")
            else -> "bin"
        }
    }

    private fun deleteOwnedHostScratch(source: File) {
        runCatching {
            val root = hostScratchRoot().canonicalFile
            val candidate = source.canonicalFile
            if (candidate.path.startsWith(root.path + File.separator)) {
                candidate.delete()
            }
        }
    }

    private fun assetsRoot(): File =
        File(host.cacheDir, "visual-assets").apply { mkdirs() }

    private fun hostScratchRoot(): File =
        File(
            host.applicationContext.cacheDir,
            "visual-host/${safePathPart(host.pluginId)}"
        )

    private fun errorJson(error: Throwable): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", error.message ?: error.javaClass.simpleName)

    private fun required(parameters: JSONObject, key: String): String =
        parameters.optString(key).trim().takeIf { it.isNotEmpty() }
            ?: error("$key is required")

    private fun safePathPart(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private companion object {
        const val HOST_SCREEN_CAPTURE = "host.screen.capture@1"
        const val HOST_SCREEN_SESSION = "host.screen.session@1"
        const val HOST_CAMERA_SESSION = "host.camera.session@1"
        const val HOST_CAMERA_CAPTURE = "host.camera.capture@1"
    }
}
