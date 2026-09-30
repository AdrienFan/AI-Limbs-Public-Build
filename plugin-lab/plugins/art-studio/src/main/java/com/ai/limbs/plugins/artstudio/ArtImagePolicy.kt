package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.sqrt

internal class ArtImageResizeRequired(val plan: JSONObject) :
    IllegalArgumentException(plan.getString("message")) {
    fun response(): JSONObject = JSONObject().put("status", "needs_confirmation")
        .put("operationApplied", false).put("imagePlan", plan)
}

/** Plugin-owned allocation policy. A budget is a conservative estimate, not an OOM guarantee. */
internal object ArtImagePolicy {
    const val MAX_EDGE = 16384
    private const val MIB = 1024L * 1024
    private const val MAX_WORK_BYTES = 384 * MIB
    // Sampled decode may be up to four times target pixels, plus a scaled bitmap and workspace.
    private const val IMPORT_BYTES_PER_PIXEL = 24L

    fun dimensionsValid(width: Int, height: Int): Boolean =
        width in 1..MAX_EDGE && height in 1..MAX_EDGE

    fun requireDimensions(width: Int, height: Int) {
        require(dimensionsValid(width, height)) { "宽度和高度须在 1–16384 像素之间：$width × $height" }
    }

    fun budgetBytes(): Long {
        val runtime = Runtime.getRuntime()
        val headroom = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0)
        // Leave room for the shared runtime, encoding, UI and other plugins; never force a GC.
        return minOf(MAX_WORK_BYTES, (headroom - maxOf(32 * MIB, headroom / 4)).coerceAtLeast(0))
    }

    fun requireBytes(bytes: Long, operation: String) {
        val budget = budgetBytes()
        require(bytes >= 0 && bytes <= budget) {
            "$operation 预计需要 ${ceilMiB(bytes)} MiB，当前处理预算 ${ceilMiB(budget)} MiB；请使用较小尺寸或稍后重试。"
        }
    }

    fun requireWorkingSize(width: Int, height: Int) {
        requireDimensions(width, height)
        requireBytes(width.toLong() * height * IMPORT_BYTES_PER_PIXEL, "处理 $width × $height 像素")
    }

    fun describe(): JSONObject = JSONObject().put("minEdge", 1).put("maxEdge", MAX_EDGE)
        .put("workingBudgetBytes", budgetBytes()).put("importBytesPerPixel", IMPORT_BYTES_PER_PIXEL)
        .put("budgetIsEstimate", true).put("automaticResize", false)

    private fun ceilMiB(bytes: Long): Long = (bytes + MIB - 1) / MIB

    private fun bounds(bytes: ByteArray): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0 &&
            options.outMimeType in setOf("image/png", "image/jpeg")) { "图片格式无效，请使用 PNG 或 JPEG" }
        require(options.outWidth.toLong() * options.outHeight <= Long.MAX_VALUE / 32) { "图片声明的像素数量无效" }
        return options
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun resizePlan(bytes: ByteArray, width: Int, height: Int, budget: Long,
                           extraBytes: Long): JSONObject {
        val pixels = (budget - extraBytes).coerceAtLeast(0) / IMPORT_BYTES_PER_PIXEL
        require(pixels >= 1) { "当前内存不足，无法安全解码图片；请关闭部分画布或稍后重试。" }
        val factor = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height),
            sqrt(pixels.toDouble() / (width.toLong() * height)))
        val targetWidth = floor(width * factor).toInt().coerceAtLeast(1)
        val targetHeight = floor(height * factor).toInt().coerceAtLeast(1)
        require(targetWidth.toLong() * targetHeight <= pixels) { "当前内存不足，无法处理该长宽比的图片。" }
        val confirmation = JSONObject().put("sourceSha256", hash(bytes))
            .put("width", targetWidth).put("height", targetHeight)
        val reason = if (!dimensionsValid(width, height)) "图片边长超过处理边界" else "图片超过当前内存预算"
        return JSONObject().put("originalWidth", width).put("originalHeight", height)
            .put("suggestedWidth", targetWidth).put("suggestedHeight", targetHeight)
            .put("workingBudgetBytes", budget).put("estimatedOriginalBytes",
                width.toLong() * height * IMPORT_BYTES_PER_PIXEL + extraBytes)
            .put("reason", reason).put("confirmation", confirmation)
            .put("message", "$reason：原图 $width × $height 像素。是否按比例缩小为 $targetWidth × $targetHeight 像素后打开？缩小会减少细节，原文件保持不变。")
    }

    private fun target(bytes: ByteArray, bounds: BitmapFactory.Options,
                       confirmation: JSONObject?, extraBytes: Long): Pair<Int, Int> {
        val width = bounds.outWidth; val height = bounds.outHeight
        val budget = budgetBytes()
        if (confirmation != null) {
            require(confirmation.getString("sourceSha256") == hash(bytes)) { "缩小确认对应另一张图片，请重新选择。" }
            val targetWidth = confirmation.getInt("width"); val targetHeight = confirmation.getInt("height")
            requireDimensions(targetWidth, targetHeight)
            require(targetWidth <= width && targetHeight <= height &&
                kotlin.math.abs(targetWidth.toLong() * height - targetHeight.toLong() * width) <= maxOf(width, height).toLong()) {
                "缩小确认尺寸无效；请使用返回的原比例建议尺寸。"
            }
            if (targetWidth.toLong() * targetHeight * IMPORT_BYTES_PER_PIXEL + extraBytes > budget)
                throw ArtImageResizeRequired(resizePlan(bytes, width, height, budget, extraBytes))
            return targetWidth to targetHeight
        }
        if (!dimensionsValid(width, height) ||
            width.toLong() * height * IMPORT_BYTES_PER_PIXEL + extraBytes > budget)
            throw ArtImageResizeRequired(resizePlan(bytes, width, height, budget, extraBytes))
        return width to height
    }

    fun decode(bytes: ByteArray, confirmation: JSONObject? = null,
               extraBytes: Long = 0): Pair<Bitmap, JSONObject> {
        val bounds = bounds(bytes)
        val (width, height) = target(bytes, bounds, confirmation, extraBytes)
        var sample = 1
        while (sample <= (1 shl 28) && sample * 2L <= maxOf(bounds.outWidth, bounds.outHeight) &&
            (bounds.outWidth.toLong() / (sample * 2L)).coerceAtLeast(1) >= width &&
            (bounds.outHeight.toLong() / (sample * 2L)).coerceAtLeast(1) >= height) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // Recheck immediately before allocating. A changed budget requires fresh explicit consent.
        val sampledPixels = ((bounds.outWidth.toLong() + sample - 1) / sample) *
            ((bounds.outHeight.toLong() + sample - 1) / sample)
        val estimate = sampledPixels * 4 + width.toLong() * height * 8 + extraBytes
        if (estimate > budgetBytes())
            throw ArtImageResizeRequired(resizePlan(bytes, bounds.outWidth, bounds.outHeight, budgetBytes(), extraBytes))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("图片解码失败，请使用有效的 PNG 或 JPEG")
        val bitmap = if (decoded.width == width && decoded.height == height) decoded else {
            try { Bitmap.createScaledBitmap(decoded, width, height, true) } finally { decoded.recycle() }
        }
        return bitmap to JSONObject().put("originalWidth", bounds.outWidth).put("originalHeight", bounds.outHeight)
            .put("width", bitmap.width).put("height", bitmap.height).put("mime", bounds.outMimeType)
            .put("resized", bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight)
    }

    fun assetPixels(file: File): Long {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        requireDimensions(bounds.outWidth, bounds.outHeight)
        return bounds.outWidth.toLong() * bounds.outHeight
    }

    fun decodeAsset(file: File): Bitmap = BitmapFactory.decodeFile(file.absolutePath,
        BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        ?: error("工程图片资源无效：${file.name}")

    fun renderBytes(store: ArtStore, state: JSONObject, width: Int, height: Int): Long {
        val layers = state.getJSONArray("layers")
        val byId = (0 until layers.length()).associate {
            val layer = layers.getJSONObject(it); layer.getString("id") to layer
        }
        var depth = 0
        for (layer in byId.values) {
            var node = layer; var count = 0
            while (node.optString("parentId").isNotBlank()) {
                require(++count <= layers.length()) { "图层组存在循环引用" }
                node = requireNotNull(byId[node.getString("parentId")]) { "图层组不存在" }
            }
            depth = maxOf(depth, count)
        }
        var largestAsset = 0L
        val seenAssets = mutableSetOf<String>()
        ArtMenuOperations.assets(state) { node, key ->
            val id = node.getString(key)
            if (seenAssets.add(id)) largestAsset = maxOf(largestAsset, assetPixels(store.assetFile(id)))
        }
        return width.toLong() * height * (3L + depth) * 4 + largestAsset * 4
    }
}
