package com.ai.assistance.operit.plugins.center

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class PluginStore(
    val rootDir: File
) {
    val stagingRoot = File(rootDir, "staging")
    val pluginsRoot = File(rootDir, "plugins")
    val pluginDataRoot = File(rootDir, "data")
    val pluginCacheRoot = File(rootDir, "cache")
    val quarantineRoot = File(rootDir, "quarantine")
    val backupsRoot = File(rootDir, "backups")

    fun initialize() {
        listOf(rootDir, stagingRoot, pluginsRoot, pluginDataRoot, pluginCacheRoot, quarantineRoot, backupsRoot).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                throw PluginInstallException("STORE_INIT_FAILED", "Could not create plugin store directory: ${dir.path}")
            }
        }
        cleanupStaging()
        cleanupLegacyInstalledPackages()
    }

    fun createStagingTransaction(): File {
        val dir = File(stagingRoot, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw PluginInstallException("STAGING_CREATE_FAILED", "Could not create staging transaction")
        return dir
    }

    fun managedPackageIn(transactionDir: File): File = File(transactionDir, "package${PluginAbi.PACKAGE_EXTENSION}")
    fun contentIn(versionDir: File): File = File(versionDir, "content")
    fun installMetadataIn(versionDir: File): File = File(versionDir, "install.json")

    fun pluginDir(pluginId: String): File = File(pluginsRoot, safeSegment(pluginId))
    fun versionsDir(pluginId: String): File = File(pluginDir(pluginId), "versions")
    fun versionDir(pluginId: String, version: String): File = File(versionsDir(pluginId), safeSegment(version))
    fun stateFile(pluginId: String): File = File(pluginDir(pluginId), "state.json")
    fun dataDir(pluginId: String): File = File(pluginDataRoot, safeSegment(pluginId))
    fun cacheDir(pluginId: String): File = File(pluginCacheRoot, safeSegment(pluginId))
    fun backupDir(pluginId: String): File = File(backupsRoot, safeSegment(pluginId))
    fun backupPackageIn(backupDir: File): File = File(backupDir, "package${PluginAbi.PACKAGE_EXTENSION}")
    fun backupMetadataIn(backupDir: File): File = File(backupDir, "backup.json")

    fun copyIntoManagedStaging(source: File, target: File) {
        if (!source.isFile) throw PluginInstallException("SOURCE_MISSING", "Plugin source file does not exist")
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    fun discardManagedPackage(transactionDir: File) {
        val managed = managedPackageIn(transactionDir)
        if (managed.exists() && !managed.delete()) {
            throw PluginInstallException("STORE_PACKAGE_DISCARD_FAILED", "Could not discard staged plugin package before commit")
        }
    }

    fun commitVersion(transactionDir: File, pluginId: String, version: String): File {
        val versions = versionsDir(pluginId)
        if (!versions.exists() && !versions.mkdirs()) throw PluginInstallException("STORE_COMMIT_FAILED", "Could not create plugin versions directory")
        val target = versionDir(pluginId, version)
        if (target.exists()) throw PluginInstallException("VERSION_ALREADY_EXISTS", "Plugin version already exists")
        if (!transactionDir.renameTo(target)) throw PluginInstallException("STORE_COMMIT_FAILED", "Could not atomically commit plugin version")
        return target
    }

    fun listPluginIds(): List<String> =
        pluginsRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    fun listVersions(pluginId: String): List<String> =
        versionsDir(pluginId).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedWith { a, b ->
            val left = SemanticVersion.parse(a)
            val right = SemanticVersion.parse(b)
            when {
                left != null && right != null -> left.compareTo(right)
                else -> a.compareTo(b)
            }
        }.orEmpty()

    fun quarantineVersion(pluginId: String, version: String): File? {
        val source = versionDir(pluginId, version)
        if (!source.exists()) return null
        val pluginQuarantine = File(quarantineRoot, safeSegment(pluginId)).apply { mkdirs() }
        val target = File(pluginQuarantine, "${safeSegment(version)}-${System.currentTimeMillis()}")
        return if (source.renameTo(target)) target else null
    }

    fun deletePlugin(pluginId: String, removeData: Boolean) {
        pluginDir(pluginId).deleteRecursively()
        cacheDir(pluginId).deleteRecursively()
        if (removeData) dataDir(pluginId).deleteRecursively()
    }

    fun cleanupStaging() {
        stagingRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun cleanupLegacyInstalledPackages() {
        pluginsRoot.listFiles()?.filter(File::isDirectory)?.forEach { plugin ->
            File(plugin, "versions").listFiles()?.filter(File::isDirectory)?.forEach { version ->
                File(version, "package${PluginAbi.PACKAGE_EXTENSION}").delete()
            }
        }
    }

    private fun safeSegment(value: String): String {
        if (value.isBlank() || value == "." || value == ".." || value.contains('/') || value.contains('\\')) {
            throw PluginInstallException("STORE_PATH_INVALID", "Unsafe plugin store segment")
        }
        return value
    }

    companion object {
        fun fromContext(context: Context): PluginStore =
            PluginStore(File(context.filesDir, "ai_limbs/plugin_center"))
    }
}
