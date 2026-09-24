package com.ai.assistance.operit.core.tools.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

object VisualHostRuntime {
    private data class ScreenSession(
        val id: String,
        val ownerPluginId: String,
        val targetId: String,
        val startedAtMs: Long
    )

    private data class CameraSession(
        val id: String,
        val ownerPluginId: String,
        val sourceId: String,
        val width: Int,
        val height: Int,
        val startedAtMs: Long,
        val thread: HandlerThread,
        val handler: Handler,
        val reader: ImageReader,
        val device: CameraDevice,
        val captureSession: CameraCaptureSession,
        val lock: Mutex = Mutex(),
        @Volatile var jpegQuality: Int = 92,
        @Volatile var jpegOrientation: Int = 0
    )

    private val screenSessions = ConcurrentHashMap<String, ScreenSession>()
    private val cameraSessions = ConcurrentHashMap<String, CameraSession>()

    suspend fun invokeScreen(
        context: Context,
        ownerPluginId: String,
        operation: String,
        parameters: JSONObject,
        captureFrame: suspend () -> JSONObject
    ): JSONObject = when (operation) {
        "list_targets" -> listScreenTargets(context)
        "start" -> startScreenSession(context, ownerPluginId, parameters)
        "status" -> screenStatus(ownerPluginId, parameters)
        "frame" -> screenFrame(ownerPluginId, parameters, captureFrame)
        "stop" -> stopScreenSession(context, ownerPluginId, parameters)
        else -> error("Unsupported screen visual operation: $operation")
    }

    suspend fun invokeCamera(
        context: Context,
        ownerPluginId: String,
        primitiveId: String,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        if (primitiveId == "host.camera.capture@1") {
            check(operation == "capture") { "Unsupported camera capture operation: $operation" }
            return captureCameraOnce(context, ownerPluginId, parameters)
        }
        return when (operation) {
            "list_sources" -> listCameraSources(context)
            "start" -> startCameraSession(context, ownerPluginId, parameters)
            "status" -> cameraStatus(ownerPluginId, parameters)
            "frame" -> captureCameraFrame(context, requireOwnedCameraSession(ownerPluginId, parameters))
            "configure" -> configureCameraSession(ownerPluginId, parameters)
            "stop" -> stopCameraSession(ownerPluginId, parameters)
            else -> error("Unsupported camera visual operation: $operation")
        }
    }

    private fun listScreenTargets(context: Context): JSONObject {
        val displays = context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        return JSONObject()
            .put("available", displays.isNotEmpty())
            .put("targets", JSONArray().apply {
                displays.forEach { display ->
                    put(
                        JSONObject()
                            .put("target_id", "display:${display.displayId}")
                            .put("display_id", display.displayId)
                            .put("name", display.name)
                            .put("state", display.state)
                    )
                }
            })
    }

    private fun startScreenSession(
        context: Context,
        ownerPluginId: String,
        parameters: JSONObject
    ): JSONObject {
        val requested = parameters.optString("target_id").trim().ifBlank { "display:0" }
        val validTargets =
            context.getSystemService(DisplayManager::class.java)
                ?.displays
                .orEmpty()
                .mapTo(linkedSetOf()) { "display:${it.displayId}" }
        require(requested in validTargets) { "Unknown screen target: $requested" }
        val session =
            ScreenSession(
                id = UUID.randomUUID().toString(),
                ownerPluginId = ownerPluginId,
                targetId = requested,
                startedAtMs = System.currentTimeMillis()
            )
        screenSessions[session.id] = session
        return screenSessionJson(session)
            .put("active", true)
            .put("projection_ready", MediaProjectionHolder.mediaProjection != null)
            .put(
                "authorization_state",
                if (MediaProjectionHolder.mediaProjection != null) "ready" else "on_demand"
            )
    }

    private fun screenStatus(ownerPluginId: String, parameters: JSONObject): JSONObject {
        val requested = parameters.optString("session_id").trim()
        if (requested.isNotEmpty()) {
            val session = requireOwnedScreenSession(ownerPluginId, requested)
            return screenSessionJson(session)
                .put("active", true)
                .put("projection_ready", MediaProjectionHolder.mediaProjection != null)
        }
        val owned = screenSessions.values.filter { it.ownerPluginId == ownerPluginId }
        return JSONObject()
            .put("active", owned.isNotEmpty())
            .put("projection_ready", MediaProjectionHolder.mediaProjection != null)
            .put("sessions", JSONArray().apply {
                owned.sortedBy { it.startedAtMs }.forEach { put(screenSessionJson(it)) }
            })
    }

