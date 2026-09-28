package com.ai.assistance.operit.plugins.center

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAttentionRegistryTest {
    @Test
    fun publishIsSparseAndSourceIdentityComesFromHost() {
        val owner = "plugin.test.attention"
        HostAttentionRegistry.clear(owner)

        val result =
            HostAttentionRegistry.publish(
                owner,
                JSONObject()
                    .put("label", "Test Plugin")
                    .put(
                        "groups",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "unread")
                                .put("label", "Unread")
                                .put(
                                    "items",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("id", "urgent")
                                                .put("label", "Urgent")
                                                .put("count", 2)
                                                .put("semantic_tone", "danger")
                                        )
                                        .put(
                                            JSONObject()
                                                .put("id", "normal")
                                                .put("label", "Normal")
                                                .put("count", 0)
                                        )
                                )
                        )
                    )
            )

        assertTrue(result.getBoolean("active"))
        val sources = HostAttentionRegistry.sidebandOrNull()!!.getJSONArray("sources")
        val source =
            (0 until sources.length())
                .map { sources.getJSONObject(it) }
                .first { it.getString("source") == owner }
        assertEquals(owner, source.getString("source"))
        val items = source.getJSONArray("groups").getJSONObject(0).getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals("urgent", items.getJSONObject(0).getString("id"))
        assertEquals(2, items.getJSONObject(0).getInt("count"))

        HostAttentionRegistry.clear(owner)
        val remaining = HostAttentionRegistry.sidebandOrNull()
        if (remaining != null) {
            assertFalse(
                (0 until remaining.getJSONArray("sources").length())
                    .map { remaining.getJSONArray("sources").getJSONObject(it) }
                    .any { it.getString("source") == owner }
            )
        }
    }

    @Test
    fun onlyZeroCountsDoNotCreateAttention() {
        val owner = "plugin.test.attention.zero"
        HostAttentionRegistry.clear(owner)
        val result =
            HostAttentionRegistry.publish(
                owner,
                JSONObject()
                    .put(
                        "groups",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "pending")
                                .put("label", "Pending")
                                .put(
                                    "items",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("id", "later")
                                            .put("label", "Later")
                                            .put("count", 0)
                                    )
                                )
                        )
                    )
            )
        assertFalse(result.getBoolean("active"))
        val remaining = HostAttentionRegistry.sidebandOrNull()
        if (remaining != null) {
            assertFalse(
                (0 until remaining.getJSONArray("sources").length())
                    .map { remaining.getJSONArray("sources").getJSONObject(it) }
                    .any { it.getString("source") == owner }
            )
        }
    }
}
