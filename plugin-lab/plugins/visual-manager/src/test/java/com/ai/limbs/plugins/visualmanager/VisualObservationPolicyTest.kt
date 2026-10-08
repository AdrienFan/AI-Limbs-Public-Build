package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualObservationPolicyTest {
    @Test fun uiRetainsExistingDefaultsAndDynamicAllowsOnePercentMotion() {
        assertEquals(0.0, VisualObservationPolicy.options(JSONObject(), "stable").stableRatio, 0.0)
        val p = JSONObject().put("scene_profile", "dynamic")
        assertEquals(0.01, VisualObservationPolicy.options(p, "stable").stableRatio, 0.0)
        assertEquals(0.0, VisualObservationPolicy.options(p.put("stable_ratio", 0.0), "stable").stableRatio, 0.0)
    }
    @Test fun defaultChangeRatioIsUsedAndInvalidExplicitThresholdIsExplained() {
        assertEquals(0.02, VisualObservationPolicy.options(JSONObject().put("stable_ratio", 0.01), "stable").changeRatio, 0.0)
        try {
            VisualObservationPolicy.options(JSONObject().put("stable_ratio", 0.02), "stable")
            fail("An equal threshold must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!, error.message!!.contains("smaller than change_ratio (0.02)"))
        }
    }
    @Test fun dynamicDefaultsCannotOverrideAnExplicitSmallerChangeThreshold() {
        try {
            VisualObservationPolicy.options(JSONObject().put("scene_profile", "dynamic").put("change_ratio", 0.005), "stable")
            fail("Invalid explicit thresholds must remain an error")
        } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("stable_ratio")) }
    }
    @Test fun geometryChangeStartsANewQuietWindowWithoutComparingDifferentLayouts() {
        val sample = VisualSample(IntArray(4096) { 0x336699 })
        val d = VisualChangeDetector(sample, VisualWaitOptions("change_then_stable"), initialChange = true)
        assertFalse(d.accept("landscape", 100, sample, 100, true))
        assertTrue(d.changed)
        assertEquals(0.0, d.baselineRatio, 0.0)
        assertFalse(d.accept("landscape", 100, sample, 349, true))
        assertTrue(d.accept("landscape", 100, sample, 350, true))
    }
    @Test fun dynamicStabilityStillRejectsAccumulatingOrLargeMotion() {
        val baseline = VisualSample(IntArray(4096))
        val options = VisualObservationPolicy.options(JSONObject().put("scene_profile", "dynamic"), "stable")
        val d = VisualChangeDetector(baseline, options)
        val small = VisualSample(IntArray(4096) { if (it < 20) 0xffffff else 0 })
        assertFalse(d.accept("a", 0, baseline))
        assertTrue(d.accept("b", 250, small))
        val large = VisualSample(IntArray(4096) { if (it < 100) 0xffffff else 0 })
        assertFalse(d.accept("c", 300, large))
    }
}
