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

    data class FreshFrame(
        val path: String, val width: Int, val height: Int,
        val requestedElapsedMs: Long, val capturedElapsedMs: Long, val capturedAtMs: Long,
        val format: String = "png"
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
            val densityDpi = metrics.densityDpi

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
                    val dimensions = if (format == "rgba8888") {
                        val plane = image.planes[0]
                        RawRgbaFrameWriter.write(plane.buffer, image.width, image.height,
                            plane.rowStride, plane.pixelStride, file)
                        image.width to image.height
                    } else {
                        bitmap = checkNotNull(imageToBitmap(image)) { "New shared-screen frame has invalid dimensions" }
                        checkNotNull(bitmap).let { it.width to it.height }
                    }
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
                requestedElapsed, capturedElapsed, capturedAt, format)
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
