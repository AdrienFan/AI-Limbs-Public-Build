package com.ai.assistance.operit.core.tools.system

/** Correlate JPEG timestamps with THIS capture's onCaptureStarted callback, regardless of callback order. */
internal class CameraFrameMatcher {
    private val pending = linkedMapOf<Long, ByteArray>()
    var timestampNs: Long? = null
        private set

    @Synchronized fun expect(timestamp: Long): ByteArray? {
        require(timestamp > 0L) { "Camera capture timestamp is invalid" }
        check(timestampNs == null) { "Capture identity was already set" }
        timestampNs = timestamp
        val match = pending[timestamp]
        pending.clear()
        return match
    }

    @Synchronized fun offer(timestamp: Long, bytes: ByteArray): ByteArray? {
        val expected = timestampNs
        if (expected != null) return if (timestamp == expected) bytes else null
        // ImageReader has two slots; retain only that bounded callback-order window.
        pending[timestamp] = bytes
        while (pending.size > 2) pending.remove(pending.keys.first())
        return null
    }
}
