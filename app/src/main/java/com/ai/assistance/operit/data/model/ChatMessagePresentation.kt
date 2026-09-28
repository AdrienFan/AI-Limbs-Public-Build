package com.ai.assistance.operit.data.model

import org.json.JSONObject

enum class ChatMessageSemanticTone(val wireName: String) {
    NEUTRAL("neutral"),
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    DANGER("danger"),
}

object ChatMessagePresentation {
    fun semanticTone(rawJson: String): ChatMessageSemanticTone? {
        val raw = rawJson.trim()
        if (raw.isEmpty()) return null
        val tone =
            runCatching { JSONObject(raw).optString("semantic_tone").trim().lowercase() }
                .getOrNull()
                ?: return null
        return ChatMessageSemanticTone.entries.firstOrNull { it.wireName == tone }
    }

    fun normalize(rawJson: String?): String {
        val tone = semanticTone(rawJson.orEmpty()) ?: return ""
        return JSONObject().put("semantic_tone", tone.wireName).toString()
    }

    fun normalize(json: JSONObject?): String =
        if (json == null) "" else normalize(json.toString())
}
