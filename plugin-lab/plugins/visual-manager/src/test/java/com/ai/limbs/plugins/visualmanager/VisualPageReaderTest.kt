package com.ai.limbs.plugins.visualmanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualPageReaderTest {
    @Test fun compactObservationRetainsEveryNodeAndTextInTheImmutableSnapshot() {
        val reader = VisualPageReader()
        val children = JSONArray()
        repeat(250) { i -> children.put(JSONObject().put("bounds", "[0,0][100,100]")
            .put("className", "android.view.View").apply {
                if (i % 10 == 0) put("text", "内容$i")
                if (i == 249) put("isClickable", true)
            }) }
        val source = JSONObject().put("packageName", "test.app")
            .put("uiElements", JSONObject().put("children", children))
        val compact = reader.capture(source, compact = true)
        val id = compact.getString("snapshot_id")
        assertEquals(251, compact.getInt("node_count"))
        assertEquals(26, compact.getInt("nodes_total"))
        assertTrue(compact.getBoolean("nodes_has_more"))
        assertTrue(compact.toString().length < 3500)
        val ids = mutableSetOf<String>()
        compact.getJSONArray("nodes").let { nodes ->
            repeat(nodes.length()) { ids.add(nodes.getJSONObject(it).getString("node_id")) }
        }
        var offset = compact.getInt("nodes_next_offset")
        do {
            val page = reader.readNodes(JSONObject().put("snapshot_id", id).put("scope", "semantic")
                .put("offset", offset).put("limit", 5))
            val nodes = page.getJSONArray("nodes")
            repeat(nodes.length()) { assertTrue(ids.add(nodes.getJSONObject(it).getString("node_id"))) }
            val more = page.getBoolean("nodes_has_more")
            if (more) offset = page.getInt("nodes_next_offset")
        } while (more)
        assertEquals(26, ids.size)
        val all = reader.readNodes(JSONObject().put("snapshot_id", id).put("offset", 200).put("limit", 100))
        assertEquals(51, all.getJSONArray("nodes").length())
        children.getJSONObject(0).put("text", "修改后")
        assertEquals("内容0", reader.read(JSONObject().put("snapshot_id", id).put("node_id", "0.0")).getString("text"))
        assertEquals(251, reader.capture(source).getJSONArray("nodes").length())
    }
    @Test fun compactResultsDeclareBudgetContinuationForDeepIdsAndLongLabels() {
        val reader = VisualPageReader()
        var root = JSONObject().put("text", "🥰".repeat(100))
        repeat(100) { root = JSONObject().put("text", "长文字".repeat(100)).put("children", JSONArray().put(root)) }
        val result = reader.capture(JSONObject().put("uiElements", root), compact = true)
        assertTrue(result.getBoolean("nodes_has_more"))
        assertTrue(result.getInt("nodes_returned") in 1..8)
        assertEquals(result.getInt("nodes_returned"), result.getInt("nodes_next_offset"))
        assertTrue(result.getJSONArray("nodes").toString().length < 1900)
    }
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
            val page = reader.read(JSONObject().put("snapshot_id", id).put("node_id", "0").put("offset", offset).put("limit", 3000))
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
            .put("node_id", "0").put("limit", 1))
        assertEquals("🥰", page.getString("text"))
        assertEquals(2, page.getInt("next_offset"))
    }
    @Test fun declaredLimitAndContentDescriptionAreHonored() {
        val reader = VisualPageReader()
        val snapshot = reader.capture(JSONObject().put("uiElements", JSONObject().put("text", "x".repeat(15000))
            .put("contentDesc", "完整描述")))
        val id = snapshot.getString("snapshot_id")
        val page = reader.read(JSONObject().put("snapshot_id", id).put("node_id", "0").put("limit", 12000))
        assertEquals(12000, page.getString("text").length)
        assertEquals(12000, page.getInt("next_offset"))
        val description = reader.read(JSONObject().put("snapshot_id", id).put("node_id", "0").put("field", "content_description"))
        assertEquals("完整描述", description.getString("text"))
    }
}
