package com.ai.limbs.plugins.visualmanager

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Screen frames are already oriented by the producer; never rotate them a second time. */
internal object FrameCoordinates {
    fun mapping(frame: JSONObject): Any {
        val geometry = frame.getJSONObject("geometry")
        if (!geometry.getBoolean("touch_mapping_available")) return JSONObject.NULL
        require(frame.getInt("width") > 1 && frame.getInt("height") > 1)
        val sx = (geometry.getInt("touch_width") - 1).toDouble() / (frame.getInt("width") - 1)
        val sy = (geometry.getInt("touch_height") - 1).toDouble() / (frame.getInt("height") - 1)
        return JSONArray(listOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0))
    }

    fun normalized(frame: JSONObject, x: Double, y: Double): Pair<Int, Int> {
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) { "图像比例坐标必须在 0..1" }
        val geometry = frame.getJSONObject("geometry")
        check(geometry.getBoolean("touch_mapping_available")) { "捕获区域没有可验证的全屏触摸映射" }
        val width = geometry.getInt("touch_width")
        val height = geometry.getInt("touch_height")
        require(width > 0 && height > 0)
        return (x * (width - 1)).roundToInt() to (y * (height - 1)).roundToInt()
    }
}
