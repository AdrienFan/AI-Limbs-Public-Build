package com.ai.limbs.plugins.visualmanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisualPageReaderTest {
    @Test
    fun immutableSnapshotRetainsFullNodeTextAcrossUnicodePages() {
        val full = "a".repeat(2999) + "🥰" + "长文字".repeat(2000)
        val node = JSONObject().put("text", full).put("children", JSONArray())
        val reader = VisualPageReader()
        val snapshot = reader.capture(JSONObject().put("packageName", "example.app")
            .put("activityName", "Main").put("uiElements", node))
        val id = snapshot.getString("snapshot_id")
        node.put("text", "页面后来改变")
        val content = StringBuilder()
        var offset = 0
        do {
            val page = reader.read(JSONObject().put("snapshot_id", id).put("node_id", "0").put("offset", offset))
            val chunk = page.getString("text")
            assertFalse(chunk.isNotEmpty() && chunk.last().isHighSurrogate())
            content.append(chunk)
            val more = page.getBoolean("has_more")
            if (more) offset = page.getInt("next_offset")
        } while (more)
        assertEquals(full, content.toString())
    }

    @Test
    fun oneCharacterPageStillMakesProgressAcrossEmoji() {
        val reader = VisualPageReader()
        val snapshot = reader.capture(JSONObject().put("uiElements",
            JSONObject().put("text", "🥰X").put("children", JSONArray())))
        val page = reader.read(JSONObject().put("snapshot_id", snapshot.getString("snapshot_id"))
            .put("node_id", "0").put("length", 1))
        assertEquals("🥰", page.getString("text"))
        assertEquals(2, page.getInt("next_offset"))
    }
}
