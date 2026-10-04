package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudioTextInputSpecTest {
    private fun draft(): JSONObject = JSONObject().put("documentId", "doc-a").put("expectedRevision", 12)
        .put("id", "text-a").put("inputSession", "session-a").put("inputMatrix", JSONArray(listOf(1,0,0,1,20,30)))
        .put("inputParentMatrix", JSONArray(listOf(1,0,0,1,0,0)))
        .put("inputOriginal", JSONObject().put("content", "你好"))
        .put("content", "你好").put("sourceMode", "rich").put("fontId", "explicit-font").put("fontSize", 48)
        .put("boxWidth", 640).put("writingMode", "vertical-rl").put("shapeInside", JSONObject().put("d", "M0 0L100 0L100 100L0 100Z"))
        .put("spans", JSONArray().put(JSONObject().put("start", 0).put("end", 2).put("color", "#FF123456")))

    @Test fun composingAndSelectionDoNotWriteOrRebaseTheCapturedTransaction() {
        val original = draft()
        val edited = StudioTextInputSpec.edit(original, "你好世界", 4, 4)
        assertEquals("你好", original.getString("content"))
        assertEquals("你好世界", edited.getString("content"))
        assertEquals(12, edited.getInt("expectedRevision"))
        assertEquals("doc-a", edited.getString("documentId"))
        assertEquals(4, edited.getJSONArray("spans").getJSONObject(0).getInt("end"))
        val selected = StudioTextInputSpec.edit(edited, edited.getString("content"), 0, 2)
        assertEquals(0, selected.getInt("selectionStart")); assertEquals(2, selected.getInt("selectionEnd"))
        assertEquals(edited.getJSONArray("spans").toString(), selected.getJSONArray("spans").toString())
    }

    @Test fun parametersCannotRedirectADraftToAnotherDocumentOrLayer() {
        val original = draft()
        val edited = StudioTextInputSpec.applyOptions(original, JSONObject().put("documentId", "other")
            .put("expectedRevision", 99).put("id", "other-layer").put("content", "你好").put("fontSize", 72))
        assertEquals("doc-a", edited.getString("documentId")); assertEquals(12, edited.getInt("expectedRevision"))
        assertEquals("text-a", edited.getString("id")); assertEquals("session-a", edited.getString("inputSession"))
        edited.getJSONArray("inputMatrix").put(4, 500)
        assertEquals(20, original.getJSONArray("inputMatrix").getInt(4))
    }

    @Test fun defaultsRetainLayoutWithoutLeakingBodyRangesOrSessionIdentity() {
        val settings = StudioTextInputSpec.defaults(draft())
        assertFalse(settings.has("documentId")); assertFalse(settings.has("id")); assertFalse(settings.has("inputOriginal"))
        assertFalse(settings.has("inputMatrix")); assertFalse(settings.has("expectedRevision"))
        assertEquals("", settings.getString("content")); assertEquals(0, settings.getJSONArray("spans").length())
        assertEquals("vertical-rl", settings.getString("writingMode")); assertTrue(settings.has("shapeInside"))
        val write = StudioTextInputSpec.writeParameters(draft())
        assertTrue(write.has("documentId")); assertTrue(write.has("expectedRevision")); assertTrue(write.has("id"))
        assertFalse(write.has("inputSession")); assertFalse(write.has("inputOriginal")); assertFalse(write.has("inputMatrix"))
    }

    @Test fun emptyDraftAndUnicodeInputRemainEditableBeforeCommitValidation() {
        val plain = draft().put("content", "").put("spans", JSONArray()).put("sourceMode", "plain")
        val edited = StudioTextInputSpec.edit(plain, "雨夜😀\n回家", 8, 8)
        assertEquals("雨夜😀\n回家", edited.getString("content"))
        assertEquals("", StudioTextInputSpec.edit(edited, "", 0, 0).getString("content"))
    }

    @Test fun svgSourceIsNeverSilentlyConvertedByTyping() {
        val svg = draft().put("sourceMode", "svg").put("svgSource", "original-svg")
        try { StudioTextInputSpec.edit(svg, "不同正文", 0, 0); fail("SVG edit must require explicit conversion") }
        catch (_: IllegalArgumentException) {}
        assertEquals("original-svg", svg.getString("svgSource"))
    }
}
