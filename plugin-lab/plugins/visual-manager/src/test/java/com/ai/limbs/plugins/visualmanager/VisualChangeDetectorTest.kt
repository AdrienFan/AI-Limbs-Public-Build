package com.ai.limbs.plugins.visualmanager

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualChangeDetectorTest {
    private fun solid(color: Int) = VisualSample(IntArray(4096) { color })
    private fun detector(mode: String = "stable", region: VisualRegion = VisualRegion(), tolerance: Int = 12) =
        VisualChangeDetector(solid(0), VisualWaitOptions(mode, region = region, pixelTolerance = tolerance))

    @Test fun newFramesMustSpanTheEntireQuietWindow() {
        val d = detector()
        assertFalse(d.accept("a", 100, solid(0)))
        assertFalse(d.accept("b", 349, solid(0)))
        assertTrue(d.accept("c", 350, solid(0)))
        assertEquals(250L, d.quietMs)
    }
    @Test fun aCachedFrameCannotProveStability() {
        val d = detector()
        assertFalse(d.accept("a", 100, solid(0)))
        assertFalse(d.accept("a", 100, solid(0), observedElapsedMs = 9999))
        assertEquals(1, d.samples); assertEquals(0L, d.quietMs)
    }
    @Test fun verifiedStaticProducerCanProveQuietPixelsWithoutForgingCaptureTime() {
        val d = detector()
        assertFalse(d.accept("a", 100, solid(0), 200, true))
        assertFalse(d.accept("a", 100, solid(0), 449, true))
        assertTrue(d.accept("a", 100, solid(0), 450, true))
        assertEquals(1, d.samples); assertEquals(3, d.observations); assertEquals(250L, d.quietMs)
    }
    @Test fun staticRepeatedProducerCannotInventAChange() {
        val d = detector("change_then_stable")
        assertFalse(d.accept("a", 100, solid(0), 100, true))
        assertFalse(d.accept("a", 100, solid(0), 1000, true))
        assertFalse(d.changed)
        assertFalse(d.accept("b", 1001, solid(0xffffff), 1001, true))
        assertTrue(d.accept("b", 1001, solid(0xffffff), 1251, true))
    }
    @Test(expected = IllegalArgumentException::class) fun cachedIdentityCannotHideChangedPixels() {
        val d = detector(); d.accept("a", 100, solid(0), 100, true)
        d.accept("a", 100, solid(0xffffff), 400, true)
    }
    @Test(expected = IllegalArgumentException::class) fun cachedIdentityCannotForgeCaptureTime() {
        val d = detector(); d.accept("a", 100, solid(0), 100, true)
        d.accept("a", 400, solid(0), 400, true)
    }
    @Test(expected = IllegalArgumentException::class) fun reversedObservationClockIsRejected() {
        val d = detector(); d.accept("a", 100, solid(0), 300, true)
        d.accept("a", 100, solid(0), 299, true)
    }
    @Test fun changeWaitDoesNotTreatNoChangeAsSuccess() {
        val d = detector("change")
        assertFalse(d.accept("a", 100, solid(0)))
        assertFalse(d.accept("b", 1000, solid(0)))
        assertTrue(d.accept("c", 1100, solid(0xffffff)))
    }
    @Test fun changeThenStableRequiresBothStages() {
        val d = detector("change_then_stable")
        assertFalse(d.accept("a", 100, solid(0)))
        assertFalse(d.accept("b", 500, solid(0)))
        assertFalse(d.accept("c", 600, solid(0xffffff)))
        assertFalse(d.accept("d", 849, solid(0xffffff)))
        assertTrue(d.accept("e", 850, solid(0xffffff)))
        assertTrue(d.changed)
    }
    @Test fun continuousAnimationNeverBecomesStable() {
        val d = detector()
        repeat(50) { assertFalse(d.accept("$it", it * 100L, solid(if (it % 2 == 0) 0 else 0xffffff))) }
    }
    @Test fun anAnchorDetectsAccumulatedSlowMovement() {
        val d = detector(tolerance = 12)
        repeat(5) { assertFalse(d.accept("$it", it * 100L, solid(it * 5))) }
        assertEquals(100L, d.quietMs)
    }
    @Test fun selectedRegionIgnoresAnimationOutsideIt() {
        val d = detector(region = VisualRegion(0.0, 0.0, 0.5, 1.0))
        val half = VisualSample(IntArray(4096) { if (it % 64 < 32) 0 else 0xffffff })
        assertFalse(d.accept("a", 0, half))
        assertTrue(d.accept("b", 250, half))
        assertEquals(0.0, d.baselineRatio, 0.0)
    }
    @Test fun changesInsideRegionAreDetected() {
        val d = detector("change", VisualRegion(0.5, 0.0, 0.5, 1.0))
        val half = VisualSample(IntArray(4096) { if (it % 64 < 32) 0 else 0xffffff })
        assertTrue(d.accept("a", 0, half))
        assertEquals(1.0, d.baselineRatio, 0.0)
    }
    @Test fun channelToleranceDoesNotHideColorOnlyChanges() {
        assertEquals(1.0, solid(0xff0000).difference(solid(0x00ff00), VisualRegion(), 12), 0.0)
        assertEquals(0.0, solid(0).difference(solid(12), VisualRegion(), 12), 0.0)
        assertEquals(1.0, solid(0).difference(solid(13), VisualRegion(), 12), 0.0)
    }
    @Test fun fractionThresholdIncludesTheBoundary() {
        val d = VisualChangeDetector(solid(0), VisualWaitOptions("change", changeRatio = 0.5))
        assertTrue(d.accept("a", 0, VisualSample(IntArray(4096) { if (it < 2048) 0xffffff else 0 })))
    }
    @Test fun newFrameModeHasNoMandatoryStabilityDelay() {
        assertTrue(detector("new_frame").accept("a", 0, solid(0)))
    }
    @Test(expected = IllegalArgumentException::class) fun reversedCaptureClockIsRejected() {
        val d = detector(); d.accept("a", 100, solid(0)); d.accept("b", 99, solid(0))
    }
    @Test fun invalidWaitOptionsAreRejected() {
        val invalid = listOf<() -> Any>({ VisualWaitOptions("unknown") },
            { VisualWaitOptions("stable", timeoutMs = 15001) }, { VisualWaitOptions("stable", stableMs = 6000) },
            { VisualWaitOptions("stable", intervalMs = 0) }, { VisualWaitOptions("stable", changeRatio = Double.NaN) },
            { VisualWaitOptions("stable", stableRatio = 0.03) }, { VisualRegion(width = 0.001) },
            { VisualRegion(left = 0.6, width = 0.5) }, { VisualRegion(top = Double.NaN) })
        for (f in invalid) { try { f(); fail("Invalid configuration accepted") } catch (_: IllegalArgumentException) {} }
    }
    @Test fun packedRgbaSamplingMatchesUncompressedRgbAndIgnoresAlpha() {
        val file = File.createTempFile("visual-sample", ".rgba")
        try {
            file.writeBytes(ByteArray(64 * 64 * 4) { when (it % 4) { 0 -> 255.toByte(); 1 -> 7; 2 -> 9; else -> 0 } })
            val meta = JSONObject().put("format", "rgba8888").put("width", 64).put("height", 64)
                .put("pixel_stride", 4).put("row_stride", 256).put("byte_count", file.length())
            val sample = VisualSample.raw(RawScreenFrame.read(meta, file), file)
            assertEquals(0.0, sample.difference(solid(0xff0709), VisualRegion(), 0), 0.0)
        } finally { file.delete() }
    }
}
