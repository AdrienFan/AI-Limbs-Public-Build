package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import org.json.JSONArray
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.IOException
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
    // Conservative working estimate includes software decoding, 8-bit normalization and PNG buffers.
    // Allow for an F16 software decode, ARGB copy, codec workspace and growing/copying PNG buffers.
    private const val IMPORT_BYTES_PER_PIXEL = 56L

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

    private fun target(bytes: ByteArray, width: Int, height: Int,
                       confirmation: JSONObject?, extraBytes: Long): Pair<Int, Int> {
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
        if(ArtOpenExr.matches(bytes)) {
            val header=ArtOpenExr.header(bytes)
            val size=target(bytes,header.width,header.height,confirmation,extraBytes)
            requireWorkingSize(size.first,size.second)
            val image=ArtOpenExr.decode(bytes,size.first.toLong()*size.second,size.first,size.second)
            return ArtFloatPixels.from(size.first,size.second,image.pixels) to JSONObject()
                .put("width",size.first).put("height",size.second).put("originalWidth",header.width).put("originalHeight",header.height)
                .put("mime","image/x-exr").put("animated",false).put("frameIndex",0).put("animationPreserved",false)
                .put("colorSpace","linear-extended-sRGB").put("bitDepth",16).put("pixelType","float")
                .put("dataWindowX",header.x).put("dataWindowY",header.y).put("resized",size.first!=header.width || size.second!=header.height)
                .put("resizeFilter","nearest-linear-float").put("warnings",JSONArray())
        }
        val metadata = JSONObject()
        val decoded = try {
            // Decode from bytes, never the file suffix or picker MIME. The header callback runs
            // before pixel allocation, so a resize request cannot create a partial document.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val originalWidth = info.size.width; val originalHeight = info.size.height
                require(originalWidth > 0 && originalHeight > 0 &&
                    ArtImageFormats.accepts(info.mimeType)) {
                    "图片格式不受支持，当前可读取 PNG、JPEG/JPG、WebP、BMP、GIF、HEIC/HEIF、AVIF"
                }
                require(originalWidth.toLong() * originalHeight <= Long.MAX_VALUE / 32) { "图片声明的像素数量无效" }
                val (width, height) = target(bytes, originalWidth, originalHeight, confirmation, extraBytes)
                // Recheck the current budget immediately before allocation and encoding normalization.
                if (width.toLong() * height * IMPORT_BYTES_PER_PIXEL + extraBytes > budgetBytes())
                    throw ArtImageResizeRequired(resizePlan(bytes, originalWidth, originalHeight, budgetBytes(), extraBytes))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(width, height)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                metadata.put("originalWidth", originalWidth).put("originalHeight", originalHeight)
                    .put("mime", info.mimeType).put("animated", info.isAnimated)
                    .put("headerColorSpace", info.colorSpace?.name ?: JSONObject.NULL)
                    .put("frameIndex", 0).put("animationPreserved", false)
                    .put("colorSpace", "linear-extended-sRGB").put("bitDepth", 16).put("pixelType","float")
                    .put("resized", width != originalWidth || height != originalHeight)
                metadata.put("warnings", JSONArray().apply {
                    if (info.isAnimated) put("此动图仅导入首帧，当前工程不保留动画；原文件保持不变。")
                })
            }
        } catch (error: ImageDecoder.DecodeException) {
            throw IllegalArgumentException("图片无法解码：文件可能损坏，或当前运行环境缺少该图片编码的系统解码器。", error)
        }
        if(android.os.Build.VERSION.SDK_INT>=34 && decoded.hasGainmap()) {
            decoded.recycle();error("此图片含Ultra HDR增益图；当前高位深导入支持EXR或直接高位深像素，不会丢弃增益图后导入SDR底图。请先转换为OpenEXR。")
        }
        if(bytes.size>25 && bytes[0]==(-119).toByte() && bytes[1]==80.toByte() && bytes[2]==78.toByte() && bytes[3]==71.toByte() && bytes[24]==16.toByte()) {
            require(decoded.config==Bitmap.Config.RGBA_F16) {"系统解码器未保留16位PNG精度，已拒绝导入"}
        }
        val bitmap = if (decoded.config == Bitmap.Config.RGBA_F16 && decoded.colorSpace==ArtFloatPixels.space) decoded else {
            try {
                ArtFloatPixels.bitmap(decoded.width,decoded.height).also {android.graphics.Canvas(it).drawBitmap(decoded,0f,0f,null)}
            } finally { decoded.recycle() }
        }
        bitmap.density = Bitmap.DENSITY_NONE
        return bitmap to metadata.put("width", bitmap.width).put("height", bitmap.height)
    }

    fun encodeAsset(bitmap:Bitmap,limit:Int):ByteArray = if(bitmap.config==Bitmap.Config.RGBA_F16)
        ArtOpenExr.encode(bitmap.width,bitmap.height,ArtFloatPixels.read(bitmap),limit) else encodePng(bitmap,limit)

    /** Compressed photos may grow greatly when normalized to the lossless project asset format. */
    fun encodePng(bitmap: Bitmap, limit: Int): ByteArray {
        if(bitmap.config==Bitmap.Config.RGBA_F16) {
            val preview=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
            try {android.graphics.Canvas(preview).drawBitmap(bitmap,0f,0f,null);return encodePng(preview,limit)} finally {preview.recycle()}
        }
        require(limit > 0) { "PNG 缓冲上限无效" }
        class BoundedOutput : OutputStream() {
            val buffer = ByteArrayOutputStream()
            var exceeded = false
            fun checkSize(addition: Int) {
                if (buffer.size().toLong() + addition > limit) {
                    exceeded = true
                    throw IOException("工程图片资源超过保存上限")
                }
            }
            override fun write(value: Int) { checkSize(1); buffer.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                checkSize(length); buffer.write(bytes, offset, length)
            }
        }
        val output = BoundedOutput()
        val encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        require(!output.exceeded) { "转换后的工程图片超过 " + limit / (1024 * 1024) + " MiB；请使用较小尺寸" }
        require(encoded) { "工程图片 PNG 编码失败" }
        return output.buffer.toByteArray()
    }

    fun assetPixels(file: File): Long {
        if(isExr(file)) {
            val header=ArtOpenExr.header(file.inputStream().use {it.artReadUpTo(1024*1024)})
            return header.width.toLong()*header.height
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        requireDimensions(bounds.outWidth, bounds.outHeight)
        return bounds.outWidth.toLong() * bounds.outHeight
    }

    private fun isExr(file:File)=file.inputStream().use {ArtOpenExr.matches(it.artReadUpTo(4))}
    fun bounds(file:File):BitmapFactory.Options {
        if(isExr(file)) {val h=ArtOpenExr.header(file.inputStream().use {it.artReadUpTo(1024*1024)})
            return BitmapFactory.Options().apply {outWidth=h.width;outHeight=h.height}}
        return BitmapFactory.Options().apply {inJustDecodeBounds=true;inScaled=false;BitmapFactory.decodeFile(file.absolutePath,this)}
    }
    fun bounds(bytes:ByteArray):BitmapFactory.Options {
        if(ArtOpenExr.matches(bytes)) {val h=ArtOpenExr.header(bytes);return BitmapFactory.Options().apply {outWidth=h.width;outHeight=h.height}}
        return BitmapFactory.Options().apply {inJustDecodeBounds=true;inScaled=false;BitmapFactory.decodeByteArray(bytes,0,bytes.size,this)}
    }
    fun decodeAssetBytes(bytes:ByteArray):Bitmap {
        if(ArtOpenExr.matches(bytes)) {val h=ArtOpenExr.header(bytes);requireBytes(h.width.toLong()*h.height*40+bytes.size,"高位深资源解码")
            val image=ArtOpenExr.decode(bytes,h.width.toLong()*h.height);return ArtFloatPixels.from(h.width,h.height,image.pixels)}
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,
            BitmapFactory.Options().apply {inScaled=false;inPreferredConfig=Bitmap.Config.RGBA_F16;inPreferredColorSpace=ArtFloatPixels.space}) ?: error("图像资源无法解码")
    }
    fun decodeAsset(file: File): Bitmap {
        if(isExr(file)) {
            val pixels=assetPixels(file);requireBytes(pixels*40+file.length(),"高位深资源解码")
            val image=ArtOpenExr.decode(file.readBytes(),pixels)
            return ArtFloatPixels.from(image.header.width,image.header.height,image.pixels)
        }
        return BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply {inScaled=false;inPreferredConfig=Bitmap.Config.RGBA_F16;inPreferredColorSpace=ArtFloatPixels.space})
            ?: error("工程图片资源无效：${file.name}")
    }

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
        var moveMaskWork=0L
        for(layer in byId.values)layer.optJSONArray("contentOrder")?.let {order->
            for(i in 0 until order.length()) {
                val event=order.getJSONObject(i)
                if(event.getString("kind") in setOf("move_pixels","transform_pixels")) {
                    val mask=event.getJSONObject("sourceSelection")
                    moveMaskWork=maxOf(moveMaskWork,mask.getInt("maskWidth").toLong()*mask.getInt("maskHeight")*6)
                }
            }
        }
        // Moving a soft selection needs a temporary erase surface plus its decoded/padded mask.
        return width.toLong() * height * (3L + depth + (if(moveMaskWork>0)1L else 0L)) * 8 + largestAsset * 40 + moveMaskWork
    }
}
