package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** The compositor reads resolved cels, not timeline labels or playback timing settings. */
internal object ArtEditorPixels {
    fun playbackCanBorrow(snapshot: JSONObject): Boolean {
        val state = snapshot.getJSONObject("state")
        if (ArtAnimation.settings(state).getBoolean("onion")) return false
        val selected = state.optString("selectedLayerId")
        return ArtAnimation.layers(state).none { layer ->
            layer.getString("id") == selected && layer.getString("kind") == "colorize" &&
                layer.getJSONObject("colorize").getJSONObject("settings").getBoolean("editKeys")
        }
    }

    fun key(snapshot: JSONObject): String {
        val state = snapshot.getJSONObject("state")
        val settings = ArtAnimation.settings(state)
        val onion = settings.getBoolean("onion") && ArtAnimation.hasTracks(state)
        val layers = JSONArray()
        var colorizeSelection = ""
        for (layer in ArtAnimation.layers(state)) {
            if (layer.getString("kind") == "colorize" &&
                layer.getString("id") == state.optString("selectedLayerId"))
                colorizeSelection = layer.getString("id")
            val projected = JSONObject()
            layer.keys().forEach { field ->
                if (field !in setOf("name", "locked", "label") &&
                    (field != "animationKeys" || onion)) projected.put(field, layer.get(field))
            }
            layers.put(projected)
        }
        val input = JSONObject().put("documentId", snapshot.getString("id"))
            .put("width", state.getInt("width")).put("height", state.getInt("height"))
            .put("background", state.getString("background")).put("layers", layers)
            .put("colorizeSelection", colorizeSelection).put("onion", onion)
            .put("referenceAssets", JSONArray(ArtReferences.items(state)
                .map { it.getString("asset") }.distinct().sorted()))
        if (onion) input.put("onionTime", settings.getInt("current"))
        return ArtDocumentDigest.of(input.toString())
    }
}
