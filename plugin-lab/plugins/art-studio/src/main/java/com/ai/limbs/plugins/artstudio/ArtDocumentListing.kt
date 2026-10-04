package com.ai.limbs.plugins.artstudio

import org.json.JSONObject

/** Read-only name/dimension projection. Geometry validation belongs to opening that document. */
internal object ArtDocumentListing {
    fun read(doc: JSONObject): JSONObject {
        val base = doc.getJSONObject("base")
        var name = base.optString("name", "未命名工程")
        var width = base.getInt("width")
        var height = base.getInt("height")
        val operations = doc.getJSONArray("operations")
        val applied = ArtHistory.stacks(operations).first.toHashSet()
        for (i in 0 until operations.length()) {
            val event = operations.getJSONObject(i)
            if (event.getString("id") !in applied) continue
            when (event.getString("type")) {
                "DOCUMENT_RENAME" -> {
                    name = event.getJSONObject("parameters").getString("name").trim().take(100)
                    require(name.isNotBlank()) { "工程名称不能为空" }
                }
                "CROP", "CANVAS_RESIZE" -> {
                    val parameters = event.getJSONObject("parameters")
                    width = parameters.getInt("width")
                    height = parameters.getInt("height")
                }
            }
        }
        return JSONObject().put("id", doc.getString("id")).put("name", name)
            .put("width", width).put("height", height)
    }
}
