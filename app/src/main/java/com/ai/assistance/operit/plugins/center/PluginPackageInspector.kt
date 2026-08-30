package com.ai.assistance.operit.plugins.center

import java.io.File
import java.util.zip.ZipFile

/** Lightweight manifest preview for Plugin Center UI. Full verification still happens on install. */
object PluginPackageInspector {
    private const val MAX_MANIFEST_BYTES = 512L * 1024L

    fun inspect(sourcePackage: File): PluginManifest {
        if (!sourcePackage.isFile) {
            throw PluginInstallException("SOURCE_MISSING", "Plugin source file does not exist")
        }
        if (!sourcePackage.name.lowercase().endsWith(PluginAbi.PACKAGE_EXTENSION)) {
            throw PluginInstallException(
                "PACKAGE_EXTENSION_INVALID",
                "Plugin package must use ${PluginAbi.PACKAGE_EXTENSION}"
            )
        }

        val raw = ZipFile(sourcePackage).use { archive ->
            val entry = archive.getEntry(PluginAbi.MANIFEST_ENTRY)
                ?: throw PluginInstallException("MANIFEST_MISSING", "Root plugin.json is required")
            if (entry.isDirectory || entry.size > MAX_MANIFEST_BYTES) {
                throw PluginInstallException("MANIFEST_TOO_LARGE", "plugin.json is invalid or too large")
            }
            archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().also { text ->
                    if (text.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
                        throw PluginInstallException("MANIFEST_TOO_LARGE", "plugin.json is too large")
                    }
                }
            }
        }
        return PluginManifestParser.parse(raw)
    }
}
