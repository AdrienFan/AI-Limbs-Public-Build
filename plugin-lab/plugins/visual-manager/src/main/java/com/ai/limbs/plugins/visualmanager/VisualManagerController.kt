package com.ai.limbs.plugins.visualmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.os.SystemClock
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val VISUAL_PLUGIN_ID = "plugin.system.visual_manager"
internal const val VISUAL_PAGE_ID = "$VISUAL_PLUGIN_ID.page"
internal const val VISUAL_SCREEN_ID = "$VISUAL_PLUGIN_ID.screen"
internal const val VISUAL_TILE_ID = "$VISUAL_PLUGIN_ID.tile"
internal const val VISUAL_STATE_ID = "$VISUAL_PLUGIN_ID.state"
internal const val VISUAL_MESSAGE_CONTEXT_ID = "$VISUAL_PLUGIN_ID.message_context"
internal const val VISUAL_FEEDBACK_ID = "$VISUAL_PLUGIN_ID.operation_feedback"

/** Core owns workflow, operation outcomes, session coordination and all visual files. */
internal class VisualManagerController(private val host: InProcessPluginUiHost) : VisualManagerPageActions {
    private val pageReader = VisualPageReader()
    private val locks = mapOf("screen" to Mutex(), "camera" to Mutex(), "images" to Mutex(), "page" to Mutex())
    private val stateLock = Any()
    private val states = mutableMapOf<String, JSONObject>()
    private val generations = mutableMapOf("screen" to 0L, "camera" to 0L)
    private val previewLock = Mutex()
    private val previews = mutableMapOf<String, JSONObject>()
    private var screenSample: VisualSample? = null // Owned by previewLock; matches the screen preview frame_id.
    private var revision = 0L
    private val signal = MutableStateFlow<String?>("""{"revision":0}""")
    val stateProvider = object : InProcessUiStateProvider {
        override val stateJson: StateFlow<String?> = signal.asStateFlow()
        override suspend fun perform(eventId: String, payloadJson: String): String =
            error("视觉状态通道只读，请调用插件能力")
    }
    private fun publishState() {
        signal.value = JSONObject().put("revision", revision).toString()
    }

    override suspend fun call(action: String, parameters: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            val result = when (action) {
                "status" -> dashboard()
                "sources" -> sources(kind(parameters))
                "permission" -> permission(parameters)
                "start" -> start(kind(parameters), parameters)
                "frame" -> frame(kind(parameters), parameters)
                "get_frame" -> {
                    require(!parameters.has("save")) { "get_frame 不保存图像记录；请使用 frame save=true" }
                    frame(kind(parameters), parameters)
                }
                "tap_on_frame" -> tapOnFrame(parameters)
                "wait_for_visual_change" -> waitForScreen(parameters, "change")
                "wait_until_stable" -> waitForScreen(parameters, "stable")
                "operation.feedback" -> operationFeedback(parameters)
                "message.context" -> cameraMessageContext(parameters)
                "capture" -> capture(kind(parameters), parameters)
                "stop" -> stop(parameters)
                "preview.read" -> previewLock.withLock { previewRead(kind(parameters), parameters.optInt("max_edge", 1024)) }
                "preview.save" -> perform("images", "保存预览") {
                    previewLock.withLock {
                        val meta = previews[kind(parameters)] ?: error("当前来源还没有预览画面")
                        archive(File(previewRoot(), meta.getString("file_name")), meta)
                    }
                }
                "images.list" -> locks.getValue("images").withLock { listImages() }
                "images.read" -> locks.getValue("images").withLock {
                    val meta = imageMetadata(parameters.getString("asset_id"))
                    val image = encodedImage(imageFile(meta), meta, parameters.optInt("max_edge", 1024))
                    attachImage(image, image)
                }
                "images.delete" -> perform("images", "删除图像") {
                    val meta = imageMetadata(parameters.getString("asset_id"))
                    check(imageFile(meta).delete()) { "图像文件删除失败" }
                    check(File(imagesRoot(), "${meta.getString("asset_id")}.json").delete()) { "图像元数据删除失败" }
                    JSONObject().put("deleted", true).put("asset_id", meta.getString("asset_id"))
                }
                "images.clear" -> perform("images", "清空图像记录") {
                    val count = listImages().getInt("count")
                    check(imagesRoot().deleteRecursively()) { "图像记录清理失败" }
                    imagesRoot()
                    JSONObject().put("cleared", true).put("deleted_count", count)
                }
                "page.inspect" -> perform("page", "读取页面") {
                    val request = JSONObject().put("format", "json").put("detail", "full")
                    if (parameters.has("display")) request.put("display", parameters.getString("display"))
                    val response = hostCall("host.ui.automation@1", "snapshot", request)
                    VisualOperationResult.requireFlag(response, "success", "页面读取未完成")
                    pageReader.capture(response.getJSONObject("result"))
                }
                "page.text" -> locks.getValue("page").withLock { pageReader.read(parameters) }
                else -> error("未知视觉工作台操作：$action")
            }
            result.put("success", true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            host.logger.e("VisualWorkbench", "Operation failed: $action", error)
            val failure = error as? VisualOperationFailure
            JSONObject().put("success", false).put("error_code", failure?.code ?: "VISUAL_OPERATION_FAILED")
                .put("error", error.message ?: error.javaClass.simpleName)
                .put("details", failure?.details ?: JSONObject())
        }
    }

