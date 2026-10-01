package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudioAssistantViewportTest {
    private fun document(id: String = "doc-a", width: Int = 1200, height: Int = 900) =
        JSONObject().put("id", id).put("state", JSONObject().put("width", width).put("height", height))

    @Test fun anOpenedDocumentHasAnAssistantViewWithoutAPhonePage() {
        ArtStudioViewControl.canvasAttached = false
        val state = ArtStudioAssistantView().describe(document())
        assertTrue(state.getBoolean("canvasAttached"))
        assertFalse(state.getBoolean("pageVisible"))
        assertEquals("assistant", state.getString("target"))
        assertEquals("doc-a", state.getJSONObject("canvasZoom").getString("documentId"))
    }

    @Test fun closingTheDocumentRemovesItsZoomAndReopeningUsesTheNewDocument() {
        val view = ArtStudioAssistantView()
        view.describe(document())
        val closed = view.describe(null)
        assertFalse(closed.getBoolean("canvasAttached"))
        assertFalse(closed.has("canvasZoom"))
        assertEquals("doc-b", view.describe(document("doc-b"))
            .getJSONObject("canvasZoom").getString("documentId"))
    }

    @Test fun canvasResizeRecomputesTheActualPreviewFitScale() {
        val view = ArtStudioAssistantView()
        val before = view.describe(document()).getJSONObject("canvasZoom").getDouble("percent")
        val after = view.describe(document(width = 2400, height = 1800))
            .getJSONObject("canvasZoom").getDouble("percent")
        assertEquals(before / 2.0, after, 0.000001)
    }

    @Test fun zoomInAndOutChangeRealDocumentToViewportScale() {
        val view = StudioAssistantViewport("doc-a", 1200, 900)
        val enlarged = view.command("zoom_in")
        assertEquals(view.scale * 1.25, enlarged.scale, 0.000001)
        assertEquals(view.scale, enlarged.command("zoom_out").scale, 0.000001)
        assertEquals(1.0, view.command("zoom_100").scale, 0.000001)
    }

    @Test fun staleDocumentAndOutOfRangeZoomAreRejected() {
        val view = StudioAssistantViewport("doc-a", 1200, 900)
        for ((id, percent) in listOf("doc-b" to 100.0, "doc-a" to Double.NaN,
            "doc-a" to Double.POSITIVE_INFINITY, "doc-a" to 0.0, "doc-a" to 100000.0)) {
            try { view.zoom(id, percent); fail("Invalid zoom was accepted") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.isNotBlank()) }
        }
        assertEquals(1.5, view.zoom("doc-a", 150.0).scale, 0.000001)
    }

    @Test fun fitUsesRotatedBoundsAndMirrorResetDoesNotChangeTheSourceDocument() {
        val view = StudioAssistantViewport("doc-a", 1200, 900)
        val rotated = view.command("rotate_right").command("mirror")
        assertEquals(15.0, rotated.rotation, 0.0)
        assertTrue(rotated.mirrored)
        val widthFit = view.copy(rotation = 90.0).command("fit_width")
        assertEquals(1024.0 / 900.0, widthFit.scale, 0.000001)
        val heightFit = view.copy(rotation = 90.0).command("fit_height")
        assertEquals(768.0 / 1200.0, heightFit.scale, 0.000001)
        assertEquals(view, rotated.command("reset_display"))
        assertEquals(rotated, rotated.command("refresh"))
        assertEquals("doc-a", rotated.documentId)
        assertEquals(1200, rotated.sourceWidth)
    }

    @Test fun zoomLimitsPreventUnboundedPreviewScale() {
        var view = StudioAssistantViewport("doc-a", 1200, 900)
        repeat(100) { view = view.command("zoom_in") }
        assertEquals(view.maxScale, view.scale, 0.0)
        repeat(100) { view = view.command("zoom_out") }
        assertEquals(view.minScale, view.scale, 0.0)
    }
}
