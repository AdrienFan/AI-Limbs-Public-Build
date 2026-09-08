package com.ai.limbs.plugins.ubuntu

import android.os.Build
import com.ai.limbs.plugin.runtime.InProcessNativeExecutableIds
import com.ai.limbs.plugin.runtime.InProcessNativeRuntime
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import java.io.File
import java.util.zip.ZipFile

internal data class UbuntuNativeRuntime(
    val abi: String,
    val directory: File,
    val ptyLibrary: File
)

/**
 * Uses the host-owned read-only executable substrate for execve targets and
 * extracts only the Ubuntu PTY JNI library from the dynamic plugin APK.
 */
internal object UbuntuNativeRuntimeInstaller {
    private const val REQUIRED_HOST_RUNTIME_API = 1
    private const val PTY_LIBRARY = "libubuntu_plugin_pty.so"

    fun prepare(host: InProcessPluginHost): UbuntuNativeRuntime {
        val executableDirectory = resolveHostExecutableDirectory(host.nativeRuntime)
        val apk = host.runtimeEntryFile.canonicalFile
        require(apk.isFile) { "Ubuntu runtime APK is missing: ${apk.absolutePath}" }

        ZipFile(apk).use { zip ->
            val abi = selectAbi(zip)
            val root = File(
                host.applicationContext.codeCacheDir,
                "ailp-native/${sanitize(host.pluginId)}/${host.version}/$abi"
            ).apply { mkdirs() }
            val pty = extractPtyLibrary(zip, abi, root)
            return UbuntuNativeRuntime(abi, executableDirectory, pty)
        }
    }

    private fun resolveHostExecutableDirectory(runtime: InProcessNativeRuntime): File {
        require(runtime.apiVersion >= REQUIRED_HOST_RUNTIME_API) {
            "Host native runtime API $REQUIRED_HOST_RUNTIME_API is required; available=${runtime.apiVersion}"
        }
        val required = linkedMapOf(
            "libbash.so" to InProcessNativeExecutableIds.POSIX_BASH,
            "libbusybox.so" to InProcessNativeExecutableIds.BUSYBOX,
            "liboperit_proot.so" to InProcessNativeExecutableIds.PROOT,
            "liboperit_loader.so" to InProcessNativeExecutableIds.PROOT_LOADER,
            "libsudo.so" to InProcessNativeExecutableIds.SUDO
        )
        val resolved = required.mapValues { (_, id) ->
            runtime.resolveExecutable(id)?.canonicalFile
                ?: error("Host native executable is unavailable: $id")
        }

        resolved.forEach { (expectedName, file) ->
            require(file.name == expectedName) {
                "Host native runtime v1 returned unexpected file for $expectedName: ${file.name}"
            }
            require(file.isFile && file.canExecute()) {
                "Host native executable is not executable: ${file.absolutePath}"
            }
        }
        val directories = resolved.values.map { it.parentFile.canonicalFile }.toSet()
        require(directories.size == 1) {
            "Host native runtime v1 executables must share one nativeLibraryDir: $directories"
        }
        return directories.single()
    }

    private fun selectAbi(zip: ZipFile): String =
        Build.SUPPORTED_ABIS.firstOrNull { abi -> zip.getEntry("lib/$abi/$PTY_LIBRARY") != null }
            ?: error(
                "Ubuntu plugin has no PTY library for supported ABIs ${Build.SUPPORTED_ABIS.joinToString()}"
            )

    private fun extractPtyLibrary(zip: ZipFile, abi: String, root: File): File {
        val entry = zip.getEntry("lib/$abi/$PTY_LIBRARY")
            ?: error("Ubuntu PTY library was not packaged for ABI $abi")
        val target = File(root, PTY_LIBRARY)
        val temp = File(root, ".$PTY_LIBRARY.tmp")
        zip.getInputStream(entry).use { input ->
            temp.outputStream().use(input::copyTo)
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setReadable(true, false)
        require(target.setExecutable(true, false) || target.canExecute()) {
            "Ubuntu PTY library is not executable: ${target.absolutePath}"
        }
        return target
    }

    private fun sanitize(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_')
        }
    }
}