    private fun kind(p: JSONObject): String {
        val kind = p.getString("kind")
        require(kind == "screen" || kind == "camera") { "kind 必须是 screen 或 camera" }
        return kind
    }

    suspend fun postActionFeedback(request: JSONObject): JSONObject {
        val result = call("operation.feedback", request)
        if (result.getBoolean("success")) return result
        return ScreenFeedbackContract.response(request, "FAILED")
            .put("error_code", result.getString("error_code")).put("error", result.getString("error"))
    }

    suspend fun readMessageContext(request: JSONObject): JSONObject {
        val result = call("message.context", request)
        if (result.getBoolean("success")) return result
        return CameraMessageContextContract.response(request, "FAILED")
            .put("error_code", result.getString("error_code")).put("error", result.getString("error"))
    }

    private suspend fun cameraMessageContext(request: JSONObject): JSONObject {
        CameraMessageContextContract.validate(request)
        val status = VisualOperationResult.requireHost(hostCall(sessionPrimitive("camera"), "status"))
        if (!status.getBoolean("active")) return CameraMessageContextContract.response(request, "INACTIVE")
        val sessions = status.getJSONArray("sessions")
        check(sessions.length() == 1) { "Expected one active camera session" }
        val lock = locks.getValue("camera")
        if (!lock.tryLock()) throw VisualOperationFailure("CAMERA_CONTEXT_BUSY", "相机取帧正在进行，本轮未取图")
        val generation = synchronized(stateLock) { generations.getValue("camera") }
        var scratch: File? = null
        try {
            setState("camera", "BUSY", "获取本轮相机画面")
            val captured = VisualOperationResult.requireHost(hostCall(sessionPrimitive("camera"), "frame", JSONObject()
                .put("session_id", sessions.getJSONObject(0).getString("session_id"))
                .put("deadline_elapsed_ms", request.getLong("deadline_elapsed_ms"))))
            scratch = File(captured.getString("file_path"))
            CameraMessageContextContract.requireFresh(request, captured)
            val image = updatePreview("camera", captured, generation, 524_288)
            val imageContent = content(image)
            image.remove("data")
            return synchronized(stateLock) {
                check(generation == generations.getValue("camera")) { "取帧已被停止指令取消" }
                setState("camera", "IDLE", "本轮相机画面已更新")
                CameraMessageContextContract.response(request, "READY")
                    .put("session_id", captured.getString("session_id")).put("source_id", captured.getString("source_id"))
                    .put("captured_at_ms", captured.getLong("captured_at_ms")).put("freshness", captured.getJSONObject("freshness"))
                    .put("image", image).put("mcp_content", imageContent)
            }
        } catch (error: Exception) {
            synchronized(stateLock) {
                if (generation == generations.getValue("camera")) setState("camera", "ERROR", error.message ?: "本轮取帧失败")
            }
            throw error
        } finally {
            try { scratch?.let { deleteHostScratch(it) } } finally { lock.unlock() }
        }
    }

