package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Plugin-wide color resources, independent of document history and canvas background. */
internal object ArtColorWorkspace {
    const val MAX_PALETTES = 64
    const val MAX_COLORS = 512
    fun defaults() = JSONObject().put("format", 1).put("revision", 0)
        .put("foreground", "#FF161616").put("background", "#FFFFFFFF").put("palettes", JSONArray())

    fun color(value: String): String {
        require(value.matches(Regex("#[A-Fa-f0-9]{8}"))) { "颜色须为 #AARRGGBB" }
        return value.uppercase(Locale.ROOT)
    }
    fun target(value: String): String {
        require(value in setOf("foreground", "background", "none")) { "取色目标须为 foreground/background/none" }
        return value
    }
    fun palette(state: JSONObject, id: String): JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "调色板标识无效" }
        val items = state.getJSONArray("palettes")
        return (0 until items.length()).map { items.getJSONObject(it) }
            .firstOrNull { it.getString("id") == id } ?: error("调色板不存在，请刷新列表")
    }
    fun validate(input: JSONObject): JSONObject {
        val state = JSONObject(input.toString())
        require(state.getInt("format") == 1 && state.getLong("revision") >= 0)
        state.put("foreground", color(state.getString("foreground")))
            .put("background", color(state.getString("background")))
        val items = state.getJSONArray("palettes")
        require(items.length() <= MAX_PALETTES)
        val ids = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            require(ids.add(item.getString("id"))) { "调色板标识重复" }
            palette(state, item.getString("id"))
            require(item.getString("name").trim().length in 1..64)
            val colors = item.getJSONArray("colors")
            require(colors.length() <= MAX_COLORS)
            val seen = mutableSetOf<String>()
            for (j in 0 until colors.length()) {
                val value = color(colors.getString(j))
                require(seen.add(value)) { "调色板颜色重复" }; colors.put(j, value)
            }
        }
        return state
    }
    /** Validate all destinations first; caller persists the returned workspace in one atomic write. */
    fun picked(input: JSONObject, value: String, target: String, paletteId: String?): Pair<JSONObject, Boolean> {
        val state = validate(input); val normalized = color(value); ArtColorWorkspace.target(target)
        val item = paletteId?.let { palette(state, it) }
        var added = false
        if (item != null) {
            val colors = item.getJSONArray("colors")
            if ((0 until colors.length()).none { colors.getString(it) == normalized }) {
                require(colors.length() < MAX_COLORS) { "调色板最多512色，请先整理颜色" }
                colors.put(normalized); added = true
            }
        }
        if (target != "none") state.put(target, normalized)
        return state to added
    }
    fun savePalette(input: JSONObject, p: JSONObject): JSONObject {
        val state = validate(input); val items = state.getJSONArray("palettes")
        val existing = if (p.has("id")) palette(state, p.getString("id")) else null
        val name = p.getString("name").trim(); require(name.length in 1..64) { "调色板名称须为1–64字" }
        val colors = if (p.has("colors")) {
            val raw = p.getJSONArray("colors"); require(raw.length() <= MAX_COLORS)
            JSONArray((0 until raw.length()).map { color(raw.getString(it)) }.distinct())
        } else existing?.getJSONArray("colors") ?: JSONArray()
        if (existing != null) existing.put("name", name).put("colors", colors)
        else {
            require(items.length() < MAX_PALETTES) { "调色板最多64个" }
            items.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("colors", colors))
        }
        return state
    }
    fun deletePalette(input: JSONObject, id: String): JSONObject {
        val state = validate(input); palette(state, id)
        val items = state.getJSONArray("palettes")
        state.put("palettes", JSONArray((0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.getString("id") != id }))
        return state
    }
}
