package com.ai.limbs.plugins.artstudio

/** Whole-animation histogram, learned RGB palette; zero is the transparent index. */
internal class ArtGifPalette private constructor(val colors: IntArray, private val exact: Map<Int, Int>?, private val bins: IntArray?) {
    fun index(pixel: Int): Int {
        if ((pixel ushr 24) < 128) return 0
        val rgb = pixel and 0xFFFFFF
        val index = if (exact != null) requireNotNull(exact[rgb]) { "像素不在已采样调色板中" }
            else requireNotNull(bins)[bin(rgb)]
        require(index in 1..255) { "像素不在已采样色域中" }
        return index
    }
    private data class Sample(val rgb: Int, val weight: Long) {
        fun channel(axis: Int) = (rgb ushr (16 - axis * 8)) and 255
    }
    private class Box(val samples: List<Sample>) {
        val ranges = IntArray(3) { axis -> samples.maxOf { it.channel(axis) } - samples.minOf { it.channel(axis) } }
        val axis = ranges.indices.maxBy { ranges[it] }
        val weight = samples.sumOf { it.weight }
        val score = ranges[axis].toLong() * weight
        fun color(): Int {
            var rgb = 0
            for (axis in 0..2) rgb = (rgb shl 8) or ((samples.sumOf { it.channel(axis) * it.weight } + weight / 2) / weight).toInt()
            return rgb
        }
    }
    class Builder {
        private val counts = LongArray(32768)
        private val red = LongArray(32768)
        private val green = LongArray(32768)
        private val blue = LongArray(32768)
        private var exact: MutableMap<Int, Long>? = linkedMapOf()
        var hasTransparency = false
            private set
        fun addRow(pixels: IntArray, weight: Int = 1) {
            require(weight > 0)
            for (pixel in pixels) {
                if ((pixel ushr 24) < 128) { hasTransparency = true; continue }
                val rgb = pixel and 0xFFFFFF
                val index = bin(rgb)
                counts[index] += weight.toLong()
                red[index] += (rgb ushr 16).toLong() * weight
                green[index] += ((rgb ushr 8) and 255).toLong() * weight
                blue[index] += (rgb and 255).toLong() * weight
                exact?.let { colors ->
                    colors[rgb] = (colors[rgb] ?: 0L) + weight
                    if (colors.size > 255) exact = null
                }
            }
        }
        fun build(): ArtGifPalette {
            val exactColors = exact
            if (exactColors != null) {
                val rgb = exactColors.keys.toIntArray()
                val colors = if (rgb.isEmpty()) intArrayOf(0) else rgb
                return ArtGifPalette(colors, rgb.withIndex().associate { it.value to it.index + 1 }, null)
            }
            val samples = counts.indices.filter { counts[it] > 0 }.map { index ->
                val n = counts[index]
                Sample((((red[index] + n / 2) / n).toInt() shl 16) or
                    (((green[index] + n / 2) / n).toInt() shl 8) or ((blue[index] + n / 2) / n).toInt(), n)
            }
            val boxes = mutableListOf(Box(samples))
            while (boxes.size < 255) {
                val box = boxes.filter { it.samples.size > 1 && it.score > 0 }.maxByOrNull { it.score } ?: break
                val sorted = box.samples.sortedBy { it.channel(box.axis) }
                var weight = 0L
                var split = 1
                for (index in 0 until sorted.lastIndex) {
                    weight += sorted[index].weight; split = index + 1
                    if (weight * 2 >= box.weight) break
                }
                boxes.remove(box)
                boxes.add(Box(sorted.subList(0, split))); boxes.add(Box(sorted.subList(split, sorted.size)))
            }
            val colors = boxes.map { it.color() }.toIntArray()
            val lookup = IntArray(32768)
            for (index in counts.indices) if (counts[index] > 0) {
                val n = counts[index]
                val r = ((red[index] + n / 2) / n).toInt()
                val g = ((green[index] + n / 2) / n).toInt()
                val b = ((blue[index] + n / 2) / n).toInt()
                lookup[index] = colors.indices.minBy { color ->
                    val dr = r - (colors[color] ushr 16)
                    val dg = g - ((colors[color] ushr 8) and 255)
                    val db = b - (colors[color] and 255)
                    dr * dr + dg * dg + db * db
                } + 1
            }
            return ArtGifPalette(colors, null, lookup)
        }
    }
    companion object {
        private fun bin(rgb: Int) = ((rgb ushr 19) shl 10) or (((rgb ushr 11) and 31) shl 5) or ((rgb ushr 3) and 31)
    }
}
