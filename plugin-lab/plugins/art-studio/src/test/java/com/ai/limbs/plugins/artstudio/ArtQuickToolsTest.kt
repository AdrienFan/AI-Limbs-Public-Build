package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtQuickToolsTest {
    private fun change(s: JSONObject, action: String, id: String? = null, tool: String? = null): JSONObject {
        val p = JSONObject().put("action", action).put("expectedConfigRevision", s.getLong("revision"))
        if (id != null) p.put("slotId", id)
        if (tool != null) p.put("toolId", tool)
        return ArtQuickTools.change(s, p, setOf("ink", "eraser"))
    }
    private fun ids(s: JSONObject): List<String> {
        val a = s.getJSONArray("slots")
        return (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    }
    @Test fun clearRetainsIdentityCountAndOtherBindings() {
        var s = ArtQuickTools.initial()
        assertEquals(2, ids(s).size)
        assertTrue(s.getJSONArray("slots").getJSONObject(0).isNull("toolId"))
        s = change(s, "set", "quick-1", "ink")
        s = change(s, "set", "quick-2", "eraser")
        val before = ids(s)
        s = change(s, "clear", "quick-1")
        assertEquals(before, ids(s))
        assertTrue(ArtQuickTools.find(s, "quick-1").isNull("toolId"))
        assertEquals("eraser", ArtQuickTools.find(s, "quick-2").getString("toolId"))
    }
    @Test fun addIsOneAtATimeAndEightIsAHardLimit() {
        var s = ArtQuickTools.initial()
        while (ids(s).size < 8) {
            val before = ids(s)
            s = change(s, "add")
            assertEquals(before.size + 1, ids(s).size)
            assertEquals(before, ids(s).dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) { change(s, "add") }
        assertEquals(8, ids(s).size)
    }
    @Test fun removeDoesNotSortCompactOrCreateReplacementSlots() {
        var s = change(ArtQuickTools.initial(), "add")
        val id = ids(s).last()
        s = change(s, "set", id, "eraser")
        s = change(s, "remove", "quick-1")
        assertEquals(listOf("quick-2", id), ids(s))
        assertTrue(ArtQuickTools.find(s, "quick-2").isNull("toolId"))
        assertEquals("eraser", ArtQuickTools.find(s, id).getString("toolId"))
        s = change(s, "remove", "quick-2")
        s = change(s, "remove", id)
        assertEquals(0, ids(s).size)
        s = change(s, "add")
        assertEquals(1, ids(s).size)
    }
    @Test fun staleConfigurationCannotOverwriteOrUseAnotherBinding() {
        val old = ArtQuickTools.initial()
        val next = change(old, "set", "quick-1", "ink")
        assertThrows(IllegalArgumentException::class.java) {
            ArtQuickTools.change(next, JSONObject().put("action", "clear").put("slotId", "quick-1")
                .put("expectedConfigRevision", old.getLong("revision")), setOf("ink"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtQuickTools.tool(next, "quick-1", old.getLong("revision"))
        }
        assertEquals("ink", ArtQuickTools.tool(next, "quick-1", next.getLong("revision")))
        assertTrue(ArtQuickTools.find(old, "quick-1").isNull("toolId"))
    }
    @Test fun emptyAndUnavailableToolsAreRejectedWithoutChangingConfiguration() {
        val s = ArtQuickTools.initial()
        assertThrows(IllegalArgumentException::class.java) { ArtQuickTools.tool(s, "quick-1", 0) }
        assertThrows(IllegalArgumentException::class.java) { change(s, "set", "quick-1", "missing") }
        assertThrows(IllegalStateException::class.java) { change(s, "clear", "missing") }
        assertEquals(0L, s.getLong("revision"))
    }
}
