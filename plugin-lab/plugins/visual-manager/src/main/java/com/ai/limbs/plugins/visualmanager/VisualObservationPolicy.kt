package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

/** Explicit scene choices; caller thresholds remain authoritative and are never silently adjusted. */
internal object VisualObservationPolicy {
    fun options(p: JSONObject, mode: String): VisualWaitOptions {
        val profile = p.optString("scene_profile", "ui")
        require(profile in setOf("ui", "dynamic")) { "scene_profile must be ui or dynamic" }
        return VisualWaitOptions(mode,
            p.optLong("timeout_ms", 5000), p.optLong("stable_ms", 250), p.optLong("sample_interval_ms", 100),
            p.optDouble("change_ratio", 0.02), p.optDouble("stable_ratio", if (profile == "dynamic") 0.01 else 0.0),
            p.optInt("pixel_tolerance", 12),
            VisualRegion(p.optDouble("region_left", 0.0), p.optDouble("region_top", 0.0),
                p.optDouble("region_width", 1.0), p.optDouble("region_height", 1.0)), profile)
    }
}
