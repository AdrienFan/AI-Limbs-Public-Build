package com.ai.limbs.plugins.artstudio

import org.json.JSONObject

/** Explicit response projection after canvas feedback; the default public response is unchanged. */
internal object ArtSvgReceipt {
    fun mode(parameters: JSONObject): String {
        val mode = if (parameters.has("responseMode")) parameters.getString("responseMode") else "full"
        require(mode in setOf("full", "receipt")) { "responseMode 只支持 full/receipt" }
        return mode
    }
    fun format(result: JSONObject, mode: String): JSONObject {
        if (mode == "full") return result
        require(mode == "receipt")
        val receipt = JSONObject()
        for (key in result.keys()) if (key !in setOf("state", "operations", "timeline", "otherBranches"))
            receipt.put(key, result.get(key))
        return receipt.put("responseMode", "receipt").put("snapshotOmitted", true)
            .put("documentId", result.getString("id"))
            .put("historyWritten", result.optBoolean("svgApplied", false))
            .put("readStateWith", "plugin.art.studio.document.snapshot.read")
            .put("readSummaryWith", "plugin.art.studio.document.summary")
    }
}
