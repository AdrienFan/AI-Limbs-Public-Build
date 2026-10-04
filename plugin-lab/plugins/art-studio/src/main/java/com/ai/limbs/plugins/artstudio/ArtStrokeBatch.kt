package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject

/** Explicit pen-up boundaries: independent strokes, never interpolated transit points. */
internal object ArtStrokeBatch {
    const val MAX_STROKES = 128
    const val MAX_SAMPLES = 20000
    const val MAX_DABS = 120000
    const val MAX_PARTICLES = 480000
    private val styleKeys = setOf("color", "width", "opacity", "tool", "brush", "brushPresetId", "brushSeed", "nibAngle")
    data class Part(val inputIndex: Int, val segmentIndex: Int, val stroke: JSONObject)

    fun expand(p: JSONObject): List<Part> {
        val layerId = p.getString("layerId")
        require(layerId.isNotBlank()) { "layerId 不能为空" }
        val defaults = if(p.has("defaults")) p.getJSONObject("defaults") else JSONObject()
        require(defaults.keys().asSequence().all { it in styleKeys }) { "defaults 含未知样式字段" }
        val items = p.getJSONArray("strokes")
        require(items.length() in 1..MAX_STROKES) { "strokes 须为 1–$MAX_STROKES 项" }
        val result = mutableListOf<Part>()
        var samples = 0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            require(item.keys().asSequence().all { it in styleKeys || it in setOf("points", "segments") }) { "strokes[$i] 含未知字段" }
            require(item.has("points") != item.has("segments")) { "strokes[$i] 必须且只能传 points 或 segments" }
            val style = JSONObject(defaults.toString())
            item.keys().asSequence().filter { it in styleKeys }.forEach { style.put(it, item.get(it)) }
            val tool = style.optString("tool", "ink")
            require(tool in ArtBrush.tools) { "批量入口支持 ink/pencil/soft/spray/eraser/calligraphy；其他工具使用原入口" }
            require(style.getString("color").matches(Regex("#[A-Fa-f0-9]{8}"))) { "strokes[$i] 颜色必须为 #AARRGGBB" }
            require(style.getDouble("width").isFinite() && style.getDouble("width") in 0.1..512.0) { "strokes[$i] width 须为 0.1–512" }
            require(style.optDouble("opacity", 1.0).isFinite() && style.optDouble("opacity", 1.0) in 0.0..1.0) { "strokes[$i] opacity 须为 0–1" }
            val paths = if (item.has("points")) JSONArray().put(item.getJSONArray("points")) else item.getJSONArray("segments")
            require(paths.length() in 1..MAX_STROKES) { "strokes[$i] segments 须为 1–$MAX_STROKES 段" }
            for (j in 0 until paths.length()) {
                require(result.size < MAX_STROKES) { "展开后的独立笔画超过 $MAX_STROKES；请分批" }
                val points = paths.getJSONArray(j)
                ArtBrush.samples(points)
                samples += points.length()
                require(samples <= MAX_SAMPLES) { "批量采样点 $samples 超过上限 $MAX_SAMPLES；请分批" }
                val stroke = JSONObject(style.toString()).put("layerId", layerId).put("tool", tool)
                    .put("points", JSONArray(points.toString()))
                // A stable default makes budget preview and commit agree, including random dynamics.
                if (!stroke.has("brushSeed")) stroke.put("brushSeed", i * MAX_STROKES + j)
                result.add(Part(i, j, stroke))
            }
        }
        return result
    }

    fun budget(strokes: List<JSONObject>): JSONObject {
        var dabs = 0L
        var particles = 0L
        val items = JSONArray()
        for (stroke in strokes) {
            val count = ArtBrush.budget(stroke)
            dabs += count.dabs
            particles += count.particles
            items.put(JSONObject().put("dabs", count.dabs).put("particles", count.particles).put("brushSeed", stroke.getInt("brushSeed")))
        }
        if (dabs > MAX_DABS || particles > MAX_PARTICLES)
            throw ArtStrokeBudgetExceeded("batch", dabs, particles, MAX_DABS, MAX_PARTICLES, false)
        return JSONObject().put("withinBudget", true).put("strokeCount", strokes.size)
            .put("estimatedDabs", dabs).put("estimatedParticles", particles).put("estimateIsLowerBound", false)
            .put("dabLimit", MAX_DABS).put("particleLimit", MAX_PARTICLES).put("strokes", items)
    }

    /** The caller owns this unpersisted document and its lock. Validate before the sole write. */
    fun commit(doc: JSONObject, events: List<JSONObject>, validate: (JSONObject) -> JSONObject,
        write: (ByteArray) -> Unit): JSONObject {
        events.forEach { doc.getJSONArray("operations").put(it) }
        val result = validate(doc)
        val bytes = doc.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024 * 1024) { "工程数据超过32 MiB，请分批或拆分工程" }
        write(bytes)
        return result
    }
}

/** A rejected edit contains enough evidence to split it without guessing the hidden limit. */
internal class ArtStrokeBudgetExceeded(val scope: String, val dabs: Long, val particles: Long,
    val dabLimit: Int, val particleLimit: Int, val lowerBound: Boolean) : IllegalArgumentException(
        "${if(scope == "batch") "批量" else "单笔"}笔触预算超限：预计笔尖${if (lowerBound) "至少" else ""}$dabs（上限 $dabLimit），" +
            "粒子${if (lowerBound) "至少" else ""}$particles（上限 $particleLimit）。请增大间距或分批；独立落笔用 stroke.batch 的 segments。") {
    fun response() = JSONObject().put("success", false).put("status", "rejected")
        .put("error", message).put("reasonCode", "STROKE_BUDGET_EXCEEDED").put("operationApplied", false)
        .put("withinBudget", false).put("scope", scope).put("estimatedDabs", dabs)
        .put("estimatedParticles", particles).put("estimateIsLowerBound", lowerBound)
        .put("dabLimit", dabLimit).put("particleLimit", particleLimit)
}

internal class ArtBatchPartBudgetExceeded(val budget: ArtStrokeBudgetExceeded,
    val inputIndex: Int, val segmentIndex: Int) : IllegalArgumentException("strokes[$inputIndex] 第 $segmentIndex 段：${budget.message}", budget) {
    fun response() = budget.response().put("inputIndex", inputIndex).put("segmentIndex", segmentIndex)
}
