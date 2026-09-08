package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/** Runtime asset source for the vendored Terminal Core. */
object TerminalRuntimeAssets {
    @Volatile
    private var runtimeApk: File? = null

    fun configure(apk: File) {
        runtimeApk = apk.canonicalFile
    }

    fun readBytes(context: Context, assetName: String): ByteArray {
        val apk = runtimeApk
        if (apk != null) {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("assets/$assetName")
                    ?: error("Terminal runtime asset missing: $assetName")
                return zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return context.assets.open(assetName).use { it.readBytes() }
    }
}
