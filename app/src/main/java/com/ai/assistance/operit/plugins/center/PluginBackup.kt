package com.ai.assistance.operit.plugins.center

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class PluginBackupSnapshot(
    val pluginId: String,
    val version: String,
    val packageSha256: String,
    val backedUpAtEpochMs: Long,
    val wasEnabled: Boolean,
    val manifest: PluginManifest,
    val packageFile: File,
    val installed: Boolean = false,
    val installedVersion: String? = null
)

data class PluginBackupPolicySnapshot(
    val enabled: Boolean
)

class PluginBackupPolicyStore(context: Context) {
    private val prefs = context.getSharedPreferences("plugin_backup_policy_v1", Context.MODE_PRIVATE)

    fun snapshot(): PluginBackupPolicySnapshot =
        PluginBackupPolicySnapshot(enabled = prefs.getBoolean("enabled", false))

    fun configure(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
    }

    companion object {
        const val HIGH_FREQUENCY_USE_COUNT = 10L
    }
}

class PluginBackupStore(
    private val store: PluginStore
) {
    fun initialize() {
        if (!store.backupsRoot.exists() && !store.backupsRoot.mkdirs()) {
            throw PluginInstallException("BACKUP_STORE_INIT_FAILED", "Could not create plugin backup directory")
        }
    }

    fun snapshot(pluginId: String): PluginBackupSnapshot? {
        val dir = store.backupDir(pluginId)
        val packageFile = store.backupPackageIn(dir)
        val metadataFile = store.backupMetadataIn(dir)
        if (!packageFile.isFile || !metadataFile.isFile) return null
        return runCatching {
            val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
            val manifest = PluginPackageInspector.inspect(packageFile)
            PluginBackupSnapshot(
                pluginId = manifest.pluginId,
                version = manifest.version,
                packageSha256 = metadata.getString("package_sha256"),
                backedUpAtEpochMs = metadata.getLong("backed_up_at"),
                wasEnabled = metadata.optBoolean("was_enabled", false),
                manifest = manifest,
                packageFile = packageFile
            )
        }.getOrNull()
    }

    fun snapshots(): List<PluginBackupSnapshot> =
        store.backupsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { snapshot(it.name) }
            ?.sortedWith(compareBy({ it.manifest.display.name.lowercase() }, { it.pluginId }))
            .orEmpty()

    fun backup(pluginId: String, version: String, wasEnabled: Boolean): PluginBackupSnapshot {
        val content = store.contentIn(store.versionDir(pluginId, version))
        if (!content.isDirectory) {
            throw PluginInstallException("BACKUP_SOURCE_MISSING", "Installed content is missing for $pluginId $version")
        }
        val manifestFile = File(content, PluginAbi.MANIFEST_ENTRY)
        if (!manifestFile.isFile) {
            throw PluginInstallException("BACKUP_MANIFEST_MISSING", "Installed plugin manifest is missing for $pluginId $version")
        }
        val manifest = PluginManifestParser.parse(manifestFile.readText(Charsets.UTF_8))
        if (manifest.pluginId != pluginId || manifest.version != version) {
            throw PluginInstallException("BACKUP_SOURCE_MISMATCH", "Installed content metadata does not match backup target")
        }
        val temp = File(store.backupsRoot, ".tmp-${UUID.randomUUID()}")
        if (!temp.mkdirs()) throw PluginInstallException("BACKUP_CREATE_FAILED", "Could not create backup staging directory")
        try {
            val packageFile = store.backupPackageIn(temp)
            packDirectory(content, packageFile)
            val digest = PluginPackageVerifier.sha256(packageFile)
            val metadata = JSONObject()
                .put("plugin_id", pluginId)
                .put("version", version)
                .put("package_sha256", digest)
                .put("backed_up_at", System.currentTimeMillis())
                .put("was_enabled", wasEnabled)
            store.backupMetadataIn(temp).writeText(metadata.toString(2), Charsets.UTF_8)
            replaceBackup(pluginId, temp)
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
        return snapshot(pluginId)
            ?: throw PluginInstallException("BACKUP_VERIFY_FAILED", "Backup could not be read after it was written")
    }

    private fun packDirectory(contentRoot: File, output: File) {
        val files = contentRoot.walkTopDown()
            .filter(File::isFile)
            .map { file -> file to file.relativeTo(contentRoot).invariantSeparatorsPath }
            .sortedBy { it.second }
            .toList()
        if (files.none { it.second == PluginAbi.MANIFEST_ENTRY }) {
            throw PluginInstallException("BACKUP_MANIFEST_MISSING", "Installed content does not contain ${PluginAbi.MANIFEST_ENTRY}")
        }
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            files.forEach { (file, path) ->
                val entry = ZipEntry(PluginPackagePaths.requireSafeRelativePath(path)).apply { time = 0L }
                zip.putNextEntry(entry)
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun delete(pluginId: String) {
        store.backupDir(pluginId).deleteRecursively()
    }

    private fun replaceBackup(pluginId: String, staged: File) {
        val target = store.backupDir(pluginId)
        val previous = File(store.backupsRoot, ".old-${UUID.randomUUID()}")
        if (target.exists() && !target.renameTo(previous)) {
            throw PluginInstallException("BACKUP_REPLACE_FAILED", "Could not move previous backup aside")
        }
        if (!staged.renameTo(target)) {
            if (previous.exists()) previous.renameTo(target)
            throw PluginInstallException("BACKUP_REPLACE_FAILED", "Could not commit plugin backup")
        }
        if (previous.exists()) previous.deleteRecursively()
    }
}
