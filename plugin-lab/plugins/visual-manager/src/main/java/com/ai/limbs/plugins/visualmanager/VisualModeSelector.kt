package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject

/** Evidence-based routing, not an app-name list or proof of a particular rendering engine. */
internal object VisualModeSelector {
    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Double get() = (right.toDouble() - left) * (bottom.toDouble() - top)
        fun contains(other: Bounds) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
        fun coverage(other: Bounds): Double =
            (minOf(right, other.right).toDouble() - maxOf(left, other.left)).coerceAtLeast(0.0) *
                (minOf(bottom, other.bottom).toDouble() - maxOf(top, other.top)).coerceAtLeast(0.0) / area
    }
    private val boundsPattern = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    private fun bounds(node: JSONObject): Bounds? {
        val values = boundsPattern.matchEntire(node.optString("bounds"))?.groupValues?.drop(1)
            ?.map { it.toIntOrNull() ?: return null } ?: return null
        return Bounds(values[0], values[1], values[2], values[3]).takeIf { it.right > it.left && it.bottom > it.top }
    }
    fun select(nodes: List<JSONObject>, requested: String = "auto"): JSONObject {
        require(requested in setOf("auto", "ui", "visual")) { "mode 必须为 auto/ui/visual" }
        require(nodes.isNotEmpty()) { "页面节点快照为空" }
        val root = bounds(nodes.first()) ?: nodes.mapNotNull { bounds(it) }.maxByOrNull { it.area }
        val visible = nodes.filter { !it.has("isVisibleToUser") || it.optBoolean("isVisibleToUser") }
        val surfaces = visible.filter { node ->
            val cls = node.optString("className").substringAfterLast('.')
            cls == "SurfaceView" || cls == "TextureView" || cls == "GLSurfaceView"
        }
        val surfaceBounds = surfaces.mapNotNull { bounds(it) }
        val labeled = visible.filter { it.optString("text").isNotBlank() || it.optString("contentDesc").isNotBlank() }
        val semanticInsideSurface = labeled.count { node ->
            val b = bounds(node)
            b != null && surfaceBounds.any { it.contains(b) } && !surfaces.contains(node)
        }
        val coverage = if (root == null) 0.0 else surfaceBounds.maxOfOrNull { root.coverage(it) } ?: 0.0
        val recommended: String
        val reason: String
        when {
            coverage >= 0.60 && semanticInsideSurface < 3 -> { recommended = "visual"; reason = "LARGE_SURFACE_WITH_SPARSE_SEMANTICS" }
            labeled.isEmpty() -> { recommended = "visual"; reason = "SPARSE_ACCESSIBILITY_CONTENT" }
            else -> { recommended = "ui"; reason = "ACCESSIBLE_CONTENT_AVAILABLE" }
        }
        return JSONObject().put("mode", if (requested == "auto") recommended else requested)
            .put("requested_mode", requested).put("recommended_mode", recommended)
            .put("reason", if (requested == "auto") reason else "EXPLICIT_MODE")
            .put("policy_version", 1).put("classification", "heuristic")
            .put("node_count", nodes.size).put("visible_node_count", visible.size)
            .put("labeled_node_count", labeled.size).put("actionable_node_count", visible.count { it.optBoolean("isClickable") })
            .put("surface_node_count", surfaces.size).put("surface_coverage", coverage)
            .put("surface_semantic_node_count", semanticInsideSurface)
            .put("coordinate_evidence", root != null)
            .put("preferred_modality", if ((if (requested == "auto") recommended else requested) == "ui") "accessibility" else "image")
    }
}
