package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow

/** Keep an unfinished body when the host recreates the page for fullscreen or toolbox navigation. */
internal object StudioTextInputState {
    val draft = MutableStateFlow<String?>(null)
}

/** UI drafts carry their original transaction identity; typing never writes the document. */
internal object StudioTextInputSpec {
    private val transientKeys = setOf("documentId", "expectedRevision", "id", "geometryChoices",
        "inputSession", "inputMatrix", "inputParentMatrix", "inputOriginal", "selectionStart", "selectionEnd", "cacheWidth", "cacheHeight",
        "cacheOriginX", "cacheOriginY", "layoutGlyphs")

    fun defaults(fields: JSONObject): JSONObject = ArtJsonCopy.objectValue(fields).apply {
        transientKeys.forEach { remove(it) }
        remove("x"); remove("y")
        if (optString("sourceMode") != "svg") {
            put("content", ""); put("spans", JSONArray()); put("sourceMode", "plain")
        }
    }

    fun writeParameters(draft: JSONObject): JSONObject = ArtJsonCopy.objectValue(draft).apply {
        listOf("geometryChoices", "inputSession", "inputMatrix", "inputParentMatrix", "inputOriginal", "selectionStart", "selectionEnd").forEach { remove(it) }
    }

    fun edit(draft: JSONObject, content: String, start: Int, end: Int): JSONObject {
        require(content.length <= 4096 && content.count { it == '\n' } < 128) { "文字最多4096个UTF-16单元、128段" }
        require(draft.optString("sourceMode") != "svg") { "请在文字参数中明确转换SVG源码为正文后输入" }
        return ArtJsonCopy.objectValue(draft).apply {
            val previousContent = getString("content")
            val spans = ArtRichText.edit(previousContent, content, optJSONArray("spans") ?: JSONArray())
            val mode = if (previousContent == content) optString("sourceMode", "plain") else if (spans.length() > 0) "rich" else "plain"
            put("content", content).put("spans", spans)
                .put("sourceMode", mode)
                .put("selectionStart", start.coerceIn(0, content.length))
                .put("selectionEnd", end.coerceIn(0, content.length))
        }
    }

    fun applyOptions(draft: JSONObject, fields: JSONObject): JSONObject {
        val result = ArtJsonCopy.objectValue(fields)
        // A parameter window cannot rebase a draft onto an unrelated current revision.
        for (key in listOf("documentId", "expectedRevision", "id", "inputSession", "inputMatrix", "inputParentMatrix", "inputOriginal", "selectionStart", "selectionEnd")) {
            result.remove(key)
            if (draft.has(key)) result.put(key, ArtJsonCopy.value(draft.get(key)))
        }
        return result
    }
}
