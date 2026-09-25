package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Color

/** Radius sampling shared by touch and LANER color.sample; transparent pixels keep their alpha. */
internal object ArtColorSampler {
    fun sample(bitmap: Bitmap, x: Int, y: Int, radius: Int): Int {
        require(x in 0 until bitmap.width && y in 0 until bitmap.height) { "坐标不在画布内" }
        require(radius in 0..32) { "取色半径必须在 0–32 px 之间" }
        if (radius == 0) return bitmap.getPixel(x, y)
        var count = 0L
        var alpha = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        for (py in maxOf(0, y - radius)..minOf(bitmap.height - 1, y + radius)) {
            for (px in maxOf(0, x - radius)..minOf(bitmap.width - 1, x + radius)) {
                val dx = px - x
                val dy = py - y
                if (dx * dx + dy * dy > radius * radius) continue
                val pixel = bitmap.getPixel(px, py)
                val a = Color.alpha(pixel).toLong()
                count++
                alpha += a
                red += Color.red(pixel).toLong() * a
                green += Color.green(pixel).toLong() * a
                blue += Color.blue(pixel).toLong() * a
            }
        }
        if (alpha == 0L) return Color.TRANSPARENT
        return Color.argb(((alpha + count / 2) / count).toInt(),
            ((red + alpha / 2) / alpha).toInt(),
            ((green + alpha / 2) / alpha).toInt(),
            ((blue + alpha / 2) / alpha).toInt())
    }

    /** blend=100 uses the sampled color; blend=0 keeps the original color. */
    fun blend(base: Int, sampled: Int, blend: Int): Int {
        require(blend in 0..100) { "取色混合必须在 0–100% 之间" }
        val originalWeight = 100L - blend
        val sampledWeight = blend.toLong()
        val originalAlpha = Color.alpha(base).toLong() * originalWeight
        val sampledAlpha = Color.alpha(sampled).toLong() * sampledWeight
        val combined = originalAlpha + sampledAlpha
        if (combined == 0L) return Color.TRANSPARENT
        fun channel(extract: (Int) -> Int): Int =
            ((extract(base).toLong() * originalAlpha +
                extract(sampled).toLong() * sampledAlpha + combined / 2) / combined).toInt()
        return Color.argb(((combined + 50) / 100).toInt(),
            channel { Color.red(it) }, channel { Color.green(it) }, channel { Color.blue(it) })
    }
}
