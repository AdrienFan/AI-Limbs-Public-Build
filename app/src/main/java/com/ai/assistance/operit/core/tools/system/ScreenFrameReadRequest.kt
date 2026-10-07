package com.ai.assistance.operit.core.tools.system

/** Pure request contract shared by the live ImageReader and its caller-facing frame operations. */
internal data class ScreenFrameReadRequest(
    val mode: String,
    val afterFrameId: String?,
    val maxAgeMs: Long,
    val timeoutMs: Long
) {
    init {
        require(mode == "new_surface" || mode == "latest") { "Unsupported frame mode" }
        require(maxAgeMs in 0L..60_000L && timeoutMs in 1L..15_000L) { "Invalid frame wait bounds" }
        require(mode == "latest" || afterFrameId == null) { "after_frame_id requires latest mode" }
    }

    fun afterSequence(producerId: String, lastSequence: Long): Long {
        val id = afterFrameId ?: return -1L
        require(id.substringBeforeLast(':') == producerId) { "Frame belongs to a different capture session" }
        return id.substringAfterLast(':').toLong().also { sequence ->
            require(sequence in 1L..lastSequence) { "Unknown frame sequence" }
        }
    }
}
