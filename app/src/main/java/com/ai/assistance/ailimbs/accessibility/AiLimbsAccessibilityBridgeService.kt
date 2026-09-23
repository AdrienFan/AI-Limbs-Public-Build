package com.ai.assistance.ailimbs.accessibility

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * AI Limbs 主 APK 内部跨进程桥。
 *
 * Host、Resident Core 与无障碍进程之间只通过这个非导出的 Binder 通道通信。
 */
class AiLimbsAccessibilityBridgeService : Service() {
    private val proxy = object : IAiLimbsAccessibilityService.Stub() {
        override fun getUiHierarchy(): String =
            backend()?.getUiHierarchy() ?: ""

        override fun performClick(x: Int, y: Int): Boolean =
            backend()?.performClick(x, y) ?: false

        override fun performLongPress(x: Int, y: Int): Boolean =
            backend()?.performLongPress(x, y) ?: false

        override fun performGlobalAction(actionId: Int): Boolean =
            backend()?.performGlobalAction(actionId) ?: false

        override fun performSwipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            duration: Long
        ): Boolean =
            backend()?.performSwipe(startX, startY, endX, endY, duration) ?: false
        override fun findFocusedNodeId(): String? =
            backend()?.findFocusedNodeId()

        override fun setTextOnNode(nodeId: String, text: String): Boolean =
            backend()?.setTextOnNode(nodeId, text) ?: false

        override fun takeScreenshot(path: String, format: String): Boolean =
            backend()?.takeScreenshot(path, format) ?: false

        override fun isAccessibilityServiceEnabled(): Boolean =
            AiLimbsAccessibilityService.isServiceConnected

        override fun getCurrentActivityName(): String =
            AiLimbsAccessibilityService.currentActivityName
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "AI Limbs accessibility bridge bound")
        return proxy
    }

    private fun backend(): IAiLimbsAccessibilityService? {
        val value = AiLimbsAccessibilityService.binder
        if (value == null) {
            Log.w(TAG, "Accessibility service is not connected")
        }
        return value
    }

    private companion object {
        const val TAG = "AiLimbsAccessibilityBridge"
    }
}
