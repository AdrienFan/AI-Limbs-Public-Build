package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualModeSelectorTest {
    private fun node(cls: String, text: String = "", bounds: String = "[0,0][100,100]") =
        JSONObject().put("className", cls).put("text", text).put("bounds", bounds)
    @Test fun readableNativeAndAccessibleCustomContentPreferUi() {
        for (cls in listOf("android.widget.TextView", "custom.Canvas")) {
            assertEquals("ui", VisualModeSelector.select(listOf(node(cls, "可读内容"))).getString("mode"))
        }
    }
    @Test fun largeUnlabeledGameSurfaceWithNativeCloseOverlayPrefersPixels() {
        val selection = VisualModeSelector.select(listOf(node("android.widget.FrameLayout"),
            node("android.view.SurfaceView"), node("android.widget.Button", "关闭", "[80,0][100,20]")))
        assertEquals("visual", selection.getString("mode"))
        assertEquals("LARGE_SURFACE_WITH_SPARSE_SEMANTICS", selection.getString("reason"))
        assertEquals(1.0, selection.getDouble("surface_coverage"), 0.0)
    }
    @Test fun smallVideoInReadableNativePageDoesNotHideTheUi() {
        val selection = VisualModeSelector.select(listOf(node("android.widget.FrameLayout"),
            node("android.view.TextureView", bounds = "[0,0][100,30]"), node("android.widget.TextView", "文章")))
        assertEquals("ui", selection.getString("mode"))
    }
    @Test fun surfaceWithVirtualAccessibleRegionsCanPreferUi() {
        val selection = VisualModeSelector.select(listOf(node("android.widget.FrameLayout"), node("android.view.SurfaceView"),
            node("android.view.View", "一", "[0,0][20,20]"), node("android.view.View", "二", "[20,0][40,20]"),
            node("android.view.View", "三", "[40,0][60,20]")))
        assertEquals("ui", selection.getString("mode"))
    }
    @Test fun sparseCustomViewIsMarkedHeuristicAndCanBeOverridden() {
        val automatic = VisualModeSelector.select(listOf(node("custom.GameCanvas")))
        assertEquals("visual", automatic.getString("mode")); assertEquals("heuristic", automatic.getString("classification"))
        val explicit = VisualModeSelector.select(listOf(node("custom.GameCanvas")), "ui")
        assertEquals("ui", explicit.getString("mode")); assertEquals("visual", explicit.getString("recommended_mode"))
    }
    @Test fun hiddenLabelsAndInvalidBoundsCannotCreateNativeEvidence() {
        val hidden = node("android.widget.TextView", "隐藏").put("isVisibleToUser", false)
        val selection = VisualModeSelector.select(listOf(node("custom.View", bounds = "bad"), hidden))
        assertEquals("visual", selection.getString("mode")); assertEquals(0, selection.getInt("labeled_node_count"))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyTreeIsAnErrorNotAVisualFallback() {
        VisualModeSelector.select(emptyList())
    }
}
