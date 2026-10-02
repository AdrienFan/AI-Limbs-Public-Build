package com.ai.limbs.plugins.artstudio

import android.graphics.fonts.Font
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Plugin-owned shaping. No default Typeface or private Android font initialization. */
internal object ArtTextShaper {
    private var loaded = false
    @Synchronized fun load(directory: File) {
        if (loaded) return
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
            ?: error("文字塑形不支持此设备 ABI")
        val resource = "lib/$abi/libartstudio_text_0262.so"
        val bytes = requireNotNull(ArtTextShaper::class.java.classLoader?.getResourceAsStream(resource)) {
            "画室包缺少文字塑形模块：$abi；请安装完整云编译包"
        }.use { it.readBytes() }
        require(bytes.size in 1..16 * 1024 * 1024)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val parent = File(directory, "text-native").apply { check(mkdirs() || isDirectory) }
        val library = File(parent, "libartstudio_text_0262-$digest.so")
        if (!library.exists()) {
            val temporary = File.createTempFile("shaper-", ".so", parent)
            try {
                temporary.outputStream().use { it.write(bytes) }
                check(temporary.setReadOnly() && temporary.renameTo(library))
            } finally { temporary.delete() }
        }
        require(MessageDigest.getInstance("SHA-256").digest(library.readBytes()).contentEquals(
            MessageDigest.getInstance("SHA-256").digest(bytes))) { "文字塑形模块校验失败" }
        System.load(library.absolutePath)
        loaded = true
    }

    fun shape(font: Font, content: String, start: Int, end: Int, size: Float,
        direction: String, script: String, language: String, features: String): FloatArray {
        check(loaded) { "文字塑形模块尚未初始化" }
        val axes = font.axes.orEmpty().joinToString(",") { "${it.tag}=${it.styleValue}" }
        return shapeNative(font.buffer, font.ttcIndex, axes, content, start, end, size,
            direction, script, language, features)
    }
    private external fun shapeNative(buffer: ByteBuffer, index: Int, axes: String, content: String,
        start: Int, end: Int, size: Float, direction: String, script: String,
        language: String, features: String): FloatArray
}
