package com.ai.assistance.operit.core.tools.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import android.os.Build
import android.view.Display
import java.util.UUID
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream

/**
 * Dedicated manager for capturing screenshots using Android's MediaProjection API.
 * This is used for Standard permission level users who don't have ADB access.
 */
class MediaProjectionCaptureManager(private val context: Context, private val mediaProjection: MediaProjection) {

    companion object {
        private const val TAG = "MediaProjectionCapture"
        @Volatile private var activeManager: MediaProjectionCaptureManager? = null

        @Synchronized fun forProjection(context: Context, projection: MediaProjection): MediaProjectionCaptureManager {
            val current = activeManager
            if (current != null && current.mediaProjection === projection) return current
            current?.release()
            val manager = MediaProjectionCaptureManager(context.applicationContext, projection)
            manager.setupDisplay()
            check(manager.virtualDisplay != null) { "Shared-screen capture initialization failed" }
            return manager
        }

        fun requireCurrentGeometry(expected: String) {
            val manager = checkNotNull(activeManager) { "Shared-screen geometry is unavailable" }
            synchronized(manager) {
                check(manager.virtualDisplay != null && expected == manager.geometry(manager.captureWidth, manager.captureHeight)
                    .getString("geometry_id")) { "SCREEN_GEOMETRY_CHANGED: obtain a new frame" }
            }
        }
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val freshCaptureLock = Mutex()
    private var pendingFreshFrame: CompletableDeferred<Unit>? = null

    private var densityDpi = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var geometryRevision = 0L
    private var frameSequence = 0L
    private val captureId = UUID.randomUUID().toString()
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastRotation = -1
    private var capturedVisible: Boolean? = null
    private var streamError: Exception? = null
    private data class BufferedFrame(val image: Image, val sequence: Long, val acquiredElapsed: Long,
        val acquiredAt: Long, val geometry: JSONObject)
    private var bufferedFrame: BufferedFrame? = null
    private val frameSignal = MutableStateFlow(0L)

    data class FreshFrame(
        val path: String, val width: Int, val height: Int,
        val requestedElapsedMs: Long, val capturedElapsedMs: Long, val capturedAtMs: Long,
        val format: String = "png",
        val frameId: String,
        val geometry: JSONObject,
        val timings: JSONObject,
        val method: String = "new_surface"
    )

    private val callbackHandler = Handler(Looper.getMainLooper())
    private var projectionCallback: MediaProjection.Callback? = null
    
    /**
     * Set up the virtual display using the MediaProjection token.
     */
    @Synchronized fun setupDisplay() {
        if (virtualDisplay != null) return
        
        try {
            ensureProjectionCallbackRegistered()

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            densityDpi = metrics.densityDpi
            captureWidth = width
            captureHeight = height
            geometryRevision++
            @Suppress("DEPRECATION")
            lastRotation = windowManager.defaultDisplay.rotation

            // Using RGBA_8888
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            installReaderListener(reader)

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "OperitScreenCapture",
                    width,
                    height,
                    densityDpi,
                    flags,
                    reader.surface,
                    null,
                    null
            )
            
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    // API 34+ supplies the actual captured window size, including app sharing.
                    // Older systems share the full display and use its logical dimensions.
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        synchronized(this@MediaProjectionCaptureManager) {
                            @Suppress("DEPRECATION")
                            val rotation = windowManager.defaultDisplay.rotation
                            if (rotation != lastRotation) {
                                lastRotation = rotation
                                geometryRevision++
                                invalidateBufferedFrame("SCREEN_GEOMETRY_CHANGED")
                            }
                        }
                        if (Build.VERSION.SDK_INT < 34) {
                            val current = DisplayMetrics()
                            @Suppress("DEPRECATION")
                            windowManager.defaultDisplay.getRealMetrics(current)
                            resizeCapture(current.widthPixels, current.heightPixels)
                        }
                    }
                }
            }
            activeManager = this
            displayListener = listener
            context.getSystemService(DisplayManager::class.java).registerDisplayListener(listener, callbackHandler)
            AppLogger.d(TAG, "Created MediaProjection virtual display: ${width}x${height}")
        } catch (e: Exception) {
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = null
            release()
            AppLogger.e(TAG, "Failed to create MediaProjection virtual display", e)
        }
    }

    private fun ensureProjectionCallbackRegistered() {
        if (projectionCallback != null) return

        val callback = object : MediaProjection.Callback() {
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                synchronized(this@MediaProjectionCaptureManager) { capturedVisible = isVisible }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                resizeCapture(width, height)
            }

            override fun onStop() {
                AppLogger.w(TAG, "MediaProjection stopped")
                try {
                    MediaProjectionHolder.clear(context)
                } catch (_: Exception) {
                }
                release()
            }
        }

        projectionCallback = callback
        try {
            mediaProjection.registerCallback(callback, callbackHandler)
        } catch (e: Exception) {
            projectionCallback = null
            AppLogger.e(TAG, "Failed to register MediaProjection callback", e)
        }
    }

    @Synchronized private fun resizeCapture(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == captureWidth && height == captureHeight)) return
        val display = virtualDisplay ?: return
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        try {
            // Keep the same authorized projection/display. Recreating a VirtualDisplay with the
            // same Android 14 token is invalid; resizing both outputs removes letterboxing.
            display.resize(width, height, densityDpi)
            display.surface = next.surface
            val previous = imageReader
            imageReader = next
            installReaderListener(next)
            captureWidth = width
            captureHeight = height
            geometryRevision++
            invalidateBufferedFrame("SCREEN_GEOMETRY_CHANGED")
            previous?.close()
            AppLogger.d(TAG, "Resized shared screen: ${width}x${height}")
        } catch (error: Exception) {
            next.close()
            AppLogger.e(TAG, "Shared-screen resize failed", error)
            // A failed resize invalidates the pipeline; never deliver the old geometry as valid.
            release()
        }
    }

    private fun geometry(width: Int, height: Int): JSONObject {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return JSONObject().put("geometry_id", "$captureId:$geometryRevision:${display.rotation}")
            .put("display_id", display.displayId).put("rotation", display.rotation * 90)
            .put("capture_width", width).put("capture_height", height)
            .put("touch_width", metrics.widthPixels).put("touch_height", metrics.heightPixels)
            .put("content_rect", JSONObject().put("left", 0).put("top", 0).put("width", width).put("height", height))
            // A selected app window may not cover the screen. Its unknown screen offset must not
            // be guessed from the bitmap dimensions. Only full-display mapping is certified here.
            .put("touch_mapping_available", width == metrics.widthPixels && height == metrics.heightPixels)
    }

    private fun invalidateBufferedFrame(reason: String) {
        pendingFreshFrame?.completeExceptionally(IllegalStateException(reason))
        pendingFreshFrame = null
        bufferedFrame?.image?.close()
        bufferedFrame = null
        frameSignal.value = 0L
    }

    private fun installReaderListener(reader: ImageReader) {
        reader.setOnImageAvailableListener({ available ->
            synchronized(this@MediaProjectionCaptureManager) {
                if (available !== imageReader) return@setOnImageAvailableListener
                var acquired: Image? = null
                try {
                    val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                    acquired = image
                    val next = BufferedFrame(image, ++frameSequence, SystemClock.elapsedRealtime(),
                        System.currentTimeMillis(), geometry(image.width, image.height))
                    bufferedFrame?.image?.close()
                    bufferedFrame = next
                    acquired = null
                    streamError = null
                    frameSignal.value = next.sequence
                    pendingFreshFrame?.complete(Unit)
                } catch (error: Exception) {
                    streamError = error
                    invalidateBufferedFrame("Shared-screen producer failed")
                    frameSignal.value = -2L
                    AppLogger.e(TAG, "Shared-screen frame arrival failed", error)
                } finally { acquired?.close() }
            }
        }, callbackHandler)
    }

    @Synchronized fun snapshotState(): JSONObject = JSONObject()
        .put("active", virtualDisplay != null).put("frame_available", bufferedFrame != null)
        .put("frame_sequence", frameSequence).put("captured_content_visible", capturedVisible ?: JSONObject.NULL)
        .put("frame_wait_pending", pendingFreshFrame != null)
        .put("producer_error", streamError?.message ?: JSONObject.NULL)
        .put("geometry", geometry(captureWidth, captureHeight))
        .apply {
            bufferedFrame?.let { frame ->
                put("frame_id", "$captureId:${frame.sequence}").put("captured_at_ms", frame.acquiredAt)
                put("frame_age_ms", SystemClock.elapsedRealtime() - frame.acquiredElapsed)
            }
        }

    /** The manager owns one latest Image; callers copy under the lock and never close it. */
    @Synchronized fun captureToBitmap(): Bitmap? {
        if (pendingFreshFrame != null) return null
        val frame = bufferedFrame ?: return null
        return try { imageToBitmap(frame.image) }
        catch (error: Exception) { AppLogger.e(TAG, "Error copying shared-screen frame", error); null }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer.duplicate())

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        if (cropped !== bitmap) bitmap.recycle()

        return cropped
    }

    /**
     * Attach an empty reader to the existing display AFTER the action. An image delivered to this
     * previously unattached surface cannot be an old queued preview. No second projection/display
     * is created, and no Image.timestamp timebase assumption is required.
     */
    suspend fun captureFreshToFile(
        file: File, format: String = "png", mode: String = "new_surface",
        afterFrameId: String? = null, maxAgeMs: Long = 1_000L, timeoutMs: Long = 2_000L
    ): FreshFrame = freshCaptureLock.withLock {
        require(format == "png" || format == "rgba8888") { "Unsupported screen frame format" }
        val request = ScreenFrameReadRequest(mode, afterFrameId, maxAgeMs, timeoutMs)
        val requestedElapsed = SystemClock.elapsedRealtime()
        val ready = CompletableDeferred<Unit>()
        val afterSequence = request.afterSequence(captureId, synchronized(this) { frameSequence })
        if (mode == "new_surface") synchronized(this) {
            val display = checkNotNull(virtualDisplay) { "Shared-screen display is not ready" }
            val previous = checkNotNull(imageReader) { "Shared-screen reader is not ready" }
            val next = ImageReader.newInstance(previous.width, previous.height, PixelFormat.RGBA_8888, 3)
            try { display.surface = next.surface }
            catch (error: Exception) { next.close(); throw error }
            invalidateBufferedFrame("New frame requested")
            imageReader = next
            pendingFreshFrame = ready
            installReaderListener(next)
            previous.close()
        }
        var bitmap: Bitmap? = null
        try {
            withTimeout(timeoutMs) {
                if (mode == "new_surface") ready.await()
                else frameSignal.first {
                    synchronized(this@MediaProjectionCaptureManager) {
                        check(virtualDisplay != null) { "Shared screen stopped during frame wait" }
                        streamError?.let { throw it }
                        val frame = bufferedFrame
                        frame != null && frame.sequence > afterSequence &&
                            SystemClock.elapsedRealtime() - frame.acquiredElapsed <= maxAgeMs
                    }
                }
            }
            val copyStarted = SystemClock.elapsedRealtime()
            val stamp = synchronized(this) {
                check(virtualDisplay != null) { "Shared screen stopped during capture" }
                val frame = checkNotNull(bufferedFrame) { "Frame invalidated during capture" }
                check(frame.sequence > afterSequence) { "Frame sequence precondition changed" }
                if (mode == "new_surface") check(pendingFreshFrame === ready && frame.acquiredElapsed >= requestedElapsed) {
                    "Fresh frame invalidated during capture"
                }
                else check(SystemClock.elapsedRealtime() - frame.acquiredElapsed <= maxAgeMs) { "Latest frame exceeds age limit" }
                val image = frame.image
                if (format == "rgba8888") {
                    val plane = image.planes[0]
                    RawRgbaFrameWriter.write(plane.buffer.duplicate(), image.width, image.height,
                        plane.rowStride, plane.pixelStride, file)
                } else bitmap = checkNotNull(imageToBitmap(image)) { "Frame has invalid dimensions" }
                frame
            }
            val copyFinished = SystemClock.elapsedRealtime()
            if (format == "png") FileOutputStream(file).use { out ->
                check(checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, out)) { "Frame encoding failed" }
            }
            synchronized(this) { check(virtualDisplay != null) { "Shared screen stopped during encoding" } }
            FreshFrame(file.absolutePath, stamp.geometry.getInt("capture_width"), stamp.geometry.getInt("capture_height"),
                requestedElapsed, stamp.acquiredElapsed, stamp.acquiredAt, format, "$captureId:${stamp.sequence}",
                JSONObject(stamp.geometry.toString()), JSONObject().put("frame_wait_ms", copyStarted - requestedElapsed)
                    .put("frame_copy_ms", copyFinished - copyStarted)
                    .put("frame_encode_ms", SystemClock.elapsedRealtime() - copyFinished)
                    .put("frame_age_at_copy_ms", copyStarted - stamp.acquiredElapsed),
                if (mode == "new_surface") "new_surface" else "latest_buffer")
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            bitmap?.recycle()
            synchronized(this) { if (pendingFreshFrame === ready) pendingFreshFrame = null }
        }
    }

    /**
     * Capture the latest frame to a file.
     */
    fun captureToFile(file: File): Boolean {
        val bitmap = captureToBitmap() ?: return false
        return try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    return false
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error writing MediaProjection capture to file", e)
            false
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized fun release() {
        displayListener?.let { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null
        invalidateBufferedFrame("Shared screen stopped during capture")
        frameSignal.value = -1L
        if (activeManager === this) activeManager = null
        try {
            virtualDisplay?.release()
            imageReader?.close()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing resources", e)
        }
        virtualDisplay = null
        imageReader = null

        val callback = projectionCallback
        if (callback != null) {
            try {
                mediaProjection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null
    }
}
