package com.ai.limbs.plugins.visualmanager

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Immutable, bounded page snapshots shared by presentation and AI capabilities. */
internal class VisualPageReader {
    private data class Node(val id: String, val data: JSONObject)
    private data class Snapshot(
        val id: String, val packageName: String, val activityName: String,
        val createdAt: Long, val nodes: List<Node>, val text: String, val mode: JSONObject
    )
    private val snapshots = LinkedHashMap<String, Snapshot>()

    @Synchronized
    fun capture(data: JSONObject, compact: Boolean = false): JSONObject {
        expire()
        require(data.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Page snapshot exceeds 4 MiB; use a smaller visible page"
        }
        val nodes = mutableListOf<Node>()
        fun visit(node: JSONObject, path: String, depth: Int = 0) {
            require(depth <= 128 && nodes.size < 10000) { "页面节点数量或深度超过上限" }
            // Copy attributes once; serializing the whole remaining subtree at every depth is quadratic.
            val attributes = JSONObject()
            node.keys().forEach { key -> if (key != "children") attributes.put(key, node.get(key)) }
            nodes.add(Node(path, JSONObject(attributes.toString())))
            val children = node.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) visit(children.getJSONObject(i), path + "." + i, depth + 1)
        }
        visit(data.getJSONObject("uiElements"), "0")
        val text = buildString {
            nodes.forEach { node ->
                val value = node.data.optString("text", "")
                val description = node.data.optString("contentDesc", "")
                if (value.isNotEmpty()) { append(value); append('\n') }
                if (description.isNotEmpty() && description != value) {
                    append(description); append('\n')
                }
            }
        }
        val snapshot = Snapshot(UUID.randomUUID().toString(), data.optString("packageName"),
            data.optString("activityName"), System.currentTimeMillis(), nodes, text,
            VisualModeSelector.select(nodes.map { it.data }))
        snapshots[snapshot.id] = snapshot
        while (snapshots.size > MAX_SNAPSHOTS) snapshots.remove(snapshots.keys.first())
        if (compact) {
            val page = nodePage(snapshot, "semantic", 0, 8, true)
            return metadata(snapshot).apply {
                for (key in listOf("nodes", "nodes_scope", "nodes_total", "nodes_returned", "nodes_has_more", "nodes_next_offset"))
                    put(key, page.get(key))
                put("nodes_compact", true).put("nodes_read_capability", VISUAL_PLUGIN_ID + ".page.nodes")
                put("read_capability", VISUAL_PLUGIN_ID + ".page.text")
            }
        }
        val summaries = JSONArray()
        nodes.forEach { summaries.put(summary(it, false)) }
        return metadata(snapshot).put("nodes", summaries)
            .put("read_capability", VISUAL_PLUGIN_ID + ".page.text")
    }

    @Synchronized
    fun readNodes(parameters: JSONObject): JSONObject {
        expire()
        val id = parameters.getString("snapshot_id")
        val snapshot = snapshots[id] ?: error("Page snapshot expired; inspect the page again")
        return nodePage(snapshot, parameters.optString("scope", "all"),
            parameters.optInt("offset", 0), parameters.optInt("limit", 20), false)
    }

    private fun nodePage(snapshot: Snapshot, scope: String, offset: Int, limit: Int, compact: Boolean): JSONObject {
        require(scope in setOf("all", "semantic")) { "Unknown node scope" }
        require(limit in 1..100) { "Node limit must be between 1 and 100" }
        val nodes = if (scope == "all") snapshot.nodes else snapshot.nodes.filter { node ->
            node.data.optString("text").isNotEmpty() || node.data.optString("contentDesc").isNotEmpty() ||
                node.data.optBoolean("isClickable") || node.data.optBoolean("isEditable")
        }
        require(offset in 0..nodes.size) { "Node offset is outside the snapshot" }
        val result = JSONArray()
        var end = offset
        var chars = 0
        while (end < nodes.size && end - offset < limit) {
            val node = summary(nodes[end], compact)
            val size = node.toString().length
            if (compact && result.length() > 0 && chars + size > 1800) break
            result.put(node); chars += size; end++
        }
        return JSONObject().put("snapshot_id", snapshot.id).put("nodes", result)
            .put("nodes_scope", scope).put("nodes_total", nodes.size).put("nodes_returned", result.length())
            .put("nodes_has_more", end < nodes.size)
            .put("nodes_next_offset", if (end < nodes.size) end else JSONObject.NULL)
    }

    private fun summary(node: Node, compact: Boolean): JSONObject {
        val value = node.data.optString("text", "")
        val description = node.data.optString("contentDesc", "")
        val result = JSONObject().put("node_id", node.id).put("bounds", node.data.optString("bounds", ""))
            .put("clickable", node.data.optBoolean("isClickable"))
            .put("text_chars", value.length).put("content_description_chars", description.length)
        if (!compact) result.put("class_name", node.data.optString("className", ""))
            .put("resource_id", node.data.optString("resourceId", ""))
        if (!compact || value.isNotEmpty()) result.put("text_preview", preview(value))
        if (!compact || description.isNotEmpty()) result.put("content_description_preview", preview(description))
        return result
    }

    @Synchronized
    fun read(parameters: JSONObject): JSONObject {
        expire()
        val id = parameters.getString("snapshot_id")
        val snapshot = snapshots[id] ?: error("Page snapshot expired; inspect the page again")
        val nodeId = parameters.optString("node_id", "")
        val field = parameters.optString("field", "text")
        require(field == "text" || field == "content_description") { "Unknown page text field" }
        val text = if (nodeId.isEmpty()) snapshot.text else {
            val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: error("Unknown node_id")
            node.data.optString(if (field == "text") "text" else "contentDesc", "")
        }
        val offset = parameters.optInt("offset", 0)
        require(!parameters.has("length")) { "分页请使用公开参数 limit" }
        val length = parameters.optInt("limit", 12000)
        require(offset in 0..text.length) { "offset is outside the text" }
        require(length in 1..12000) { "limit must be between 1 and 12000" }
        require(offset == 0 || offset == text.length ||
            !(text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate())) {
            "offset splits a Unicode character"
        }
        var end = minOf(offset + length, text.length)
        if (end < text.length && end > offset &&
            text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        if (end == offset && offset < text.length) end = minOf(offset + 2, text.length)
        return JSONObject().put("snapshot_id", id).put("node_id", nodeId).put("field", field)
            .put("offset", offset).put("total_chars", text.length)
            .put("text", text.substring(offset, end)).put("has_more", end < text.length)
            .put("next_offset", if (end < text.length) end else JSONObject.NULL)
    }

    @Synchronized
    fun status(): JSONObject {
        expire()
        return JSONObject().put("count", snapshots.size)
            .put("latest_snapshot", snapshots.values.lastOrNull()?.let { metadata(it) } ?: JSONObject.NULL)
    }

    @Synchronized
    fun clear() { snapshots.clear() }

    private fun expire() {
        val now = System.currentTimeMillis()
        snapshots.entries.removeAll { now - it.value.createdAt >= TTL_MS }
    }

    private fun metadata(snapshot: Snapshot): JSONObject = JSONObject()
        .put("snapshot_id", snapshot.id).put("package_name", snapshot.packageName)
        .put("activity_name", snapshot.activityName).put("created_at_ms", snapshot.createdAt)
        .put("text_chars", snapshot.text.length).put("node_count", snapshot.nodes.size)
        .put("visual_mode", JSONObject(snapshot.mode.toString()))

    private fun preview(value: String): String {
        if (value.length <= 120) return value
        val end = if (value[119].isHighSurrogate() && value[120].isLowSurrogate()) 119 else 120
        return value.substring(0, end) + "…"
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_SNAPSHOTS = 4
        const val TTL_MS = 10 * 60 * 1000L
    }
}