    private suspend fun operationFeedback(request: JSONObject): JSONObject {
        ScreenFeedbackContract.validateRequest(request)
        val status = VisualOperationResult.requireHost(hostCall(sessionPrimitive("screen"), "status"))
        if (!status.getBoolean("active") || !status.getBoolean("projection_ready")) {
            return ScreenFeedbackContract.response(request, "INACTIVE")
        }
        val sessions = status.getJSONArray("sessions")
        check(sessions.length() == 1 && sessions.getJSONObject(0).getString("state") == "READY") {
            "Shared-screen session is not ready for feedback"
        }
        // Feedback must not queue behind another long screen operation or initiate a new session.
        val lock = locks.getValue("screen")
        if (!lock.tryLock()) throw VisualOperationFailure("SCREEN_FEEDBACK_BUSY", "屏幕取帧正在进行，本次操作反馈未取图")
        val generation = synchronized(stateLock) { generations.getValue("screen") }
        var scratch: File? = null
        try {
            setState("screen", "BUSY", "获取操作后画面")
            val captured = hostCall(sessionPrimitive("screen"), "frame", JSONObject()
                .put("session_id", sessions.getJSONObject(0).getString("session_id")).put("fresh", true).put("frame_format", "rgba8888")
                .put("deadline_elapsed_ms", request.getLong("deadline_elapsed_ms")))
            VisualOperationResult.requireHost(captured)
            val hostFrame = captured.getJSONObject("frame")
            VisualOperationResult.requireHost(hostFrame)
            scratch = File(hostFrame.getString("path"))
            ScreenFeedbackContract.requireFresh(request, captured)
            val image = updatePreview("screen", captured, generation, 524_288, 960)
            image.put("last_operation", JSONObject().put("operation_id", request.getString("operation_id"))
                .put("completed_elapsed_ms", request.getLong("completed_elapsed_ms"))
                .apply {
                    for (key in listOf("tool", "operation_success", "action_parameters")) if (request.has(key)) put(key, request.get(key))
                })
            val imageContent = content(image)
            image.remove("data")
            return synchronized(stateLock) {
                check(generation == generations.getValue("screen")) { "取帧已被停止指令取消" }
                setState("screen", "IDLE", "操作后画面已更新")
                ScreenFeedbackContract.response(request, "READY")
                    .put("session_id", captured.getString("session_id"))
                    .put("target_id", captured.getString("target_id"))
                    .put("captured_at_ms", captured.getLong("captured_at_ms"))
                    .put("freshness", captured.getJSONObject("freshness"))
                    .put("image", image).put("mcp_content", imageContent)
            }
        } catch (error: Exception) {
            synchronized(stateLock) {
                if (generation == generations.getValue("screen")) setState("screen", "ERROR", error.message ?: "操作反馈取帧失败")
            }
            throw error
        } finally {
            try { scratch?.let { deleteHostScratch(it) } }
            finally { lock.unlock() }
        }
    }

