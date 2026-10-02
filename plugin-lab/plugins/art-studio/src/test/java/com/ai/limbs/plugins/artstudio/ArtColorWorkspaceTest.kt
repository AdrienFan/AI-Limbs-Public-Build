package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtColorWorkspaceTest {
    private fun board(colors: JSONArray = JSONArray()): JSONObject = ArtColorWorkspace.savePalette(
        ArtColorWorkspace.defaults(), JSONObject().put("name", "夜色").put("colors", colors))
    private fun id(state: JSONObject) = state.getJSONArray("palettes").getJSONObject(0).getString("id")
    private fun rejected(action: () -> Unit) {
        try { action(); fail("expected explicit rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun foregroundAndBackgroundAreIndependentResources() {
        val original = board()
        val (next, added) = ArtColorWorkspace.picked(original, "#ff123456", "background", id(original))
        assertTrue(added); assertEquals("#FF123456", next.getString("background"))
        assertEquals(original.getString("foreground"), next.getString("foreground"))
        assertEquals("#FFFFFFFF", original.getString("background"))
        assertEquals(0, original.getJSONArray("palettes").getJSONObject(0).getJSONArray("colors").length())
    }
    @Test fun collectOnlyPreservesBothColorsAndDeduplicatesFullArgb() {
        val original = board(JSONArray().put("#ff123456"))
        val (same, added) = ArtColorWorkspace.picked(original, "#FF123456", "none", id(original))
        assertFalse(added)
        val (next, secondAdded) = ArtColorWorkspace.picked(same, "#80123456", "none", id(same))
        assertTrue(secondAdded); assertEquals(2, next.getJSONArray("palettes").getJSONObject(0).getJSONArray("colors").length())
        assertEquals(original.getString("foreground"), next.getString("foreground"))
        assertEquals(original.getString("background"), next.getString("background"))
    }
    @Test fun paletteCapacityFailureDoesNotPartiallyChangeTarget() {
        val colors = JSONArray((0 until 512).map { String.format(java.util.Locale.ROOT, "#FF%06X", it) })
        val original = board(colors); val before = original.toString()
        rejected { ArtColorWorkspace.picked(original, "#FF112233", "foreground", id(original)) }
        assertEquals(before, original.toString())
        assertFalse(ArtColorWorkspace.picked(original, "#FF000001", "none", id(original)).second)
    }
    @Test fun invalidDestinationOrColorCannotChangeSourceResources() {
        val original = board(); val before = original.toString()
        rejected { ArtColorWorkspace.picked(original, "#FFFFFFFF", "phone", id(original)) }
        rejected { ArtColorWorkspace.picked(original, "#FFF", "foreground", id(original)) }
        try { ArtColorWorkspace.picked(original, "#FFFFFFFF", "foreground", "00000000-0000-0000-0000-000000000000"); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(before, original.toString())
    }
    @Test fun renamePreservesColorsAndExplicitEmptyArrayClears() {
        val initial = board(JSONArray().put("#FF123456"))
        val renamed = ArtColorWorkspace.savePalette(initial, JSONObject().put("id", id(initial)).put("name", "窗边"))
        assertEquals(1, renamed.getJSONArray("palettes").getJSONObject(0).getJSONArray("colors").length())
        val cleared = ArtColorWorkspace.savePalette(renamed, JSONObject().put("id", id(renamed)).put("name", "窗边").put("colors", JSONArray()))
        assertEquals(0, cleared.getJSONArray("palettes").getJSONObject(0).getJSONArray("colors").length())
        assertEquals(1, initial.getJSONArray("palettes").getJSONObject(0).getJSONArray("colors").length())
    }
    @Test fun deletionAndRoundTripKeepIndependentColorState() {
        val initial = board(); val decoded = ArtColorWorkspace.validate(JSONObject(initial.toString()))
        val deleted = ArtColorWorkspace.deletePalette(decoded, id(decoded))
        assertEquals(0, deleted.getJSONArray("palettes").length())
        assertEquals(initial.getString("background"), deleted.getString("background"))
        assertEquals(1, initial.getJSONArray("palettes").length())
    }
    private fun layer(id: String, kind: String, parent: String = "", visible: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("parentId", parent).put("visible", visible)
        .put("opacity", 0.25).put("blend", "multiply").put("x", 17).put("y", 23).put("rotation", 30).put("scale", 2)
    private fun snapshot(vararg layers: JSONObject) = JSONObject().put("state", JSONObject()
        .put("background", "#FFFFFFFF").put("layers", JSONArray(layers.toList())))
    private fun byId(s: JSONObject, id: String): JSONObject {
        val layers = s.getJSONObject("state").getJSONArray("layers")
        return (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.getString("id") == id }
    }
    @Test fun hiddenNestedLayerKeepsEveryTransformAndExcludesSiblings() {
        val original = snapshot(layer("g", "group", visible = false), layer("a", "vector", "g", false), layer("b", "paint", "g"))
        val before = original.toString(); val isolated = ArtColorSampler.isolate(original, "a")
        assertEquals(2, isolated.getJSONObject("state").getJSONArray("layers").length())
        for (id in listOf("g", "a")) {
            val node = byId(isolated, id)
            assertTrue(node.getBoolean("visible")); assertEquals(1.0, node.getDouble("opacity"), 0.0)
            assertEquals("normal", node.getString("blend"))
            for (key in listOf("x", "y", "rotation", "scale")) assertEquals(byId(original, id).getDouble(key), node.getDouble(key), 0.0)
        }
        assertEquals("#00000000", isolated.getJSONObject("state").getString("background"))
        assertEquals(before, original.toString())
    }
    @Test fun groupProjectionPreservesChildCompositingAndHiddenSubgroup() {
        val original = snapshot(layer("g", "group", visible = false), layer("a", "image", "g"),
            layer("h", "group", "g", false), layer("b", "text", "h"), layer("outside", "paint"))
        val isolated = ArtColorSampler.isolate(original, "g")
        assertEquals(4, isolated.getJSONObject("state").getJSONArray("layers").length())
        assertTrue(byId(isolated, "g").getBoolean("visible"))
        assertFalse(byId(isolated, "h").getBoolean("visible"))
        assertEquals(0.25, byId(isolated, "a").getDouble("opacity"), 0.0)
        assertEquals("multiply", byId(isolated, "a").getString("blend"))
    }
    @Test fun danglingAndCyclicParentGraphsAreRejected() {
        rejected { ArtColorSampler.isolate(snapshot(layer("a", "paint", "g"), layer("g", "group", "a")), "a") }
        try { ArtColorSampler.isolate(snapshot(layer("a", "paint", "missing")), "a"); fail() }
        catch (_: IllegalStateException) { }
        rejected { ArtColorSampler.isolate(snapshot(layer("a", "paint", "b"), layer("b", "paint")), "a") }
    }
    @Test fun discoveryExamplesSeparateReadFromPersistentPick() {
        assertFalse(ArtCapabilityHelp.example("color.sample").has("target"))
        assertEquals("background", ArtCapabilityHelp.example("color.pick").getString("target"))
        assertTrue(ArtCapabilityHelp.parameterDescription("color.set", "target").contains("foreground"))
        assertTrue(ArtCapabilityHelp.parameterDescription("color.pick", "paletteId").contains("color.palette.list"))
        assertEquals(7, ArtCapabilityHelp.names().count { it.startsWith("color.") && it != "color.sample" })
    }
}