    private suspend fun screenFrame(
        ownerPluginId: String,
        parameters: JSONObject,
        captureFrame: suspend () -> JSONObject
    ): JSONObject {
        val session = requireOwnedScreenSession(ownerPluginId, required(parameters, "session_id"))
        val capturedAt = System.currentTimeMillis()
        val hostResult = captureFrame()
        return JSONObject()
            .put("session_id", session.id)
            .put("target_id", session.targetId)
            .put("captured_at_ms", capturedAt)
            .put("frame", JSONObject(hostResult.toString()))
    }

    private fun stopScreenSession(
        context: Context,
        ownerPluginId: String,
        parameters: JSONObject
    ): JSONObject {
        val requested = parameters.optString("session_id").trim()
        val stopped =
            if (requested.isNotEmpty()) {
                val session = requireOwnedScreenSession(ownerPluginId, requested)
                screenSessions.remove(session.id, session)
                listOf(session.id)
            } else {
                screenSessions.values
                    .filter { it.ownerPluginId == ownerPluginId }
                    .mapNotNull { session ->
                        if (screenSessions.remove(session.id, session)) session.id else null
                    }
            }
        if (screenSessions.isEmpty()) {
            MediaProjectionHolder.clear(context.applicationContext)
        }
        return JSONObject()
            .put("stopped", JSONArray(stopped))
            .put("active", screenSessions.values.any { it.ownerPluginId == ownerPluginId })
    }

    private fun screenSessionJson(session: ScreenSession): JSONObject =
        JSONObject()
            .put("session_id", session.id)
            .put("target_id", session.targetId)
            .put("started_at_ms", session.startedAtMs)

    private fun requireOwnedScreenSession(ownerPluginId: String, sessionId: String): ScreenSession {
        val session = screenSessions[sessionId] ?: error("Screen visual session not found: $sessionId")
        check(session.ownerPluginId == ownerPluginId) { "Screen visual session owner mismatch" }
        return session
    }

