package com.ai.limbs.plugins.visualmanager

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** Uncompressed RGB samples. Comparison never depends on JPEG quality or preview dimensions. */
internal class VisualSample(val pixels: IntArray) {
    init { require(pixels.size == EDGE * EDGE) }
    fun difference(other: VisualSample, region: VisualRegion, tolerance: Int): Double {
        var changed = 0
        var count = 0
        for (y in 0 until EDGE) for (x in 0 until EDGE) {
            if (!region.contains((x + 0.5) / EDGE, (y + 0.5) / EDGE)) continue
            val a = pixels[y * EDGE + x]
            val b = other.pixels[y * EDGE + x]
            if (abs(((a shr 16) and 255) - ((b shr 16) and 255)) > tolerance ||
                abs(((a shr 8) and 255) - ((b shr 8) and 255)) > tolerance ||
                abs((a and 255) - (b and 255)) > tolerance) changed++
            count++
        }
        require(count > 0) { "region 未覆盖任何采样点" }
        return changed.toDouble() / count
    }
    companion object {
        const val EDGE = 64
        fun sample(width: Int, height: Int, pixel: (Int, Int) -> Int): VisualSample = VisualSample(
            IntArray(EDGE * EDGE) { i -> pixel(((i % EDGE + 0.5) * width / EDGE).toInt(),
                ((i / EDGE + 0.5) * height / EDGE).toInt()) })
        fun raw(frame: RawScreenFrame, file: File): VisualSample {
            val pixels = IntArray(EDGE * EDGE)
            RandomAccessFile(file, "r").use { input ->
                val row = ByteArray(frame.width * 4)
                for (y in 0 until EDGE) {
                    val sy = ((y + 0.5) * frame.height / EDGE).toInt()
                    input.seek(sy.toLong() * row.size)
                    input.readFully(row)
                    for (x in 0 until EDGE) {
                        val sx = ((x + 0.5) * frame.width / EDGE).toInt() * 4
                        pixels[y * EDGE + x] = ((row[sx].toInt() and 255) shl 16) or
                            ((row[sx + 1].toInt() and 255) shl 8) or (row[sx + 2].toInt() and 255)
                    }
                }
            }
            return VisualSample(pixels)
        }
    }
}

internal data class VisualRegion(val left: Double = 0.0, val top: Double = 0.0,
    val width: Double = 1.0, val height: Double = 1.0) {
    init {
        require(listOf(left, top, width, height).all { it.isFinite() })
        require(left >= 0 && top >= 0 && width >= 1.0 / VisualSample.EDGE && height >= 1.0 / VisualSample.EDGE &&
            left + width <= 1.0 && top + height <= 1.0) { "region 必须位于图内，宽高至少 1/64" }
    }
    fun contains(x: Double, y: Double) = x >= left && x < left + width && y >= top && y < top + height
}

internal data class VisualWaitOptions(val mode: String, val timeoutMs: Long = 5000,
    val stableMs: Long = 250, val intervalMs: Long = 100, val changeRatio: Double = 0.02,
    val stableRatio: Double = 0.0, val pixelTolerance: Int = 12, val region: VisualRegion = VisualRegion()) {
    init {
        require(mode in setOf("new_frame", "change", "stable", "change_then_stable")) { "无效观察模式" }
        require(timeoutMs in 100..15000 && intervalMs in 50..1000 && stableMs in 100..5000)
        require(mode !in setOf("stable", "change_then_stable") || stableMs <= timeoutMs)
        require(changeRatio.isFinite() && changeRatio > 0 && changeRatio <= 1)
        require(stableRatio.isFinite() && stableRatio >= 0 && stableRatio < changeRatio)
        require(pixelTolerance in 0..254)
    }
}

/** Uses distinct producer frames and their monotonic capture times, never repeated cached frames. */
internal class VisualChangeDetector(private val baseline: VisualSample, private val options: VisualWaitOptions) {
    private var previous = baseline
    private var anchor: VisualSample? = null
    private var anchorTime = 0L
    private var lastTime = -1L
    private var lastId: String? = null
    var samples = 0; private set
    var changed = false; private set
    var baselineRatio = 0.0; private set
    var adjacentRatio = 0.0; private set
    var quietMs = 0L; private set

    fun accept(id: String, capturedElapsedMs: Long, sample: VisualSample): Boolean {
        if (id == lastId) return false
        require(capturedElapsedMs >= lastTime) { "帧时间倒退" }
        lastId = id; lastTime = capturedElapsedMs; samples++
        baselineRatio = baseline.difference(sample, options.region, options.pixelTolerance)
        adjacentRatio = previous.difference(sample, options.region, options.pixelTolerance)
        changed = changed || baselineRatio >= options.changeRatio
        val currentAnchor = anchor
        if (currentAnchor == null || adjacentRatio > options.stableRatio ||
            currentAnchor.difference(sample, options.region, options.pixelTolerance) > options.stableRatio) {
            anchor = sample; anchorTime = capturedElapsedMs
        }
        quietMs = capturedElapsedMs - anchorTime
        previous = sample
        return when (options.mode) {
            "new_frame" -> true
            "change" -> changed
            "stable" -> samples >= 2 && quietMs >= options.stableMs
            "change_then_stable" -> changed && samples >= 2 && quietMs >= options.stableMs
            else -> error("Invalid visual wait mode")
        }
    }
}
