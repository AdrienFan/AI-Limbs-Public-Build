package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

/** A cached image is usable for quiet-time evidence only while the capture source is valid. */
internal object VisualCaptureEvidence {
    /** False means this frame was superseded between capture and the source-health query. */
    fun requireActive(status: JSONObject, sessionId: String, geometryId: String,
        followGeometry: Boolean = false): Boolean {
        VisualOperationResult.requireHost(status)
        check(status.getString("session_id") == sessionId && status.getBoolean("active") &&
            status.getBoolean("projection_ready") && status.getString("state") == "READY") {
            "SCREEN_CAPTURE_INACTIVE: 共享屏会话不可用"
        }
        val capture = status.getJSONObject("capture")
        check(capture.getBoolean("active") && capture.getBoolean("frame_available") && capture.has("producer_error") &&
            capture.isNull("producer_error")) { "SCREEN_CAPTURE_UNAVAILABLE: 采集停止、无帧或生产器异常" }
        check(!capture.has("captured_content_visible") || capture.isNull("captured_content_visible") ||
            capture.getBoolean("captured_content_visible")) { "SCREEN_CONTENT_HIDDEN: 共享内容不可见" }
        val matches = capture.getJSONObject("geometry").getString("geometry_id") == geometryId
        check(matches || followGeometry) {
            "SCREEN_GEOMETRY_CHANGED: 等待期间屏幕方向或尺寸改变"
        }
        return matches
    }
}