    private suspend fun perform(channel: String, label: String, block: suspend (Long?) -> JSONObject): JSONObject =
        locks.getValue(channel).withLock {
            val generation = synchronized(stateLock) { generations[channel] }
            setState(channel, "BUSY", label)
            try {
                val result = block(generation)
                synchronized(stateLock) {
                    if (generation != generations[channel]) {
                        throw VisualOperationFailure("OPERATION_CANCELLED", "操作已被停止指令取消")
                    }
                    setState(channel, "IDLE", "$label 完成")
                }
                result
            } catch (error: CancellationException) {
                setState(channel, "IDLE", "$label 已取消")
                throw error
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (generation == generations[channel]) setState(channel, "ERROR", error.message ?: label)
                }
                throw error
            }
        }

    private fun setState(channel: String, phase: String, message: String) = synchronized(stateLock) {
        revision++
        states[channel] = JSONObject().put("phase", phase).put("message", message)
            .put("updated_at_ms", System.currentTimeMillis())
        publishState()
    }

    private suspend fun hostCall(primitive: String, operation: String, p: JSONObject = JSONObject()): JSONObject =
        JSONObject(host.invokeHostCapability(primitive, JSONObject(p.toString()).put("operation", operation).toString()))

    private suspend fun hostStatus(primitive: String, operation: String): JSONObject =
        try { VisualOperationResult.requireHost(hostCall(primitive, operation)) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            JSONObject().put("available", false).put("error", error.message ?: error.javaClass.simpleName)
        }

    private fun sessionPrimitive(kind: String) = "host.$kind.session@1"

    private suspend fun dashboard(): JSONObject {
        val screen = hostStatus(sessionPrimitive("screen"), "status")
        val camera = hostStatus(sessionPrimitive("camera"), "status")
        val result = JSONObject().put("screen", screen).put("camera", camera)
            .put("screen_targets", hostStatus(sessionPrimitive("screen"), "list_targets"))
            .put("camera_sources", hostStatus(sessionPrimitive("camera"), "list_sources"))
            .put("camera_permission", hostStatusPermission())
            .put("assets", locks.getValue("images").withLock { listImages() })
            .put("page", locks.getValue("page").withLock { pageReader.status() })
        synchronized(stateLock) {
            val operations = JSONObject()
            states.forEach { (key, value) -> operations.put(key, JSONObject(value.toString())) }
            result.put("operations", operations).put("revision", revision)
        }
        previewLock.withLock {
            val summary = JSONObject()
            previews.forEach { (key, value) -> summary.put(key, JSONObject(value.toString())) }
            result.put("previews", summary)
        }
        return result
    }

    private suspend fun hostStatusPermission(): JSONObject =
        try { VisualOperationResult.requireHost(hostCall("host.permission@1", "check", JSONObject().put("permission", "camera"))) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            JSONObject().put("available", false).put("permission_granted", false)
                .put("error", "相机权限状态不可用：${error.message}。请检查基座版本和插件授权。")
        }

    private suspend fun sources(kind: String): JSONObject =
        VisualOperationResult.requireHost(hostCall(sessionPrimitive(kind), if (kind == "screen") "list_targets" else "list_sources"))

    private suspend fun permission(p: JSONObject): JSONObject = perform("camera", "相机授权") {
        val operation = p.getString("operation")
        require(operation in setOf("check", "request", "open_settings")) { "无效权限操作" }
        val result = VisualOperationResult.requireHost(hostCall("host.permission@1", operation, JSONObject().put("permission", "camera")))
        if (operation == "open_settings") VisualOperationResult.requireFlag(result, "opened", "系统权限设置未打开")
        if (operation == "request") VisualOperationResult.requireFlag(result, "permission_granted",
            if (result.optString("state") == "BLOCKED") "相机权限未授予，请打开系统设置授权" else "相机授权未完成，可手动重试")
        result
    }

    private suspend fun start(kind: String, p: JSONObject): JSONObject = perform(kind, "开始会话") {
        val status = VisualOperationResult.requireHost(hostCall(sessionPrimitive(kind), "status"))
        check(status.getJSONArray("sessions").length() == 0) { "此来源已有会话，请先停止再切换" }
        if (kind == "camera") VisualOperationResult.requireFlag(hostStatusPermission(), "permission_granted", "请先授权相机")
        val request = JSONObject(p.toString()).apply {
            remove("kind")
            remove("source_id")
            if (kind == "screen") put("target_id", p.getString("source_id")) else put("source_id", p.getString("source_id"))
        }
        val opened = hostCall(sessionPrimitive(kind), "start", request)
        VisualOperationResult.requireFlag(opened, if (kind == "camera") "started" else "active", "视觉会话未启动")
        val id = opened.getString("session_id")
        try {
            val captured = captureSession(kind, id)
            val source = frameFile(kind, captured)
            try {
                val shown = updatePreview(kind, captured, it)
                attachImage(opened.put("preview", shown), shown)
            } finally { deleteHostScratch(source) }
        } catch (error: Throwable) {
            // A session that never delivered its first frame is not reported as ready.
            try { hostCall(sessionPrimitive(kind), "stop", JSONObject().put("session_id", id)) }
            catch (cleanup: Exception) { host.logger.e("VisualWorkbench", "Failed to release incomplete start", cleanup) }
            throw error
        }
    }

    private suspend fun captureSession(kind: String, id: String, save: Boolean = false): JSONObject {
        val request = JSONObject().put("session_id", id)
        if (kind == "screen") request.put("frame_format", if (save) "png" else "rgba8888")
        val result = hostCall(sessionPrimitive(kind), "frame", request)
        val frame = if (kind == "screen") result.getJSONObject("frame") else result
        VisualOperationResult.requireHost(frame)
        if (kind == "screen") result.put("mime_type", frame.getString("mime_type"))
        return result
    }

    private suspend fun frame(kind: String, p: JSONObject): JSONObject = perform(kind, "读取画面") {
        val edge = p.optInt("max_edge", 1024)
        require(edge in 160..2048) { "max_edge 必须在 160 到 2048 之间" }
        val captured = captureSession(kind, p.getString("session_id"), p.optBoolean("save", false))
        val source = frameFile(kind, captured)
        try {
            val image = updatePreview(kind, captured, it, edge = edge)
            if (p.optBoolean("save", false)) {
                val meta = frameMetadata(kind, captured, source)
                captured.put("managed_asset", locks.getValue("images").withLock { archive(source, meta) })
            }
            attachImage(captured.put("preview", image), image)
        } finally { deleteHostScratch(source) }
    }

    private suspend fun capture(kind: String, p: JSONObject): JSONObject = perform(kind, "拍摄并保存") {
        if (kind == "camera") {
            VisualOperationResult.requireFlag(hostStatusPermission(), "permission_granted", "请先授权相机")
            val status = hostCall(sessionPrimitive(kind), "status")
            check(status.getJSONArray("sessions").length() == 0) { "摄像头会话已开启，请使用读取画面并保存" }
        }
        if (kind == "screen") require(p.getString("source_id") == "display:0") { "当前屏幕取图只支持内置屏幕" }
        val request = JSONObject(p.toString()).apply { remove("kind") }
        val captured = hostCall("host.$kind.capture@1", if (kind == "screen") "capture_frame" else "capture", request)
        VisualOperationResult.requireHost(captured)
        if (kind == "camera" && captured.has("started")) {
            VisualOperationResult.requireFlag(captured, "started", "相机拍摄未完成")
        }
        val file = frameFile(kind, captured)
        val meta = frameMetadata(kind, captured, file)
        val saved = locks.getValue("images").withLock { archive(file, meta) }
        val image = updatePreview(kind, captured, it)
        deleteHostScratch(file)
        attachImage(captured.put("managed_asset", saved).put("preview", image), image)
    }

    /** Stop never queues behind a long frame request; generation invalidates its eventual result. */
    private suspend fun stop(p: JSONObject): JSONObject {
        val kind = p.optString("kind", "all")
        require(kind in setOf("screen", "camera", "all")) { "无效停止来源" }
        val channels = if (kind == "all") listOf("screen", "camera") else listOf(kind)
        val results = JSONObject()
        val errors = mutableListOf<String>()
        for (channel in channels) {
            synchronized(stateLock) { generations[channel] = generations.getValue(channel) + 1L }
            setState(channel, "STOPPING", "正在停止")
            try {
                val request = JSONObject()
                if (p.has("session_id")) {
                    require(kind != "all") { "全部停止不能指定单个会话" }
                    request.put("session_id", p.getString("session_id"))
                }
                val result = hostCall(sessionPrimitive(channel), "stop", request)
                VisualOperationResult.requireStopped(result)
                results.put(channel, result)
                setState(channel, "IDLE", "已停止")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                host.logger.e("VisualWorkbench", "Stop failed: $channel", error)
                errors += "$channel：${error.message}"
                results.put(channel, JSONObject().put("success", false).put("error", error.message))
                setState(channel, "ERROR", error.message ?: "停止失败")
            }
        }
        if (errors.isNotEmpty()) throw VisualOperationFailure("STOP_INCOMPLETE", errors.joinToString("；"), results)
        return results.put("stopped", true)
    }

    private fun frameFile(kind: String, result: JSONObject): File {
        val frame = if (kind == "screen" && result.has("frame")) result.getJSONObject("frame") else result
        val path = frame.getString(if (kind == "screen") "path" else "file_path")
        val file = File(path)
        if (kind == "screen" && frame.optString("format") == "rgba8888") {
            RawScreenFrame.read(frame, file)
            return file
        }
        check(file.length() <= 33_554_432L) { "单帧图像超过 32 MiB 上限" }
        check(file.isFile && file.length() > 0L) { "宿主未提供有效的图像文件" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "返回内容不是有效图像" }
        return file
    }

    private fun frameMetadata(kind: String, result: JSONObject, file: File): JSONObject {
        val frame = if (kind == "screen" && result.has("frame")) result.getJSONObject("frame") else result
        val raw = if (frame.optString("format") == "rgba8888") RawScreenFrame.read(frame, file) else null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (raw == null) BitmapFactory.decodeFile(file.absolutePath, options)
        return JSONObject().put("kind", kind).put("captured_at_ms", result.optLong("captured_at_ms", System.currentTimeMillis()))
            .put("source_id", result.optString(if (kind == "screen") "target_id" else "source_id", "display:0"))
            .put("session_id", result.optString("session_id"))
            .put("width", raw?.width ?: options.outWidth).put("height", raw?.height ?: options.outHeight)
            .put("mime_type", if (raw != null) "application/octet-stream" else options.outMimeType).put("file_name", file.name)
            .put("original_width", raw?.width ?: options.outWidth).put("original_height", raw?.height ?: options.outHeight)
            .apply {
                if (kind == "screen") {
                    put("frame_id", frame.getString("frame_id"))
                    put("geometry", JSONObject(frame.getJSONObject("geometry").toString()))
                    put("timings_ms", JSONObject(frame.getJSONObject("timings_ms").toString()))
                }
            }
    }

    private data class EncodedPreview(val image: JSONObject, val bytes: ByteArray, val sample: VisualSample?)

    private suspend fun updatePreview(kind: String, result: JSONObject, generation: Long?, maxBytes: Int = 1_048_576, edge: Int = 1024): JSONObject = previewLock.withLock {
        val startedElapsed = SystemClock.elapsedRealtime()
        val source = frameFile(kind, result)
        val meta = frameMetadata(kind, result, source)
        val frame = if (kind == "screen" && result.has("frame")) result.getJSONObject("frame") else result
        val raw = if (frame.optString("format") == "rgba8888") RawScreenFrame.read(frame, source) else null
        val encoded = encodeImage(source, meta, edge, maxBytes, raw)
        if (kind == "screen") {
            encoded.image.getJSONObject("timings_ms").put("preview_encode_ms", SystemClock.elapsedRealtime() - startedElapsed)
            encoded.image.put("image_to_touch", FrameCoordinates.mapping(encoded.image))
            meta.put("timings_ms", encoded.image.getJSONObject("timings_ms"))
                .put("image_to_touch", encoded.image.get("image_to_touch"))
        }
        val destination = File(previewRoot(), "$kind.jpg")
        synchronized(stateLock) {
            check(generation == generations[kind]) { "取帧已被停止指令取消" }
            // Write the one encoder output directly; do not decode our own Base64 to save it.
            destination.writeBytes(encoded.bytes)
            meta.put("file_name", destination.name).put("bytes", encoded.bytes.size).put("mime_type", "image/jpeg")
                .put("width", encoded.image.getInt("width")).put("height", encoded.image.getInt("height"))
            previews[kind] = meta
            if (kind == "screen") screenSample = encoded.sample
            revision++
            publishState()
        }
        encoded.image.put("file_name", destination.name).put("data", Base64.encodeToString(encoded.bytes, Base64.NO_WRAP))
    }

    private fun previewRead(kind: String, edge: Int): JSONObject {
        require(edge in 160..1280) { "max_edge 必须在 160 到 1280 之间" }
        val meta = previews[kind] ?: error("此来源尚无预览")
        val encoded = encodedImage(File(previewRoot(), meta.getString("file_name")), meta, edge)
        return attachImage(encoded, encoded)
    }

    private fun encodedImage(source: File, meta: JSONObject, edge: Int, maxBytes: Int = 1_048_576): JSONObject {
        val encoded = encodeImage(source, meta, edge, maxBytes, null)
        return encoded.image.put("data", Base64.encodeToString(encoded.bytes, Base64.NO_WRAP))
    }

    private fun encodeImage(source: File, meta: JSONObject, edge: Int, maxBytes: Int, raw: RawScreenFrame?): EncodedPreview {
        require(edge in 160..2048) { "max_edge 必须在 160 到 2048 之间" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val decoded = if (raw != null) {
            Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                try { bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(source.readBytes())) }
                catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        } else {
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000L) { "图像尺寸无效或超过 6400 万像素" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
            BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("图像解码失败")
        }
        var rotated: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val matrix = Matrix()
            val orientation = if (bounds.outMimeType == "image/jpeg") {
                ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } else ExifInterface.ORIENTATION_NORMAL
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            }
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            rotated = oriented
            val ratio = minOf(1f, edge.toFloat() / maxOf(oriented.width, oriented.height))
            val resized = Bitmap.createScaledBitmap(oriented, maxOf(1, (oriented.width * ratio).toInt()),
                maxOf(1, (oriented.height * ratio).toInt()), true)
            scaled = resized
            val stream = ByteArrayOutputStream()
            check(resized.compress(Bitmap.CompressFormat.JPEG, 82, stream)) { "预览编码失败" }
            val bytes = stream.toByteArray()
            check(bytes.size <= maxBytes) { "预览超过 $maxBytes 字节上限" }
            val image = JSONObject(meta.toString()).put("mime_type", "image/jpeg").put("width", resized.width)
                .put("height", resized.height)
            if (image.optString("kind") == "screen") image.put("image_to_touch", FrameCoordinates.mapping(image))
            val sample = if (meta.getString("kind") == "screen")
                VisualSample.sample(oriented.width, oriented.height) { x, y -> oriented.getPixel(x, y) } else null
            EncodedPreview(image, bytes, sample)
        } finally {
            // Rotation/resize allocation failures must also release the full raw-frame bitmap.
            scaled?.takeIf { it !== rotated && it !== decoded }?.recycle()
            rotated?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }

    private fun attachImage(result: JSONObject, image: JSONObject): JSONObject {
        val attachment = content(image)
        image.remove("data")
        return result.put("mcp_content", attachment)
    }

    private suspend fun tapOnFrame(p: JSONObject): JSONObject = perform("screen", "按画面点击") { generation ->
        require(p.optInt("max_edge", 1024) in 160..2048) { "max_edge 必须在 160 到 2048 之间" }
        val options = waitOptions(p, p.optString("observe_mode", "new_frame"))
        require(options.mode != "change") { "点击观察请使用 new_frame/stable/change_then_stable" }
        val (meta, sample) = screenBaseline(p)
        val status = VisualOperationResult.requireHost(hostCall(sessionPrimitive("screen"), "status",
            JSONObject().put("session_id", meta.getString("session_id"))))
        check(status.getBoolean("active") && status.getBoolean("projection_ready")) { "共享屏会话已停止" }
        val (x, y) = FrameCoordinates.normalized(meta, p.getDouble("x"), p.getDouble("y"))
        val geometry = meta.getJSONObject("geometry")
        val started = SystemClock.elapsedRealtime()
        val actionId = UUID.randomUUID().toString()
        val action = hostCall("host.ui.automation@1", "tap", JSONObject().put("x", x).put("y", y)
            .put("screen_feedback", false)
            .put("expected_display_width", geometry.getInt("touch_width"))
            .put("expected_display_height", geometry.getInt("touch_height"))
            .put("expected_display_rotation", geometry.getInt("rotation"))
            .put("expected_geometry_id", geometry.getString("geometry_id")))
        VisualOperationResult.requireFlag(action, "success", "触控未完成")
        val completed = SystemClock.elapsedRealtime()
        // Inject once. Waiting/observation can never re-enter the action path.
        val result = JSONObject().put("action_success", true).put("operation_id", actionId)
            .put("source_frame_id", meta.getString("frame_id")).put("touch_x", x).put("touch_y", y)
            .put("completed_elapsed_ms", completed).put("action_elapsed_ms", completed - started)
        try {
            val observed = observeScreen(meta, sample, options, generation, p.optInt("max_edge", 1024))
            observed.getJSONObject("preview").put("last_operation", JSONObject()
                .put("operation_id", actionId).put("source_frame_id", meta.getString("frame_id"))
                .put("touch_x", x).put("touch_y", y).put("completed_elapsed_ms", completed))
            for (key in observed.keys()) result.put(key, observed.get(key))
            result
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            host.logger.e("VisualWorkbench", "Tap completed but observation failed", error)
            result.put("observation_success", false).put("observation_error", error.message ?: error.javaClass.simpleName)
                .put("automatic_reexecution", false)
        }
    }

    private fun waitOptions(p: JSONObject, mode: String) = VisualWaitOptions(mode,
        p.optLong("timeout_ms", 5000), p.optLong("stable_ms", 250), p.optLong("sample_interval_ms", 100),
        p.optDouble("change_ratio", 0.02), p.optDouble("stable_ratio", 0.0), p.optInt("pixel_tolerance", 12),
        VisualRegion(p.optDouble("region_left", 0.0), p.optDouble("region_top", 0.0),
            p.optDouble("region_width", 1.0), p.optDouble("region_height", 1.0)))

    private suspend fun screenBaseline(p: JSONObject): Pair<JSONObject, VisualSample> = previewLock.withLock {
        val meta = JSONObject(checkNotNull(previews["screen"]) { "请先获取屏幕帧" }.toString())
        check(p.getString("frame_id") == meta.getString("frame_id")) { "STALE_FRAME: 请使用最新屏幕帧" }
        meta to checkNotNull(screenSample) { "屏幕帧没有原始像素采样" }
    }

    private suspend fun waitForScreen(p: JSONObject, mode: String): JSONObject = perform("screen", "等待画面") { generation ->
        val edge = p.optInt("max_edge", 1024)
        require(edge in 160..2048)
        val options = waitOptions(p, mode)
        val (meta, sample) = screenBaseline(p)
        observeScreen(meta, sample, options, generation, edge)
    }

    private suspend fun observeScreen(meta: JSONObject, baseline: VisualSample, options: VisualWaitOptions,
        generation: Long?, edge: Int): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + options.timeoutMs
        val detector = VisualChangeDetector(baseline, options)
        val geometryId = meta.getJSONObject("geometry").getString("geometry_id")
        var captured: JSONObject? = null
        var scratch: File? = null
        var met = false
        var polls = 0
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                synchronized(stateLock) { check(generation == generations["screen"]) { "取帧已被停止指令取消" } }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                // The first image is a post-request surface. Subsequent reads reuse the latest
                // producer image. Cached duplicates cannot advance the stable-time detector.
                val response = VisualOperationResult.requireHost(hostCall(sessionPrimitive("screen"), "frame", JSONObject()
                    .put("session_id", meta.getString("session_id")).put("frame_format", "rgba8888")
                    .put("frame_mode", if (polls == 0) "new_surface" else "latest")
                    .put("max_age_ms", 60000).put("timeout_ms", remaining.coerceAtMost(15000))))
                val rawMeta = VisualOperationResult.requireHost(response.getJSONObject("frame"))
                val next = File(rawMeta.getString("path"))
                val previousScratch = scratch
                scratch = next
                previousScratch?.let { deleteHostScratch(it) }
                check(rawMeta.getJSONObject("geometry").getString("geometry_id") == geometryId) {
                    "SCREEN_GEOMETRY_CHANGED: 等待期间屏幕方向或尺寸改变"
                }
                val raw = RawScreenFrame.read(rawMeta, next)
                val sample = VisualSample.raw(raw, next)
                captured = response
                polls++
                met = detector.accept(rawMeta.getString("frame_id"),
                    response.getJSONObject("freshness").getLong("captured_elapsed_ms"), sample)
                // A frame delivered after the deadline cannot retroactively satisfy the condition.
                if (SystemClock.elapsedRealtime() >= deadline) { met = false; break }
                if (met) break
                delay(minOf(options.intervalMs, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)))
            }
            val last = checkNotNull(captured) { "VISUAL_WAIT_TIMEOUT: 等待期限内没有画面" }
            val image = updatePreview("screen", last, generation, edge = edge)
            val wait = JSONObject().put("mode", options.mode).put("condition_met", met)
                .put("status", if (met) "READY" else "TIMEOUT").put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("samples", detector.samples).put("polls", polls).put("changed", detector.changed)
                .put("baseline_change_ratio", detector.baselineRatio).put("adjacent_change_ratio", detector.adjacentRatio)
                .put("stable_elapsed_ms", detector.quietMs).put("stable_ms", options.stableMs)
                .put("change_ratio", options.changeRatio).put("stable_ratio", options.stableRatio)
                .put("pixel_tolerance", options.pixelTolerance).put("sample_grid", VisualSample.EDGE)
                .put("region", JSONObject().put("left", options.region.left).put("top", options.region.top)
                    .put("width", options.region.width).put("height", options.region.height))
            image.put("visual_wait", JSONObject(wait.toString()))
            return attachImage(JSONObject().put("observation_success", true).put("wait_success", met)
                .put("visual_wait", wait).put("frame", last).put("preview", image)
                .put("automatic_reexecution", false), image)
        } finally { scratch?.let { deleteHostScratch(it) } }
    }

    private fun content(image: JSONObject) = JSONArray().put(JSONObject()
        .put("type", "image").put("mimeType", image.getString("mime_type")).put("data", image.getString("data")))

    private fun archive(source: File, info: JSONObject): JSONObject {
        val id = "${info.getString("kind")}-${info.getLong("captured_at_ms")}-${UUID.randomUUID().toString().take(8)}"
        val extension = source.extension.lowercase()
        require(extension in setOf("png", "jpg", "jpeg", "webp")) { "不支持的图像记录格式" }
        val target = File(imagesRoot(), "$id.$extension")
        source.copyTo(target)
        val meta = JSONObject(info.toString()).put("asset_id", id).put("file_name", target.name).put("bytes", target.length())
        File(imagesRoot(), "$id.json").writeText(meta.toString(), Charsets.UTF_8)
        trimImages()
        return JSONObject(meta.toString()).put("file_path", target.absolutePath)
    }

    private fun imageMetadata(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "无效图像 ID" }
        return JSONObject(File(imagesRoot(), "$id.json").readText(Charsets.UTF_8))
    }

    private fun imageFile(meta: JSONObject): File {
        val root = imagesRoot().canonicalFile
        val file = File(root, meta.getString("file_name")).canonicalFile
        require(file.parentFile == root && file.isFile) { "图像记录文件无效" }
        return file
    }

    private fun listImages(): JSONObject {
        val items = imagesRoot().listFiles().orEmpty().filter { it.extension == "json" }
            .map { JSONObject(it.readText(Charsets.UTF_8)) }
            .sortedByDescending { it.getLong("captured_at_ms") }
        val array = JSONArray()
        var bytes = 0L
        items.forEach { meta ->
            val file = imageFile(meta)
            bytes += file.length()
            array.put(JSONObject(meta.toString()).put("bytes", file.length()))
        }
        return JSONObject().put("count", items.size).put("bytes", bytes).put("items", array)
            .put("max_count", 60).put("max_bytes", 67_108_864L)
    }

    private fun trimImages() {
        val items = listImages().getJSONArray("items")
        var count = items.length()
        var bytes = (0 until count).sumOf { items.getJSONObject(it).getLong("bytes") }
        for (index in items.length() - 1 downTo 0) {
            if (count <= 60 && bytes <= 67_108_864L) break
            val item = items.getJSONObject(index)
            check(imageFile(item).delete()) { "超额图像记录清理失败" }
            check(File(imagesRoot(), "${item.getString("asset_id")}.json").delete()) { "图像记录元数据清理失败" }
            bytes -= item.getLong("bytes")
            count--
        }
    }

    private fun imagesRoot() = File(host.cacheDir, "visual-assets").apply { check(exists() || mkdirs()) }
    private fun previewRoot() = File(host.cacheDir, "visual-preview").apply { check(exists() || mkdirs()) }
    private fun deleteHostScratch(file: File) {
        val root = File(host.applicationContext.cacheDir, "visual-host/${host.pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)}").canonicalFile
        val candidate = file.canonicalFile
        if (candidate.path.startsWith(root.path + File.separator)) check(candidate.delete()) { "临时帧清理失败" }
    }

    suspend fun dispose() {
        val result = call("stop", JSONObject().put("kind", "all"))
        if (!result.getBoolean("success")) host.logger.e("VisualWorkbench", result.getString("error"))
        pageReader.clear()
        previewLock.withLock {
            previews.clear()
            screenSample = null
            check(previewRoot().deleteRecursively()) { "临时预览清理失败" }
        }
    }
}
