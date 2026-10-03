package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Response controls never enter replay parameters. Existing callers still receive full results. */
internal object ArtCapabilityReply {
    private val ui = setOf("view.set", "view.tool_options", "view.zoom_tool", "view.zoom",
        "view.command", "view.presentation", "quick_tools.use")
    fun supports(name: String) = name !in ui && name !in setOf(
        "document.summary", "document.snapshot.read", "document.operation.status")
    fun tracksRequest(name: String) = name in setOf("animation.configure", "animation.keyframe", "animation.seek", "svg.apply")

    fun mode(parameters: JSONObject): String = ArtSvgReceipt.mode(parameters)

    fun format(result: JSONObject, mode: String): JSONObject {
        require(mode in setOf("full", "receipt")) { "responseMode必须为full或receipt" }
        if (mode == "full" || !result.has("state") || !result.has("operations")) return result
        val receipt = JSONObject()
        // Geometry, historical parameters and branch records can each exceed a Wire frame.
        result.keys().forEach { key ->
            if (key !in setOf("state", "operations", "timeline", "otherBranches")) receipt.put(key, result.get(key))
        }
        return receipt.put("responseMode", "receipt").put("snapshotOmitted", true)
            .put("documentId", result.getString("id"))
            .put("historyWritten", result.has("lastOperationId"))
            .put("readStateWith", "plugin.art.studio.document.snapshot.read")
            .put("readSummaryWith", "plugin.art.studio.document.summary")
    }

    fun summary(snapshot: JSONObject): JSONObject {
        val state = snapshot.getJSONObject("state")
        val result = format(snapshot, "receipt")
        for (key in listOf("name", "width", "height", "selectedLayerId"))
            if (state.has(key)) result.put(key, state.get(key))
        // Counts stay small even when a layer contains hundreds of cels or vector objects.
        result.put("layerCount", state.getJSONArray("layers").length())
        state.optJSONObject("animation")?.let { animation ->
            val settings = JSONObject()
            for (key in listOf("fps", "start", "end", "current", "loop", "onion"))
                if (animation.has(key)) settings.put(key, animation.get(key))
            result.put("animation", settings)
        }
        return result
    }

    fun page(snapshot: JSONObject, documentId: String, revision: Int, offset: Int, limit: Int): JSONObject {
        require(snapshot.getString("id") == documentId) { "工程已经切换，请重新读取摘要" }
        require(snapshot.getInt("revision") == revision) { "工程版本已改变，请从第一页重新读取" }
        require(limit in 2..16384) { "limit必须为2–16384个UTF-16字符" }
        val source = snapshot.toString()
        require(offset in 0..source.length && (offset == source.length || !source[offset].isLowSurrogate())) {
            "offset必须位于完整字符边界；续页使用nextOffset"
        }
        var end = minOf(source.length, offset + limit)
        if (end < source.length && source[end - 1].isHighSurrogate()) end--
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("documentId", documentId).put("revision", revision)
            .put("offset", offset).put("nextOffset", if (end < source.length) end else JSONObject.NULL)
            .put("totalChars", source.length).put("sha256", digest).put("complete", end == source.length)
            .put("source", source.substring(offset, end))
    }
}

internal object ArtOperationReceipt {
    fun validate(requestId: String): String {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
            "requestId必须为小写标准UUID；每次逻辑编辑生成一个新的UUID"
        }
        return requestId
    }

    fun status(doc: JSONObject, requestId: String): JSONObject {
        validate(requestId)
        val operations = doc.getJSONArray("operations")
        val result = JSONObject().put("documentId", doc.getString("id")).put("requestId", requestId)
            .put("documentRevision", operations.length()).put("status", "not_found")
        for (index in 0 until operations.length()) {
            val operation = operations.getJSONObject(index)
            if (operation.optString("requestId") == requestId) {
                result.put("status", "committed").put("revision", index + 1)
                    .put("operationId", operation.getString("id"))
                for (key in listOf("actor", "type", "timestamp")) result.put(key, operation.get(key))
                break
            }
        }
        return result
    }

    fun requireUnused(doc: JSONObject, requestId: String) {
        require(status(doc, requestId).getString("status") == "not_found") {
            "此requestId已提交；请读取document.operation.status，不要重复执行"
        }
    }
}
