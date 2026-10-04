package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.os.Build
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** One requested format produces exactly that format; an encoder failure never changes it. */
internal object ArtImageEncoder {
    @Suppress("DEPRECATION")
    fun write(bitmap: Bitmap, format: String, file: File) {
        when (format) {
            "heic", "heif" -> HeifWriter.Builder(file.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .setMaxImages(1).setQuality(95).build().use { writer ->
                    writer.start(); writer.addBitmap(bitmap); writer.stop(30000)
                }
            "avif" -> AvifWriter.Builder(file.absolutePath, bitmap.width, bitmap.height, AvifWriter.INPUT_MODE_BITMAP)
                .setMaxImages(1).setQuality(95).build().use { writer ->
                    writer.start(); writer.addBitmap(bitmap); writer.stop(30000)
                }
            else -> FileOutputStream(file).use { stream ->
                when (format) {
                    "png" -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG编码失败" }
                    "jpeg" -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "JPEG编码失败" }
                    "webp" -> check(bitmap.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                        else Bitmap.CompressFormat.WEBP, 100, stream)) { "WebP编码失败" }
                    "bmp" -> ArtBmpWriter.write(stream, bitmap.width, bitmap.height) { y, row ->
                        bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                    }
                    "gif" -> gif(bitmap, stream)
                    else -> error("不支持的导出格式：$format")
                }
                stream.fd.sync()
            }
        }
        require(file.isFile && file.length() > 0) { "编码器没有生成图片" }
    }
    private fun gif(bitmap: Bitmap, out: OutputStream) {
        require(bitmap.width.toLong() * bitmap.height <= 16L * 1024 * 1024) { "单帧GIF超过16 Mi像素，请缩小输出尺寸" }
        val builder = ArtGifPalette.Builder(); val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1); builder.addRow(row)
        }
        ArtGifWriter(out, bitmap.width, bitmap.height, false, builder.build(), builder.hasTransparency).apply {
            frame(bitmap, 1); finish()
        }
    }
}
