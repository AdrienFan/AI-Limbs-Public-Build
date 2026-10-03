package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Slot identity and order survive clears; only an explicit remove changes the count. */
internal object ArtQuickTools {
    const val MAX_SLOTS = 8
    fun initial(): JSONObject = JSONObject().put("schema", 1).put("revision", 0L)
        .put("slots", JSONArray().put(slot("quick-1")).put(slot("quick-2")))

    private fun slot(id: String) = JSONObject().put("id", id).put("toolId", JSONObject.NULL)

    fun validate(state: JSONObject) {
        require(state.getInt("schema") == 1 && state.getLong("revision") >= 0)
        val slots = state.getJSONArray("slots")
        require(slots.length() in 0..MAX_SLOTS)
        val ids = mutableSetOf<String>()
        for (i in 0 until slots.length()) {
            val item = slots.getJSONObject(i)
            require(item.getString("id").isNotBlank() && ids.add(item.getString("id")))
            require(item.has("toolId") && (item.isNull("toolId") || item.getString("toolId").isNotBlank()))
        }
    }

    fun find(state: JSONObject, id: String): JSONObject {
        validate(state)
        val slots = state.getJSONArray("slots")
        return (0 until slots.length()).map { slots.getJSONObject(it) }
            .firstOrNull { it.getString("id") == id } ?: error("快捷位已移除，请重新读取配置")
    }

    fun change(state: JSONObject, p: JSONObject, availableTools: Set<String>): JSONObject {
        validate(state)
        require(p.getLong("expectedConfigRevision") == state.getLong("revision")) {
            "快捷配置已改变，请重新读取 quick_tools.state"
        }
        val next = JSONObject(state.toString())
        val slots = next.getJSONArray("slots")
        when (p.getString("action")) {
            "add" -> {
                require(!p.has("slotId") && !p.has("toolId")) { "添加只追加一个空位，不指定工具或位置" }
                require(slots.length() < MAX_SLOTS) { "快捷位最多八个" }
                slots.put(slot(UUID.randomUUID().toString()))
            }
            "set" -> {
                val tool = p.getString("toolId")
                require(tool in availableTools) { "工具尚未实现或在当前设备不可用：$tool" }
                find(next, p.getString("slotId")).put("toolId", tool)
            }
            "clear" -> {
                require(!p.has("toolId"))
                find(next, p.getString("slotId")).put("toolId", JSONObject.NULL)
            }
            "remove" -> {
                require(!p.has("toolId"))
                val id = find(next, p.getString("slotId")).getString("id")
                next.put("slots", JSONArray((0 until slots.length())
                    .map { slots.getJSONObject(it) }.filterNot { it.getString("id") == id }))
            }
            else -> error("未知快捷配置操作")
        }
        next.put("revision", Math.addExact(state.getLong("revision"), 1L))
        return next
    }

    fun tool(state: JSONObject, id: String, expectedRevision: Long): String {
        validate(state)
        require(state.getLong("revision") == expectedRevision) { "快捷配置已改变，请重新读取" }
        val item = find(state, id)
        require(!item.isNull("toolId")) { "快捷位为空，请先选择工具" }
        return item.getString("toolId")
    }

    fun describe(state: JSONObject): JSONObject {
        validate(state)
        val result = JSONObject(state.toString())
        val tools = ArtToolCatalog.implemented.associateBy { it.first }
        val slots = result.getJSONArray("slots")
        for (i in 0 until slots.length()) {
            val item = slots.getJSONObject(i)
            val tool = if (item.isNull("toolId")) null else tools[item.getString("toolId")]
            item.put("available", tool != null)
            if (tool != null) item.put("label", tool.second).put("glyph", tool.third)
        }
        return result.put("maxSlots", MAX_SLOTS).put("columns", 2).put("rows", (slots.length() + 1) / 2)
            .put("stateCapability", "$ART_ID.quick_tools.state")
            .put("configureCapability", "$ART_ID.quick_tools.configure")
            .put("useCapability", "$ART_ID.quick_tools.use")
    }
}
