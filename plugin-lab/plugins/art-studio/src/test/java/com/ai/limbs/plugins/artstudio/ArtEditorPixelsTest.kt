package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtEditorPixelsTest {
    private fun snapshot(): JSONObject = JSONObject().put("id", "document").put("revision", 1)
        .put("state", JSONObject().put("width", 1000).put("height", 700)
            .put("background", "#FFFFFFFF").put("selectedLayerId", "paint")
            .put("animation", JSONObject().put("current", 0).put("fps", 12).put("start", 0)
                .put("end", 23).put("loop", true).put("onion", false))
            .put("layers", JSONArray().put(JSONObject().put("id", "paint").put("kind", "paint")
                .put("name", "绘画图层").put("parentId", "").put("locked", false)
                .put("visible", true).put("opacity", 1.0).put("blend", "normal")
                .put("strokes", JSONArray()))))
    private fun layer(snapshot: JSONObject) = snapshot.getJSONObject("state").getJSONArray("layers").getJSONObject(0)
    private fun copy(snapshot: JSONObject) = JSONObject(snapshot.toString())

    @Test fun playbackDoesNotBorrowEditorOnlyOverlays() {
        val source = snapshot()
        assertTrue(ArtEditorPixels.playbackCanBorrow(source))
        source.getJSONObject("state").getJSONObject("animation").put("onion", true)
        assertFalse(ArtEditorPixels.playbackCanBorrow(source))
        source.getJSONObject("state").getJSONObject("animation").put("onion", false)
        layer(source).put("kind", "colorize").put("colorize", JSONObject()
            .put("settings", JSONObject().put("editKeys", true)))
        assertFalse(ArtEditorPixels.playbackCanBorrow(source))
    }
    @Test fun playbackSettingsAndStaticSeeksDoNotChangePixels() {
        val before = snapshot()
        val after = copy(before).put("revision", 8)
        after.getJSONObject("state").getJSONObject("animation")
            .put("fps", 24).put("loop", false).put("start", 2).put("end", 99).put("current", 52)
        assertEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(after))
    }
    @Test fun metadataAndNativeOverlaysDoNotInvalidateTheCompositor() {
        val before = snapshot(); val after = copy(before)
        layer(after).put("name", "改名").put("locked", true)
        after.getJSONObject("state").put("name", "工程改名").put("selectedLayerId", "another")
            .put("selection", JSONObject().put("shape", "rect"))
        assertEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(after))
    }
    @Test fun celContentsOpacityAndDimensionsInvalidatePixels() {
        val before = snapshot()
        val content = copy(before)
        layer(content).put("strokes", JSONArray().put(JSONObject().put("id", "stroke").put("color", "#FF000000")))
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(content))
        val opacity = copy(before); layer(opacity).put("opacity", 0.5)
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(opacity))
        val resized = copy(before); resized.getJSONObject("state").put("width", 800)
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(resized))
    }
    @Test fun inactiveCelsOnlyAffectPixelsWhenOnionSkinsAreVisible() {
        val before = snapshot()
        layer(before).put("animationKeys", JSONArray().put(JSONObject().put("time", 0)
            .put("content", JSONObject().put("strokes", JSONArray()))))
        val after = copy(before)
        layer(after).getJSONArray("animationKeys").put(JSONObject().put("time", 20)
            .put("content", JSONObject().put("strokes", JSONArray().put("different"))))
        assertEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(after))
        before.getJSONObject("state").getJSONObject("animation").put("onion", true)
        after.getJSONObject("state").getJSONObject("animation").put("onion", true)
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(after))
        val seek = copy(before)
        seek.getJSONObject("state").getJSONObject("animation").put("current", 3)
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(seek))
    }
    @Test fun colorizeSelectionAndReferenceAssetReplacementInvalidatePixels() {
        val before = snapshot(); layer(before).put("kind", "colorize")
        val selection = copy(before); selection.getJSONObject("state").put("selectedLayerId", "another")
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(selection))
        val reference = copy(before)
        reference.getJSONObject("state").put("references", JSONArray().put(JSONObject().put("asset", "asset")))
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(reference))
        val otherDocument = copy(before).put("id", "other")
        assertNotEquals(ArtEditorPixels.key(before), ArtEditorPixels.key(otherDocument))
    }
    @Test fun layerPreviewContainsOnlyItsSubtreeAndDoesNotModifyTheDocument() {
        val source = snapshot(); val state = source.getJSONObject("state"); val rows = state.getJSONArray("layers")
        val chosen = layer(source).put("parentId", "group").put("visible", false)
            .put("animationKeys", JSONArray().put(JSONObject().put("time", 0)))
        rows.put(JSONObject().put("id", "group").put("kind", "group").put("parentId", ""))
        rows.put(JSONObject().put("id", "unrelated").put("kind", "paint").put("parentId", ""))
        val original = source.toString()
        val preview = ArtLayerPreview.snapshot("document", state, chosen)
        val result = preview.getJSONObject("state").getJSONArray("layers")
        assertEquals(1, result.length())
        assertEquals("", result.getJSONObject(0).getString("parentId"))
        assertTrue(result.getJSONObject(0).getBoolean("visible"))
        assertFalse(result.getJSONObject(0).has("animationKeys"))
        assertEquals(original, source.toString())
    }
}
