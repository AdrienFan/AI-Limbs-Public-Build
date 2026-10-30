package com.ai.limbs.plugins.artstudio

import android.os.Build
import java.io.File
import java.security.MessageDigest

/** In-process plugin libraries are APK resources, not the Host's nativeLibraryDir. */
internal object ArtNativeLibrary {
    fun load(directory:File,name:String) {
        require(name.matches(Regex("[a-z0-9_]+")))
        val abi=Build.SUPPORTED_ABIS.firstOrNull {it in setOf("arm64-v8a","armeabi-v7a","x86_64","x86")} ?: error("画室原生像素模块不支持此设备ABI")
        val bytes=requireNotNull(ArtNativeLibrary::class.java.classLoader?.getResourceAsStream("lib/"+abi+"/lib"+name+".so")) {"画室包缺少高位深模块，请安装完整云编译包"}.use {it.readBytes()}
        require(bytes.size in 1..16*1024*1024)
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex=digest.joinToString("") {"%02x".format(it.toInt() and 255)}
        val parent=File(directory,"pixels-native").apply {check(mkdirs() || isDirectory)}
        val library=File(parent,"lib"+name+"-"+hex+".so")
        if(!library.isFile) {
            val temporary=File.createTempFile("pixels-",".so",parent)
            try {temporary.outputStream().use {it.write(bytes)};check(temporary.setReadOnly() && temporary.renameTo(library))}
            finally {temporary.delete()}
        }
        require(MessageDigest.getInstance("SHA-256").digest(library.readBytes()).contentEquals(digest)) {"高位深原生模块校验失败"}
        System.load(library.absolutePath)
    }
}
