package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject

/** Radius sampling shared by touch and LANER color.sample; transparent pixels keep their alpha. */
internal object ArtColorSampler {
    fun renderSource(store: ArtStore, snapshot: JSONObject, layerId: String?): Bitmap {
        if (layerId == null) return ArtRenderer.render(store, snapshot)
        return ArtRenderer.render(store, isolate(snapshot, layerId))
    }

    /** Sample a layer's own projection: neutralize its composite, retain document transforms. */
    fun isolate(snapshot: JSONObject, layerId: String): JSONObject {
        val isolated = JSONObject(snapshot.toString())
        val state = isolated.getJSONObject("state")
        val layers = state.getJSONArray("layers")
        val all = (0 until layers.length()).map { layers.getJSONObject(it) }
        val byId = all.associateBy { it.getString("id") }
        require(byId.size == all.size) { "图层标识重复" }
        val target = byId[layerId] ?: error("取色图层不存在")
        require(target.getString("kind") in setOf("paint", "image", "text", "vector", "colorize", "group")) {
            "此图层没有可取样的内容投影"
        }
        val included = mutableSetOf(layerId)
        val ancestors = mutableSetOf<String>()
        var node = target
        while (node.optString("parentId").isNotBlank()) {
            val parent = node.getString("parentId")
            require(parent != layerId && ancestors.add(parent) && ancestors.size <= all.size) { "图层组循环引用" }
            node = byId[parent] ?: error("取色图层的父组不存在")
            require(node.getString("kind") == "group") { "图层的父节点不是组" }
        }
        // A selected group keeps its visible descendant projection, including child opacity/blend.
        if (target.getString("kind") == "group") {
            repeat(all.size) {
                all.filter { it.optString("parentId") in included }.forEach { included.add(it.getString("id")) }
            }
        }
        state.put("background", "#00000000")
        state.remove("references"); state.remove("assistants")
        for (layer in all) {
            val id = layer.getString("id")
            layer.put("visible", id == layerId || id in ancestors || (id in included && layer.getBoolean("visible")))
            if (id == layerId || id in ancestors) layer.put("opacity", 1.0).put("blend", "normal")
        }
        state.put("layers", org.json.JSONArray(all.filter { it.getString("id") in included || it.getString("id") in ancestors }))
        return isolated
    }

    fun info(): JSONObject = JSONObject().put("targets", org.json.JSONArray(listOf("foreground", "background", "none")))
        .put("sources", org.json.JSONArray(listOf("merged", "layer"))).put("coordinateSpace", "document")
        .put("layerKinds", org.json.JSONArray(listOf("paint", "image", "text", "vector", "colorize", "group")))
        .put("hiddenLayerSampling", true).put("transforms", "layer and ancestors retained")
        .put("layerProjection", "selected layer and ancestor opacity/blend neutralized; group descendants retain visibility, opacity and blend; no canvas background")
        .put("radius", org.json.JSONArray().put(0).put(32)).put("blend", org.json.JSONArray().put(0).put(100))
        .put("defaults", JSONObject().put("sampleMerged", true).put("target", "foreground").put("radius", 0).put("blend", 100))
        .put("colorFormat", "#AARRGGBB / 8-bit sRGB").put("maxPalettes", ArtColorWorkspace.MAX_PALETTES)
        .put("maxColorsPerPalette", ArtColorWorkspace.MAX_COLORS).put("paletteDuplicates", "exact ARGB deduplicated")
        .put("memory", "full-resolution render under image.limits budget; no automatic resize")
        .put("limits", org.json.JSONArray(listOf("canvas extent only", "no HDR/ICC source-value sampling", "no external Krita palette format")))

    fun sample(bitmap: Bitmap, x: Int, y: Int, radius: Int): Int {
        require(x in 0 until bitmap.width && y in 0 until bitmap.height) { "坐标不在画布内" }
        require(radius in 0..32) { "取色半径必须在 0–32 px 之间" }
        if (radius == 0) return bitmap.getPixel(x, y)
        var count = 0L
        var alpha = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        for (py in maxOf(0, y - radius)..minOf(bitmap.height - 1, y + radius)) {
            for (px in maxOf(0, x - radius)..minOf(bitmap.width - 1, x + radius)) {
                val dx = px - x
                val dy = py - y
                if (dx * dx + dy * dy > radius * radius) continue
                val pixel = bitmap.getPixel(px, py)
                val a = Color.alpha(pixel).toLong()
                count++
                alpha += a
                red += Color.red(pixel).toLong() * a
                green += Color.green(pixel).toLong() * a
                blue += Color.blue(pixel).toLong() * a
            }
        }
        if (alpha == 0L) return Color.TRANSPARENT
        return Color.argb(((alpha + count / 2) / count).toInt(),
            ((red + alpha / 2) / alpha).toInt(),
            ((green + alpha / 2) / alpha).toInt(),
            ((blue + alpha / 2) / alpha).toInt())
    }

    /** blend=100 uses the sampled color; blend=0 keeps the original color. */
    fun blend(base: Int, sampled: Int, blend: Int): Int {
        require(blend in 0..100) { "取色混合必须在 0–100% 之间" }
        val originalWeight = 100L - blend
        val sampledWeight = blend.toLong()
        val originalAlpha = Color.alpha(base).toLong() * originalWeight
        val sampledAlpha = Color.alpha(sampled).toLong() * sampledWeight
        val combined = originalAlpha + sampledAlpha
        if (combined == 0L) return Color.TRANSPARENT
        fun channel(extract: (Int) -> Int): Int =
            ((extract(base).toLong() * originalAlpha +
                extract(sampled).toLong() * sampledAlpha + combined / 2) / combined).toInt()
        return Color.argb(((combined + 50) / 100).toInt(),
            channel { Color.red(it) }, channel { Color.green(it) }, channel { Color.blue(it) })
    }
}
