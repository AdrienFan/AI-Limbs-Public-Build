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

    data class FreshFrame(
        val path: String, val width: Int, val height: Int,
        val requestedElapsedMs: Long, val capturedElapsedMs: Long, val capturedAtMs: Long,
        val format: String = "png",
        val frameId: String,
        val geometry: JSONObject,
        val timings: JSONObject
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

            // Using RGBA_8888
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

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
                    if (displayId == Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT < 34) {
                        val current = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.getRealMetrics(current)
                        resizeCapture(current.widthPixels, current.heightPixels)
                    }
                }
            }
            displayListener = listener
            context.getSystemService(DisplayManager::class.java).registerDisplayListener(listener, callbackHandler)
            AppLogger.d(TAG, "Created MediaProjection virtual display: ${width}x${height}")
        } catch (e: Exception) {
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = null
            AppLogger.e(TAG, "Failed to create MediaProjection virtual display", e)
        }
    }

    private fun ensureProjectionCallbackRegistered() {
        if (projectionCallback != null) return

        val callback = object : MediaProjection.Callback() {
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
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        try {
            // Keep the same authorized projection/display. Recreating a VirtualDisplay with the
            // same Android 14 token is invalid; resizing both outputs removes letterboxing.
            display.resize(width, height, densityDpi)
            display.surface = next.surface
            val previous = imageReader
            imageReader = next
            captureWidth = width
            captureHeight = height
            geometryRevision++
            pendingFreshFrame?.completeExceptionally(IllegalStateException("SCREEN_GEOMETRY_CHANGED"))
            pendingFreshFrame = null
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

    /**
     * Capture the latest frame as a raw bitmap.
     */
    @Synchronized fun captureToBitmap(): Bitmap? {
        // A fresh request owns its new reader until its first post-request frame is consumed.
        if (pendingFreshFrame != null) return null
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            // Try to get the latest image
            image = reader.acquireLatestImage()
            if (image == null) {
                 // Sometimes it takes a moment for the first frame to arrive
                 return null
            }

            imageToBitmap(image)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error capturing frame from MediaProjection", e)
            null
        } finally {
            image?.close()
        }
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
        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        if (cropped !== bitmap) bitmap.recycle()

        return cropped
    }

    /**
     * Attach an empty reader to the existing display AFTER the action. An image delivered to this
     * previously unattached surface cannot be an old queued preview. No second projection/display
     * is created, and no Image.timestamp timebase assumption is required.
     */
    suspend fun captureFreshToFile(file: File, format: String = "png"): FreshFrame = freshCaptureLock.withLock {
        require(format == "png" || format == "rgba8888") { "Unsupported screen frame format" }
        val ready = CompletableDeferred<Unit>()
        val requestedElapsed = SystemClock.elapsedRealtime()
        var acquisitionGeometry: JSONObject? = null
        var frameId = ""
        var acquisitionFinished = requestedElapsed
        val reader = synchronized(this) {
            val display = checkNotNull(virtualDisplay) { "Shared-screen display is not ready" }
            val previous = checkNotNull(imageReader) { "Shared-screen reader is not ready" }
            val next = ImageReader.newInstance(previous.width, previous.height, PixelFormat.RGBA_8888, 2)
            next.setOnImageAvailableListener({ available ->
                synchronized(this@MediaProjectionCaptureManager) {
                    if (available === imageReader && pendingFreshFrame === ready) ready.complete(Unit)
                }
            }, callbackHandler)
            try {
                display.surface = next.surface
            } catch (error: Exception) {
                next.close()
                throw error
            }
            imageReader = next
            pendingFreshFrame = ready
            previous.close()
            next
        }
        var bitmap: Bitmap? = null
        try {
            // This bounds an actual frame-arrival wait; it is not a fixed UI settling delay.
            withTimeout(2_000L) { ready.await() }
            // Return both clocks from the synchronized acquisition; assigning outer vals inside
            // try/finally is not definite initialization in Kotlin's data-flow analysis.
            val (capturedElapsed, capturedAt, dimensions) = synchronized(this) {
                check(imageReader === reader && pendingFreshFrame === ready) { "Shared screen stopped during capture" }
                val image = checkNotNull(reader.acquireLatestImage()) { "New shared-screen frame is unavailable" }
                try {
                    val acquiredElapsed = SystemClock.elapsedRealtime()
                    val acquiredAt = System.currentTimeMillis()
                    acquisitionGeometry = geometry(image.width, image.height)
                    frameId = "$captureId:${++frameSequence}"
                    val dimensions = if (format == "rgba8888") {
                        val plane = image.planes[0]
                        RawRgbaFrameWriter.write(plane.buffer, image.width, image.height,
                            plane.rowStride, plane.pixelStride, file)
                        image.width to image.height
                    } else {
                        bitmap = checkNotNull(imageToBitmap(image)) { "New shared-screen frame has invalid dimensions" }
                        checkNotNull(bitmap).let { it.width to it.height }
                    }
                    acquisitionFinished = SystemClock.elapsedRealtime()
                    Triple(acquiredElapsed, acquiredAt, dimensions)
                } finally { image.close() }
            }
            if (format == "png") {
                val captured = checkNotNull(bitmap)
                FileOutputStream(file).use { out ->
                    check(captured.compress(Bitmap.CompressFormat.PNG, 100, out)) { "New frame encoding failed" }
                }
            }
            FreshFrame(file.absolutePath, dimensions.first, dimensions.second,
                requestedElapsed, capturedElapsed, capturedAt, format, frameId, checkNotNull(acquisitionGeometry),
                JSONObject().put("frame_wait_ms", capturedElapsed - requestedElapsed)
                    .put("frame_copy_ms", acquisitionFinished - capturedElapsed)
                    .put("frame_encode_ms", SystemClock.elapsedRealtime() - acquisitionFinished))
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            bitmap?.recycle()
            synchronized(this) {
                if (imageReader === reader) reader.setOnImageAvailableListener(null, null)
                if (pendingFreshFrame === ready) pendingFreshFrame = null
            }
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
        pendingFreshFrame?.completeExceptionally(IllegalStateException("Shared screen stopped during capture"))
        pendingFreshFrame = null
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