    private fun listCameraSources(context: Context): JSONObject {
        val permissionGranted = cameraPermissionGranted(context)
        val manager = context.getSystemService(CameraManager::class.java)
        val sources =
            manager.cameraIdList.map { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val sizes =
                    characteristics
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.JPEG)
                        .orEmpty()
                        .sortedByDescending { it.width.toLong() * it.height.toLong() }
                        .take(12)
                JSONObject()
                    .put("source_id", id)
                    .put(
                        "lens_facing",
                        lensFacingName(characteristics.get(CameraCharacteristics.LENS_FACING))
                    )
                    .put(
                        "sensor_orientation",
                        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    )
                    .put("jpeg_sizes", JSONArray().apply {
                        sizes.forEach { size ->
                            put(JSONObject().put("width", size.width).put("height", size.height))
                        }
                    })
            }
        return JSONObject()
            .put("available", sources.isNotEmpty())
            .put("permission_granted", permissionGranted)
            .put(
                "permission_required",
                if (permissionGranted) JSONObject.NULL else Manifest.permission.CAMERA
            )
            .put("sources", JSONArray(sources))
    }

    private suspend fun captureCameraOnce(
        context: Context,
        ownerPluginId: String,
        parameters: JSONObject
    ): JSONObject {
        val start = startCameraSession(context, ownerPluginId, parameters)
        if (!start.optBoolean("started", false)) return start
        val sessionId = start.getString("session_id")
        return try {
            captureCameraFrame(
                context,
                requireOwnedCameraSession(
                    ownerPluginId,
                    JSONObject().put("session_id", sessionId)
                )
            ).put("transient_session", true)
        } finally {
            stopCameraSession(ownerPluginId, JSONObject().put("session_id", sessionId))
        }
    }

    private suspend fun startCameraSession(
        context: Context,
        ownerPluginId: String,
        parameters: JSONObject
    ): JSONObject {
        if (!cameraPermissionGranted(context)) {
            return JSONObject()
                .put("started", false)
                .put("available", true)
                .put("permission_granted", false)
                .put("permission_required", Manifest.permission.CAMERA)
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val sourceId = resolveCameraSource(manager, parameters)
        val characteristics = manager.getCameraCharacteristics(sourceId)
        val size = chooseJpegSize(characteristics, parameters)
        val thread = HandlerThread("AiLimbsVisualCamera-${UUID.randomUUID()}").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val device =
            try {
                openCamera(manager, sourceId, handler)
            } catch (error: Throwable) {
                reader.close()
                thread.quitSafely()
                throw error
            }
        val captureSession =
            try {
                createCaptureSession(device, reader, handler)
            } catch (error: Throwable) {
                device.close()
                reader.close()
                thread.quitSafely()
                throw error
            }
        val session =
            CameraSession(
                id = UUID.randomUUID().toString(),
                ownerPluginId = ownerPluginId,
                sourceId = sourceId,
                width = size.width,
                height = size.height,
                startedAtMs = System.currentTimeMillis(),
                thread = thread,
                handler = handler,
                reader = reader,
                device = device,
                captureSession = captureSession,
                jpegQuality = parameters.optInt("jpeg_quality", 92).coerceIn(1, 100),
                jpegOrientation = normalizeOrientation(parameters.optInt("jpeg_orientation", 0))
            )
        cameraSessions[session.id] = session
        return cameraSessionJson(session)
            .put("started", true)
            .put("active", true)
            .put("permission_granted", true)
    }

    private fun cameraStatus(ownerPluginId: String, parameters: JSONObject): JSONObject {
        val requested = parameters.optString("session_id").trim()
        if (requested.isNotEmpty()) {
            return cameraSessionJson(requireOwnedCameraSession(ownerPluginId, parameters))
                .put("active", true)
        }
        val owned = cameraSessions.values.filter { it.ownerPluginId == ownerPluginId }
        return JSONObject()
            .put("active", owned.isNotEmpty())
            .put("sessions", JSONArray().apply {
                owned.sortedBy { it.startedAtMs }.forEach { put(cameraSessionJson(it)) }
            })
    }

    private fun configureCameraSession(
        ownerPluginId: String,
        parameters: JSONObject
    ): JSONObject {
        val session = requireOwnedCameraSession(ownerPluginId, parameters)
        if (parameters.has("jpeg_quality")) {
            session.jpegQuality = parameters.getInt("jpeg_quality").coerceIn(1, 100)
        }
        if (parameters.has("jpeg_orientation")) {
            session.jpegOrientation =
                normalizeOrientation(parameters.getInt("jpeg_orientation"))
        }
        val requestedWidth = parameters.optInt("width", session.width)
        val requestedHeight = parameters.optInt("height", session.height)
        val restartRequired =
            requestedWidth != session.width || requestedHeight != session.height
        return cameraSessionJson(session)
            .put("configured", true)
            .put("restart_required", restartRequired)
            .put("requested_width", requestedWidth)
            .put("requested_height", requestedHeight)
    }

    private suspend fun captureCameraFrame(
        context: Context,
        session: CameraSession
    ): JSONObject =
        session.lock.withLock {
            val deferred = CompletableDeferred<ByteArray>()
            session.reader.setOnImageAvailableListener(
                { reader ->
                    try {
                        val image =
                            reader.acquireLatestImage()
                                ?: return@setOnImageAvailableListener
                        image.use {
                            val buffer = it.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            if (!deferred.isCompleted) deferred.complete(bytes)
                        }
                    } catch (error: Throwable) {
                        if (!deferred.isCompleted) {
                            deferred.completeExceptionally(error)
                        }
                    }
                },
                session.handler
            )
            try {
                val request =
                    session.device
                        .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        .apply {
                            addTarget(session.reader.surface)
                            set(
                                CaptureRequest.JPEG_QUALITY,
                                session.jpegQuality.toByte()
                            )
                            set(
                                CaptureRequest.JPEG_ORIENTATION,
                                session.jpegOrientation
                            )
                        }
                        .build()
                session.captureSession.capture(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {},
                    session.handler
                )
                val bytes =
                    withTimeout(CAMERA_FRAME_TIMEOUT_MS) { deferred.await() }
                val capturedAt = System.currentTimeMillis()
                val dir =
                    File(
                        context.cacheDir,
                        "visual-host/${safePathPart(session.ownerPluginId)}/${session.id}"
                    ).apply { mkdirs() }
                val file = File(dir, "frame-$capturedAt.jpg")
                file.writeBytes(bytes)
                JSONObject()
                    .put("session_id", session.id)
                    .put("source_id", session.sourceId)
                    .put("captured_at_ms", capturedAt)
                    .put("width", session.width)
                    .put("height", session.height)
                    .put("mime_type", "image/jpeg")
                    .put("file_path", file.absolutePath)
                    .put("bytes", bytes.size)
            } finally {
                session.reader.setOnImageAvailableListener(null, null)
            }
        }

    private fun stopCameraSession(
        ownerPluginId: String,
        parameters: JSONObject
    ): JSONObject {
        val requested = parameters.optString("session_id").trim()
        val sessions =
            if (requested.isNotEmpty()) {
                listOf(requireOwnedCameraSession(ownerPluginId, parameters))
            } else {
                cameraSessions.values.filter { it.ownerPluginId == ownerPluginId }
            }
        val stopped = JSONArray()
        sessions.forEach { session ->
            if (cameraSessions.remove(session.id, session)) {
                closeCameraSession(session)
                stopped.put(session.id)
            }
        }
        return JSONObject()
            .put("stopped", stopped)
            .put(
                "active",
                cameraSessions.values.any { it.ownerPluginId == ownerPluginId }
            )
    }

    private fun closeCameraSession(session: CameraSession) {
        runCatching { session.captureSession.close() }
        runCatching { session.device.close() }
        runCatching { session.reader.close() }
        runCatching { session.thread.quitSafely() }
    }

    private fun cameraSessionJson(session: CameraSession): JSONObject =
        JSONObject()
            .put("session_id", session.id)
            .put("source_id", session.sourceId)
            .put("width", session.width)
            .put("height", session.height)
            .put("jpeg_quality", session.jpegQuality)
            .put("jpeg_orientation", session.jpegOrientation)
            .put("started_at_ms", session.startedAtMs)

    private fun requireOwnedCameraSession(
        ownerPluginId: String,
        parameters: JSONObject
    ): CameraSession {
        val sessionId = required(parameters, "session_id")
        val session =
            cameraSessions[sessionId]
                ?: error("Camera visual session not found: $sessionId")
        check(session.ownerPluginId == ownerPluginId) {
            "Camera visual session owner mismatch"
        }
        return session
    }

    private fun cameraPermissionGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun resolveCameraSource(
        manager: CameraManager,
        parameters: JSONObject
    ): String {
        val requested =
            parameters.optString("source_id").trim().ifBlank {
                parameters.optString("camera_id").trim()
            }
        if (requested.isNotEmpty()) {
            require(requested in manager.cameraIdList) {
                "Unknown camera source: $requested"
            }
            return requested
        }
        val desiredFacing =
            when (
                parameters
                    .optString("lens_facing", "back")
                    .trim()
                    .lowercase()
            ) {
                "front" -> CameraCharacteristics.LENS_FACING_FRONT
                "external" -> CameraCharacteristics.LENS_FACING_EXTERNAL
                else -> CameraCharacteristics.LENS_FACING_BACK
            }
        return manager.cameraIdList.firstOrNull { id ->
            manager
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == desiredFacing
        } ?: manager.cameraIdList.firstOrNull()
            ?: error("No camera source is available")
    }

    private fun chooseJpegSize(
        characteristics: CameraCharacteristics,
        parameters: JSONObject
    ): Size {
        val sizes =
            characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                .orEmpty()
        require(sizes.isNotEmpty()) { "Camera has no JPEG output size" }
        val requestedWidth =
            parameters.optInt("width", 1280).coerceAtLeast(1)
        val requestedHeight =
            parameters.optInt("height", 720).coerceAtLeast(1)
        return sizes.minByOrNull { size ->
            abs(
                size.width.toLong() * size.height.toLong() -
                    requestedWidth.toLong() * requestedHeight.toLong()
            )
        } ?: sizes.first()
    }

    @Suppress("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        sourceId: String,
        handler: Handler
    ): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            manager.openCamera(
                sourceId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) {
                            continuation.resume(camera)
                        } else {
                            camera.close()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "Camera disconnected: $sourceId"
                                )
                            )
                        }
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int
                    ) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "Camera open failed: $sourceId error=$error"
                                )
                            )
                        }
                    }
                },
                handler
            )
        }

    @Suppress("DEPRECATION")
    private suspend fun createCaptureSession(
        device: CameraDevice,
        reader: ImageReader,
        handler: Handler
    ): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(
                        session: CameraCaptureSession
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(session)
                        } else {
                            session.close()
                        }
                    }

                    override fun onConfigureFailed(
                        session: CameraCaptureSession
                    ) {
                        session.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "Camera capture session configuration failed"
                                )
                            )
                        }
                    }
                },
                handler
            )
        }

    private fun lensFacingName(value: Int?): String =
        when (value) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }

    private fun normalizeOrientation(value: Int): Int =
        ((value % 360) + 360) % 360

    private fun required(
        parameters: JSONObject,
        key: String
    ): String =
        parameters
            .optString(key)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: error("$key is required")

    private fun safePathPart(value: String): String =
        value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)

    private const val CAMERA_FRAME_TIMEOUT_MS = 8_000L
}
