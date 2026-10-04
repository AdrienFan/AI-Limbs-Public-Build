package com.ai.limbs.plugins.artstudio

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Export policy belongs to the plugin; decoder support never implies encoder support. */
internal object ArtExportFormats {
    data class Format(val id: String, val label: String, val mime: String, val opaque: Boolean, val notice: String)
    data class Support(val format: Format, val available: Boolean, val reason: String)
    val formats = listOf(
        Format("png", "PNG", "image/png", false, "无损，保留透明度。"),
        Format("jpeg", "JPEG", "image/jpeg", true, "有损，透明区域合成为白色。"),
        Format("webp", "WebP", "image/webp", false, "无损，保留透明度；输出单张图片。"),
        Format("bmp", "BMP", "image/bmp", true, "24位未压缩，透明区域合成为白色。"),
        Format("gif", "GIF（单帧）", "image/gif", false, "单帧、自适应255色加透明索引；半透明以alpha=128为界。最多16 Mi像素。"),
        Format("heic", "HEIC", "image/heic", true, "HEVC压缩，透明区域合成为白色；需要设备编码器。"),
        Format("heif", "HEIF", "image/heif", true, "HEVC压缩，与HEIC使用相同编码；透明区域合成为白色。"),
        Format("avif", "AVIF", "image/avif", true, "AV1压缩，透明区域合成为白色；需要设备编码器。")
    )
    fun get(id: String): Format = formats.firstOrNull { it.id == id } ?: error("不支持的导出格式：$id")
    fun support(): List<Support> {
        val codecs = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }
        catch (error: Exception) {
            Log.e("ArtStudio", "Cannot enumerate image encoders", error)
            return formats.map { Support(it, it.id !in setOf("heic", "heif", "avif"),
                if (it.id in setOf("heic", "heif", "avif")) "无法读取设备编码器：${error.message}" else "") }
        }
        fun encoder(mime: String, tiledVideo: Boolean): Boolean = codecs.any { codec ->
            if (!codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }) false
            else try {
                val caps = codec.getCapabilitiesForType(mime)
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                    (!tiledVideo || caps.videoCapabilities?.isSizeSupported(512, 512) == true)
            } catch (error: Exception) {
                Log.w("ArtStudio", "Cannot inspect ${codec.name}/$mime", error)
                false
            }
        }
        // AndroidX 1.1.0 writes AVIF through a 512px-tile AV1 video encoder.
        val heif = encoder(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, false) || encoder(MediaFormat.MIMETYPE_VIDEO_HEVC, true)
        val avif = encoder(MediaFormat.MIMETYPE_VIDEO_AV1, true)
        return formats.map { format ->
            val available = when (format.id) { "heic", "heif" -> heif; "avif" -> avif; else -> true }
            Support(format, available, if (available) "" else "设备没有可用的${if (format.id == "avif") "AV1" else "HEIC/HEVC"}位图编码器")
        }
    }
    fun requireAvailable(id: String): Format {
        val requested = get(id)
        if (id !in setOf("heic", "heif", "avif")) return requested
        val checked = support().first { it.format.id == id }
        require(checked.available) { "${requested.label}不可导出：${checked.reason}" }
        return requested
    }
    fun describe(): JSONObject = JSONObject().put("formats", JSONArray().apply {
        support().forEach { put(JSONObject().put("id", it.format.id).put("name", it.format.label)
            .put("mime", it.format.mime).put("extension", it.format.id).put("encoderAvailable", it.available)
            .put("unavailableReason", it.reason).put("notice", it.format.notice)) }
    }).put("mode", "timeline_current_frame").put("supportCheck", "codec_capabilities")
        .put("notice", "设备编码能力预检不保证指定尺寸编码成功；编码失败明确报错。完整GIF使用animation.export。")
}
