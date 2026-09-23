package com.ai.assistance.ailimbs.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Xml
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import org.xmlpull.v1.XmlSerializer

/**
 * AI Limbs 内置无障碍服务。
 *
 * 这是宿主的静态 Android 入口，不承载插件业务；插件通过受控 Host 能力使用它。
 */
class AiLimbsAccessibilityService : AccessibilityService() {
    private val screenshotLock = Any()
    private var lastScreenshotTimestamp = 0L
    private val minScreenshotIntervalMs = 1100L
    private val serviceBinder = object : IAiLimbsAccessibilityService.Stub() {
        override fun getUiHierarchy(): String = captureUiHierarchyAsXml()

        override fun performClick(x: Int, y: Int): Boolean =
            dispatchPointGesture(x, y, 50L)

        override fun performLongPress(x: Int, y: Int): Boolean =
            dispatchPointGesture(x, y, 600L)

        override fun performGlobalAction(actionId: Int): Boolean =
            this@AiLimbsAccessibilityService.performGlobalAction(actionId)

        override fun performSwipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            duration: Long
        ): Boolean {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(1L))
            return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
        override fun findFocusedNodeId(): String? {
            val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return null
            return try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.toShortString()
            } finally {
                node.recycle()
            }
        }

        override fun setTextOnNode(nodeId: String, text: String): Boolean {
            val root = rootInActiveWindow ?: return false
            val container = try {
                findNodeByBounds(root, nodeId)
            } finally {
                root.recycle()
            } ?: return false

            var target: AccessibilityNodeInfo? = null
            return try {
                target = findFirstEditableNode(container) ?: return false
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                target?.recycle()
                container.recycle()
            }
        }
        override fun takeScreenshot(path: String, format: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            var success = false
            synchronized(screenshotLock) {
                val elapsed = System.currentTimeMillis() - lastScreenshotTimestamp
                if (elapsed in 0 until minScreenshotIntervalMs) {
                    try {
                        Thread.sleep(minScreenshotIntervalMs - elapsed)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                lastScreenshotTimestamp = System.currentTimeMillis()
                val latch = CountDownLatch(1)
                this@AiLimbsAccessibilityService.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            buffer.close()
                            success = bitmap?.let {
                                try {
                                    writeBitmap(it, File(path), format)
                                } finally {
                                    it.recycle()
                                }
                            } ?: false
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot failed: $errorCode")
                            latch.countDown()
                        }
                    }
                )
                try {
                    latch.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return success
        }

        override fun isAccessibilityServiceEnabled(): Boolean = isServiceConnected

        override fun getCurrentActivityName(): String = currentActivityName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        binder = serviceBinder
        Log.i(TAG, "AI Limbs accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.takeIf { it.isNotBlank() }?.let {
                currentActivityName = it
            }
        }
    }

    override fun onInterrupt() {
        isServiceConnected = false
        binder = null
        currentActivityName = ""
        Log.w(TAG, "AI Limbs accessibility service interrupted")
    }
    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        binder = null
        currentActivityName = ""
        Log.i(TAG, "AI Limbs accessibility service unbound")
        return super.onUnbind(intent)
    }

    private fun dispatchPointGesture(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun writeBitmap(bitmap: Bitmap, file: File, format: String): Boolean {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return false
        }
        val normalized = format.lowercase()
        val compressFormat = when (normalized) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.PNG
        }
        return file.outputStream().use { output ->
            bitmap.compress(
                compressFormat,
                if (compressFormat == Bitmap.CompressFormat.JPEG) 90 else 100,
                output
            )
        }
    }
    private fun captureUiHierarchyAsXml(): String {
        val root = rootInActiveWindow ?: return ""
        val serializer = Xml.newSerializer()
        val writer = StringWriter()
        return try {
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "hierarchy")
            serializeNode(serializer, root)
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
            writer.toString()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to capture UI hierarchy", error)
            ""
        }
    }

    private fun serializeNode(serializer: XmlSerializer, node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            serializer.startTag(null, "node")
            serializer.attribute(null, "class", sanitize(node.className?.toString()))
            serializer.attribute(null, "package", sanitize(node.packageName?.toString()))
            serializer.attribute(null, "content-desc", sanitize(node.contentDescription?.toString()))
            serializer.attribute(null, "text", sanitize(node.text?.toString()))
            serializer.attribute(null, "resource-id", sanitize(node.viewIdResourceName))
            serializer.attribute(null, "clickable", node.isClickable.toString())
            serializer.attribute(null, "enabled", node.isEnabled.toString())
            serializer.attribute(null, "focused", node.isFocused.toString())
            serializer.attribute(null, "selected", node.isSelected.toString())
            serializer.attribute(null, "editable", node.isEditable.toString())
            val rect = Rect()
            node.getBoundsInScreen(rect)
            serializer.attribute(null, "bounds", rect.toShortString())
            for (index in 0 until node.childCount) {
                serializeNode(serializer, node.getChild(index))
            }
            serializer.endTag(null, "node")
        } finally {
            node.recycle()
        }
    }

    private fun findNodeByBounds(
        node: AccessibilityNodeInfo,
        bounds: String
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.toShortString() == bounds) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = try {
                findNodeByBounds(child, bounds)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = try {
                findFirstEditableNode(child)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }
    private fun sanitize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val out = StringBuilder(value.length)
        value.forEach { ch ->
            if (
                ch == '\t' || ch == '\n' || ch == '\r' ||
                ch in '\u0020'..'\uD7FF' ||
                ch in '\uE000'..'\uFFFD'
            ) {
                out.append(ch)
            } else {
                out.append(' ')
            }
        }
        return out.toString()
    }

    companion object {
        private const val TAG = "AiLimbsAccessibility"

        @Volatile
        internal var isServiceConnected: Boolean = false
            private set

        @Volatile
        internal var binder: IAiLimbsAccessibilityService.Stub? = null
            private set

        @Volatile
        internal var currentActivityName: String = ""
            private set
    }
}
