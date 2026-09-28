package com.ai.assistance.operit.plugins.center

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal object HostAttentionRegistry {
    private const val MAX_GROUPS = 8
    private const val MAX_ITEMS_PER_GROUP = 16
    private const val MAX_ID_LENGTH = 64
    private const val MAX_LABEL_LENGTH = 96
    private val allowedSemanticTones = setOf("neutral", "info", "success", "warning", "danger")
    private val sources = ConcurrentHashMap<String, String>()

    fun publish(ownerPluginId: String, parameters: JSONObject): JSONObject {
        val source = ownerPluginId.trim().lowercase()
        require(source.isNotEmpty()) { "owner plugin id is required" }

        val label =
            parameters.optString("label").trim().takeIf { it.isNotEmpty() }
                ?.also { require(it.length <= MAX_LABEL_LENGTH) { "attention label is too long" } }
                ?: source
        val inputGroups = parameters.optJSONArray("groups") ?: JSONArray()
        require(inputGroups.length() <= MAX_GROUPS) {
            "attention groups exceed limit $MAX_GROUPS"
        }

        val outputGroups = JSONArray()
        val groupIds = mutableSetOf<String>()
        for (groupIndex in 0 until inputGroups.length()) {
            val inputGroup = inputGroups.optJSONObject(groupIndex) ?: continue
            val groupId = normalizedId(inputGroup.optString("id"), "group")
            require(groupIds.add(groupId)) { "duplicate attention group id: $groupId" }
            val groupLabel = requiredLabel(inputGroup.optString("label"), "group")
            val inputItems = inputGroup.optJSONArray("items") ?: JSONArray()
            require(inputItems.length() <= MAX_ITEMS_PER_GROUP) {
                "attention items exceed limit $MAX_ITEMS_PER_GROUP"
            }

            val outputItems = JSONArray()
            val itemIds = mutableSetOf<String>()
            for (itemIndex in 0 until inputItems.length()) {
                val inputItem = inputItems.optJSONObject(itemIndex) ?: continue
                val count = inputItem.optInt("count", 0)
                if (count <= 0) continue

                val itemId = normalizedId(inputItem.optString("id"), "item")
                require(itemIds.add(itemId)) {
                    "duplicate attention item id in $groupId: $itemId"
                }
                val itemLabel = requiredLabel(inputItem.optString("label"), "item")
                val tone =
                    inputItem.optString("semantic_tone").trim().lowercase().takeIf { it.isNotEmpty() }
                if (tone != null) {
                    require(tone in allowedSemanticTones) {
                        "unsupported attention semantic_tone: $tone"
                    }
                }

                val outputItem =
                    JSONObject()
                        .put("id", itemId)
                        .put("label", itemLabel)
                        .put("count", count)
                if (tone != null) outputItem.put("semantic_tone", tone)
                outputItems.put(outputItem)
            }

            if (outputItems.length() > 0) {
                outputGroups.put(
                    JSONObject()
                        .put("id", groupId)
                        .put("label", groupLabel)
                        .put("items", outputItems)
                )
            }
        }

        if (outputGroups.length() == 0) {
            sources.remove(source)
            return JSONObject()
                .put("success", true)
                .put("active", false)
                .put("source", source)
        }

        val canonical =
            JSONObject()
                .put("source", source)
                .put("label", label)
                .put("groups", outputGroups)
        sources[source] = canonical.toString()
        return JSONObject()
            .put("success", true)
            .put("active", true)
            .put("source", source)
    }

    fun clear(ownerPluginId: String): JSONObject {
        val source = ownerPluginId.trim().lowercase()
        require(source.isNotEmpty()) { "owner plugin id is required" }
        val removed = sources.remove(source) != null
        return JSONObject()
            .put("success", true)
            .put("cleared", removed)
            .put("source", source)
    }

    fun sidebandOrNull(): JSONObject? {
        val ordered =
            sources.entries
                .sortedBy { it.key }
                .map { JSONObject(it.value) }
        if (ordered.isEmpty()) return null
        return JSONObject().put("sources", JSONArray(ordered))
    }

    fun attachTo(result: JSONObject): JSONObject {
        val attention = sidebandOrNull()
        if (attention == null) {
            result.remove("attention")
        } else {
            result.put("attention", attention)
        }
        return result
    }

    private fun normalizedId(raw: String, kind: String): String {
        val value = raw.trim().lowercase()
        require(value.isNotEmpty()) { "attention $kind id is required" }
        require(value.length <= MAX_ID_LENGTH) { "attention $kind id is too long" }
        require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
            "attention $kind id contains unsupported characters"
        }
        return value
    }

    private fun requiredLabel(raw: String, kind: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "attention $kind label is required" }
        require(value.length <= MAX_LABEL_LENGTH) { "attention $kind label is too long" }
        return value
    }
}
