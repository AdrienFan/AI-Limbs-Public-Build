package com.ai.limbs.plugins.ubuntu

import android.os.Build
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import java.io.File
import java.util.zip.ZipFile

internal data class UbuntuNativeRuntime(
    val abi: String,
    val directory: File,
    val ptyLibrary: File
)

/** Extracts the native payload carried by the Ubuntu plugin APK into host code-cache. */
internal object UbuntuNativeRuntimeInstaller {
    fun prepare(host: InProcessPluginHost): UbuntuNativeRuntime {
        val apk = host.runtimeEntryFile.canonicalFile
        require(apk.isFile) { "Ubuntu runtime APK is missing: ${apk.absolutePath}" }

        ZipFile(apk).use { zip ->
            val abi = selectAbi(zip)
            val root = File(
                host.applicationContext.codeCacheDir,
                "ailp-native/${sanitize(host.pluginId)}/${host.version}/$abi"
            ).apply { mkdirs() }
            extractAbiLibraries(zip, abi, root)
            val pty = File(root, "libubuntu_plugin_pty.so")
            require(pty.isFile) { "Ubuntu PTY library was not packaged for ABI $abi" }
            require(pty.setExecutable(true, false) || pty.canExecute()) {
                "Ubuntu PTY library is not executable: ${pty.absolutePath}"
            }
            return UbuntuNativeRuntime(abi, root, pty)
        }
    }

    private fun selectAbi(zip: ZipFile): String {
        val packaged = zip.entries().asSequence()
            .mapNotNull { entry ->
                val parts = entry.name.split('/')
                if (parts.size >= 3 && parts[0] == "lib" && parts.last().endsWith(".so")) parts[1] else null
            }
            .toSet()
        return Build.SUPPORTED_ABIS.firstOrNull(packaged::contains)
            ?: error("Ubuntu plugin has no native libraries for supported ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
    }
    private fun extractAbiLibraries(zip: ZipFile, abi: String, root: File) {
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
            .forEach { entry ->
                val target = File(root, entry.name.substringAfterLast('/'))
                val temp = File(root, ".${target.name}.tmp")
                zip.getInputStream(entry).use { input ->
                    temp.outputStream().use(input::copyTo)
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.setReadable(true, false)
                target.setExecutable(true, false)
            }
    }

    private fun sanitize(value: String): String = buildString(value.length) {
        value.forEach { ch -> append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_') }
    }
}
