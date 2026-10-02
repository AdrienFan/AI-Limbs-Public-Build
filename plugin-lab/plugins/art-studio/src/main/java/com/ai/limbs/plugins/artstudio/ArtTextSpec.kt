package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import java.text.BreakIterator
import java.util.Locale

/** Editable source contract, independent of renderer and Android default fonts. */
internal object ArtTextSpec {
    val writingModes = setOf("horizontal-tb", "vertical-rl", "vertical-lr")
    val styleKeys = setOf("fontId", "fontSize", "color", "strokeColor", "strokeWidth", "letterSpacing",
        "wordSpacing", "baselineShift", "underline", "strike", "fontFeatures", "language")
    fun number(p: JSONObject, key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double =
        p.optDouble(key, default).also { require(it.isFinite() && it in range) { "$key 超出范围 $range" } }
    fun color(value: String): String = value.also {
        require(it.matches(Regex("#[A-Fa-f0-9]{8}"))) { "颜色需要为 #AARRGGBB" }
    }
    fun style(p: JSONObject, base: JSONObject = JSONObject()): JSONObject {
        val result = JSONObject(base.toString())
        p.keys().forEach { if (it in styleKeys) result.put(it, p.get(it)) }
        if (result.has("fontId")) require(result.getString("fontId").length in 1..1024)
        result.put("fontSize", number(result, "fontSize", 48.0, 6.0..512.0))
            .put("color", color(result.optString("color", "#FF000000")))
            .put("strokeColor", color(result.optString("strokeColor", "#00000000")))
            .put("strokeWidth", number(result, "strokeWidth", 0.0, 0.0..64.0))
            .put("letterSpacing", number(result, "letterSpacing", 0.0, -64.0..256.0))
            .put("wordSpacing", number(result, "wordSpacing", 0.0, -64.0..256.0))
            .put("baselineShift", number(result, "baselineShift", 0.0, -512.0..512.0))
            .put("underline", result.optBoolean("underline", false)).put("strike", result.optBoolean("strike", false))
        val language = result.optString("language", "und")
        require(language.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) { "language 需要 BCP47 标签" }
        val features = result.optString("fontFeatures", "")
        require(features.length <= 1024 && (features.isBlank() || features.split(',').all {
            it.trim().matches(Regex("[A-Za-z0-9]{4}=[0-9]{1,3}"))
        })) { "fontFeatures 示例：kern=1,liga=1" }
        result.put("language", language).put("fontFeatures", features)
        return result
    }
    fun normalize(p: JSONObject): JSONObject {
        val out = style(p)
        val content = p.getString("content").replace("\r\n", "\n").replace('\r', '\n')
        require(content.isNotBlank() && content.length <= 4096 && content.count { it == '\n' } < 128) {
            "文字需要 1–4096 个 UTF-16 单元，最多 128 段，不能全为空白"
        }
        var i = 0
        while (i < content.length) {
            val cp = Character.codePointAt(content, i)
            require(cp !in 0xD800..0xDFFF && (cp >= 32 || cp == 10 || cp == 9)) { "文字包含无效代理项或控制字符" }
            i += Character.charCount(cp)
        }
        val mode = p.optString("sourceMode", if (p.has("svgSource")) "svg" else if (p.has("spans") && p.getJSONArray("spans").length()>0) "rich" else "plain")
        require(mode in setOf("plain", "rich", "svg")) { "sourceMode 需要 plain/rich/svg" }
        val writing = p.optString("writingMode", "horizontal-tb")
        val direction = p.optString("direction", "auto")
        val orientation = p.optString("textOrientation", "mixed")
        val align = p.optString("align", "left")
        require(writing in writingModes && direction in setOf("auto", "ltr", "rtl") &&
            orientation in setOf("mixed", "upright", "sideways") && align in setOf("left", "center", "right", "start", "end")) {
            "文字方向、竖排方向或对齐方式无效"
        }
        out.put("content", content).put("sourceMode", mode).put("writingMode", writing)
            .put("direction", direction).put("textOrientation", orientation).put("align", align)
            .put("boxWidth", p.optInt("boxWidth", 640).also { require(it in 1..16384) })
            .put("lineSpacing", number(p, "lineSpacing", 1.2, 1.0..3.0))
        val spans = p.optJSONArray("spans") ?: JSONArray()
        require(spans.length() <= 128)
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(content) }
        var previous = 0
        val normalized = JSONArray()
        for (n in 0 until spans.length()) {
            val span = spans.getJSONObject(n)
            require(span.keys().asSequence().all { it in styleKeys || it in setOf("start", "end") }) { "富文本样式键无效" }
            val start = span.getInt("start"); val end = span.getInt("end")
            require(start >= previous && end > start && end <= content.length && breaks.isBoundary(start) && breaks.isBoundary(end)) {
                "spans 需按 UTF-16 顺序、互不重叠，并位于完整字符簇边界"
            }
            previous = end
            normalized.put(style(span, out).put("start", start).put("end", end))
        }
        out.put("spans", normalized)
        for (key in listOf("textPath", "shapeInside")) if (p.has(key) && !p.isNull(key)) {
            val geometry = JSONObject(p.getJSONObject(key).toString())
            require(geometry.keys().asSequence().all { it in setOf("d", "shape", "startOffset", "normalOffset", "padding", "fillRule") })
            require(geometry.has("d") xor geometry.has("shape")) { "$key 需指定 d 或形状快照" }
            if (geometry.has("d")) {
                val commands=ArtSvgPath.commands(geometry.getString("d"))
                if(key=="shapeInside")require(commands.count {it.name.uppercaseChar()=='M'}==commands.count {it.name.uppercaseChar()=='Z'}) {"形状内文字需要闭合的每个子路径"}
            }
            geometry.put("startOffset", number(geometry, "startOffset", 0.0, -16384.0..16384.0))
                .put("normalOffset", number(geometry, "normalOffset", 0.0, -4096.0..4096.0))
                .put("padding", number(geometry, "padding", 0.0, 0.0..1024.0))
            val rule = geometry.optString("fillRule", "nonzero")
            require(rule in setOf("nonzero", "evenodd")); geometry.put("fillRule", rule)
            out.put(key, geometry)
        }
        require(!(out.has("textPath") && out.has("shapeInside"))) { "路径文字与形状内文字不可同时使用" }
        require(!out.has("textPath") || writing == "horizontal-tb") { "路径文字当前使用横排行进方向" }
        if (p.has("svgSource")) {
            require(p.getString("svgSource").toByteArray(Charsets.UTF_8).size <= 131072)
            out.put("svgSource", p.getString("svgSource"))
            val chunks = p.getJSONArray("svgChunks"); require(chunks.length() in 1..256)
            out.put("svgChunks", JSONArray(chunks.toString())).put("svgViewBox", JSONArray(p.getJSONArray("svgViewBox").toString()))
        }
        return out
    }
    fun info(): JSONObject = JSONObject().put("sourceModes", JSONArray(listOf("plain", "rich", "svg")))
        .put("writingModes", JSONArray(writingModes.toList())).put("spanIndex", "UTF-16 grapheme boundaries, sorted non-overlapping")
        .put("shaping", "HarfBuzz 11.0.1 / explicit device font, no automatic font substitution")
        .put("styleKeys", JSONArray(styleKeys.toList())).put("geometry", "textPath or shapeInside: d=SVG path / shape=local frozen vector snapshot")
        .put("svg", ArtSvgText.info()).put("renderMode", "editable_source_with_png_cache")
        .put("notice", ArtText.NOTICE)
}
