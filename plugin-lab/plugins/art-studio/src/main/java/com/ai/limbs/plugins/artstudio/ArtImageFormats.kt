package com.ai.limbs.plugins.artstudio

import android.graphics.ImageDecoder
import org.json.JSONArray
import org.json.JSONObject

/** Import formats are plugin policy; Android reports decoder availability in each runtime. */
internal object ArtImageFormats {
    private data class Format(val name: String, val mimes: List<String>, val extensions: List<String>)
    private val formats = listOf(
        Format("PNG", listOf("image/png"), listOf("png")),
        Format("JPEG", listOf("image/jpeg"), listOf("jpg", "jpeg", "jpe")),
        Format("WebP", listOf("image/webp"), listOf("webp")),
        Format("BMP", listOf("image/bmp", "image/x-ms-bmp"), listOf("bmp")),
        Format("GIF", listOf("image/gif"), listOf("gif")),
        Format("HEIC / HEIF", listOf("image/heic", "image/heif"), listOf("heic", "heif")),
        Format("AVIF", listOf("image/avif"), listOf("avif"))
    )
    val pickerMimeTypes = formats.flatMap { it.mimes }.toTypedArray()
    fun accepts(mime: String): Boolean = formats.any { mime in it.mimes }
    fun describe(): JSONObject = JSONObject().put("formats", JSONArray().apply {
        formats.forEach { format ->
            put(JSONObject().put("name", format.name).put("mimeTypes", JSONArray(format.mimes))
                .put("extensions", JSONArray(format.extensions))
                .put("decoderAvailable", format.mimes.any { ImageDecoder.isMimeTypeSupported(it) })
                .put("importMode", "static_bitmap"))
        }
    }).put("export", ArtExportFormats.describe()).put("detection", "encoded_content").put("animatedImport", "first_frame")
        .put("workingColorSpace", "sRGB").put("workingBitDepth", 8)
        .put("inputByteLimit", ArtStore.MAX_IMAGE_INPUT_BYTES)
        .put("assetByteLimit", ArtStore.MAX_ASSET_BYTES)
        .put("notice", "GIF 和动态 WebP 当前只导入首帧。HEIC/HEIF、AVIF 依赖当前运行环境的系统解码器；无法解码会明确报错。")
}
