package com.ai.limbs.plugins.visualmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
                    image.put("mcp_content", content(image))
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
                .put("session_id", sessions.getJSONObject(0).getString("session_id")).put("fresh", true)
                .put("deadline_elapsed_ms", request.getLong("deadline_elapsed_ms")))
            VisualOperationResult.requireHost(captured)
            val hostFrame = captured.getJSONObject("frame")
            VisualOperationResult.requireHost(hostFrame)
            scratch = File(hostFrame.getString("path"))
            ScreenFeedbackContract.requireFresh(request, captured)
            val image = updatePreview("screen", captured, generation, 524_288)
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
            val shown = updatePreview(kind, captured, it)
            deleteHostScratch(frameFile(kind, captured))
            opened.put("preview", shown).put("mcp_content", content(shown))
        } catch (error: Throwable) {
            // A session that never delivered its first frame is not reported as ready.
            try { hostCall(sessionPrimitive(kind), "stop", JSONObject().put("session_id", id)) }
            catch (cleanup: Exception) { host.logger.e("VisualWorkbench", "Failed to release incomplete start", cleanup) }
            throw error
        }
    }

    private suspend fun captureSession(kind: String, id: String): JSONObject {
        val result = hostCall(sessionPrimitive(kind), "frame", JSONObject().put("session_id", id))
        val frame = if (kind == "screen") result.getJSONObject("frame") else result
        VisualOperationResult.requireHost(frame)
        if (kind == "screen") result.put("mime_type", "image/png")
        return result
    }

    private suspend fun frame(kind: String, p: JSONObject): JSONObject = perform(kind, "读取画面") {
        val captured = captureSession(kind, p.getString("session_id"))
        val image = updatePreview(kind, captured, it)
        if (p.optBoolean("save", false)) {
            val meta = frameMetadata(kind, captured, frameFile(kind, captured))
            captured.put("managed_asset", locks.getValue("images").withLock { archive(frameFile(kind, captured), meta) })
        }
        deleteHostScratch(frameFile(kind, captured))
        captured.put("preview", image).put("mcp_content", content(image))
    }

    private suspend fun capture(kind: String, p: JSONObject): JSONObject = perform(kind, "拍摄并保存") {
        if (kind == "camera") {
            VisualOperationResult.requireFlag(hostStatusPermission(), "permission_granted", "请先授权相机")
            val status = hostCall(sessionPrimitive(kind), "status")
            check(status.getJSONArray("sessions").length() == 0) { "摄像头会话已开启，请使用读取画面并保存" }
        }
        if (kind == "screen") require(p.getString("source_id") == "display:0") { "当前屏幕取图只支持内置屏幕" }
        val request = JSONObject(p.toString()).apply { remove("kind") }
        val captured = hostCall("host.$kind.capture@1", "capture", request)
        VisualOperationResult.requireHost(captured)
        if (kind == "camera" && captured.has("started")) {
            VisualOperationResult.requireFlag(captured, "started", "相机拍摄未完成")
        }
        val file = frameFile(kind, captured)
        val meta = frameMetadata(kind, captured, file)
        val saved = locks.getValue("images").withLock { archive(file, meta) }
        val image = updatePreview(kind, captured, it)
        deleteHostScratch(file)
        captured.put("managed_asset", saved).put("preview", image).put("mcp_content", content(image))
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
        check(file.length() <= 33_554_432L) { "单帧图像超过 32 MiB 上限" }
        check(file.isFile && file.length() > 0L) { "宿主未提供有效的图像文件" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "返回内容不是有效图像" }
        return file
    }

    private fun frameMetadata(kind: String, result: JSONObject, file: File): JSONObject {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return JSONObject().put("kind", kind).put("captured_at_ms", result.optLong("captured_at_ms", System.currentTimeMillis()))
            .put("source_id", result.optString(if (kind == "screen") "target_id" else "source_id", "display:0"))
            .put("session_id", result.optString("session_id"))
            .put("width", options.outWidth).put("height", options.outHeight)
            .put("mime_type", options.outMimeType).put("file_name", file.name)
    }

    private suspend fun updatePreview(kind: String, result: JSONObject, generation: Long?, maxBytes: Int = 1_048_576): JSONObject = previewLock.withLock {
        val source = frameFile(kind, result)
        val meta = frameMetadata(kind, result, source)
        val encoded = encodedImage(source, meta, 1024, maxBytes)
        val destination = File(previewRoot(), "$kind.jpg")
        synchronized(stateLock) {
            check(generation == generations[kind]) { "取帧已被停止指令取消" }
            destination.writeBytes(Base64.decode(encoded.getString("data"), Base64.NO_WRAP))
            meta.put("file_name", destination.name).put("bytes", destination.length()).put("mime_type", "image/jpeg")
                .put("width", encoded.getInt("width")).put("height", encoded.getInt("height"))
            previews[kind] = meta
            revision++
            publishState()
        }
        encoded
    }

    private fun previewRead(kind: String, edge: Int): JSONObject {
        val meta = previews[kind] ?: error("此来源尚无预览")
        val encoded = encodedImage(File(previewRoot(), meta.getString("file_name")), meta, edge)
        return encoded.put("mcp_content", content(encoded))
    }

    private fun encodedImage(source: File, meta: JSONObject, edge: Int, maxBytes: Int = 1_048_576): JSONObject {
        require(edge in 160..1280) { "max_edge 必须在 160 到 1280 之间" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000L) { "图像尺寸无效或超过 6400 万像素" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图像解码失败")
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
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val ratio = minOf(1f, edge.toFloat() / maxOf(rotated.width, rotated.height))
        val scaled = Bitmap.createScaledBitmap(rotated, maxOf(1, (rotated.width * ratio).toInt()),
            maxOf(1, (rotated.height * ratio).toInt()), true)
        return try {
            val stream = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)) { "预览编码失败" }
            val bytes = stream.toByteArray()
            check(bytes.size <= maxBytes) { "预览超过 $maxBytes 字节上限" }
            JSONObject(meta.toString()).put("mime_type", "image/jpeg").put("width", scaled.width)
                .put("height", scaled.height).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } finally {
            if (scaled !== rotated) scaled.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
        }
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
            check(previewRoot().deleteRecursively()) { "临时预览清理失败" }
        }
    }
}
