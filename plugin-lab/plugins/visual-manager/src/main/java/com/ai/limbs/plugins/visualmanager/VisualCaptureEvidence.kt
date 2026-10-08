package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

/** A cached image is usable for quiet-time evidence only while the capture source is valid. */
internal object VisualCaptureEvidence {
    /** False means this frame was superseded, including the empty-buffer interval of a resize. */
    fun requireActive(status: JSONObject, sessionId: String, geometryId: String,
        followGeometry: Boolean = false): Boolean {
        VisualOperationResult.requireHost(status)
        check(status.getString("session_id") == sessionId && status.getBoolean("active") &&
            status.getBoolean("projection_ready") && status.getString("state") == "READY") {
            "SCREEN_CAPTURE_INACTIVE: 共享屏会话不可用"
        }
        val capture = status.getJSONObject("capture")
        check(capture.getBoolean("active")) { "SCREEN_CAPTURE_UNAVAILABLE: 采集停止 (capture.active=false)" }
        check(capture.has("producer_error")) { "SCREEN_CAPTURE_UNAVAILABLE: 生产器状态缺失" }
        check(capture.isNull("producer_error")) {
            "SCREEN_CAPTURE_UNAVAILABLE: 生产器异常 (${capture.get("producer_error")})"
        }
        check(!capture.has("captured_content_visible") || capture.isNull("captured_content_visible") ||
            capture.getBoolean("captured_content_visible")) { "SCREEN_CONTENT_HIDDEN: 共享内容不可见" }
        val frameAvailable = capture.getBoolean("frame_available")
        val currentGeometryId = capture.getJSONObject("geometry").getString("geometry_id")
        val matches = currentGeometryId == geometryId
        check(matches || followGeometry) {
            "SCREEN_GEOMETRY_CHANGED: 等待期间屏幕方向或尺寸改变"
        }
        // Resize clears the old ImageReader buffer before the first new-direction frame arrives.
        // That healthy geometry transition invalidates this evidence; it is not a producer failure.
        if (!matches) return false
        check(frameAvailable) {
            "SCREEN_CAPTURE_UNAVAILABLE: 当前方向没有可用帧 (geometry_id=$currentGeometryId)"
        }
        return true
    }
}
