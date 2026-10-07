package com.ai.limbs.plugins.visualmanager

import java.io.File
import org.json.JSONObject

/** Explicit raw-frame representation; malformed/unsupported frames are never decoded as images. */
internal data class RawScreenFrame(val width: Int, val height: Int) {
    companion object {
        fun read(frame: JSONObject, file: File): RawScreenFrame {
            require(frame.getString("format") == "rgba8888") { "Expected RGBA8888 screen frame" }
            val width = frame.getInt("width")
            val height = frame.getInt("height")
            require(width in 1..8192 && height in 1..8192) { "Invalid raw frame dimensions" }
            val bytes = width.toLong() * height * 4L
            require(bytes <= 67_108_864L) { "Raw frame exceeds 64 MiB" }
            require(frame.getInt("pixel_stride") == 4 && frame.getInt("row_stride") == width * 4) { "Raw frame is not packed RGBA8888" }
            require(file.isFile && file.length() == bytes && frame.getLong("byte_count") == bytes) { "Raw frame byte count mismatch" }
            return RawScreenFrame(width, height)
        }
    }
}
