package com.ai.assistance.operit.plugins.system

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.SemanticVersion
import com.ai.assistance.operit.plugins.center.SystemPluginUiRegistry
import com.ai.assistance.operit.util.AppLogger
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class SystemPluginMaintenanceSnapshot(
    val installed: Boolean,
    val activeVersion: String?,
    val currentBackupVersion: String?,
    val previousBackupVersion: String?
)

internal class SystemPluginController(
    context: Context,
    private val uiRegistry: SystemPluginUiRegistry,
    private val hostFactory: (String, String) -> SystemPluginHostV1
) {
    private data class ActiveSession(
        val manifest: SystemPluginManifestV1,
        val handle: AutoCloseable,
        val classLoader: DexClassLoader
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "ai_limbs/system_plugins/plugin_center")
    private val versionsDir = File(root, "versions")
    private val backupDir = File(root, "backup")
    private val stateFile = File(root, "state.json")
    private val pendingDir = File(root, "pending")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeSession: ActiveSession? = null

    fun initialize() {
        versionsDir.mkdirs()
        backupDir.mkdirs()
        pendingDir.mkdirs()
    }

    suspend fun restore() {
        val version = readStateVersion() ?: return
        val packageFile = packageFile(version)
        if (!packageFile.isFile) return
        runCatching { mountPackage(packageFile, packageFile.name) }
            .onFailure { AppLogger.e(TAG, "Failed to restore Plugin Center $version", it) }
    }

    fun snapshot(): SystemPluginMaintenanceSnapshot {
        val active = activeSession?.manifest?.version ?: readStateVersion()
        return SystemPluginMaintenanceSnapshot(
            installed = active != null,
            activeVersion = active,
            currentBackupVersion = backupVersion(currentBackupFile),
            previousBackupVersion = backupVersion(previousBackupFile)
        )
    }

    suspend fun installFirstTrustedFromUri(uriText: String, originalName: String): SystemPluginValidationResult {
        val staged = copyUriToPending(uriText, originalName)
        return try {
            installFirstTrusted(staged, originalName)
        } finally {
            staged.delete()
        }
    }

    suspend fun installFirstTrusted(source: File, originalName: String): SystemPluginValidationResult {
        val validation = SystemPluginPackageValidator.validateForPluginCenterBootstrap(source, originalName)
        checkRuntimeSupported(validation.manifest)
        if (snapshot().installed) {
            throw PluginInstallException("SYSTEM_PLUGIN_ALREADY_INSTALLED", "Plugin Center is already installed")
        }
        val installed = stageVersion(source, validation)
        try {
            mountPackage(installed, installed.name)
            copyAtomically(installed, currentBackupFile)
            previousBackupFile.delete()
            writeState(validation.manifest.version)
            return validation
        } catch (error: Throwable) {
            stopActive()
            versionDir(validation.manifest.version).deleteRecursively()
            currentBackupFile.delete()
            throw error
        }
    }

    suspend fun stageUpgradeFromUri(uriText: String, originalName: String? = null): SystemPluginValidationResult {
        val uri = Uri.parse(uriText)
        val resolvedName = originalName?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolveDisplayName(uri)
            ?: throw PluginInstallException("SOURCE_NAME_UNAVAILABLE", "Unable to resolve selected system plugin file name")
        val staged = copyUriToPending(uriText, resolvedName)
        val validation = try {
            SystemPluginPackageValidator.validateForPluginCenterBootstrap(staged, resolvedName)
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
        checkRuntimeSupported(validation.manifest)
        val current = activeSession?.manifest?.version ?: readStateVersion()
        if (current != null && SemanticVersion.parse(validation.manifest.version)!! <= SemanticVersion.parse(current)!!) {
            staged.delete()
            throw PluginInstallException(
                "SYSTEM_UPGRADE_VERSION_NOT_NEWER",
                "Upgrade requires a newer Plugin Center version: current=$current candidate=${validation.manifest.version}"
            )
        }
        scope.launch {
            delay(MAINTENANCE_HANDOFF_DELAY_MS)
            runCatching { performUpgrade(staged, validation) }
                .onFailure { AppLogger.e(TAG, "Plugin Center upgrade failed", it) }
        }
        return validation
    }

    fun requestRepair() {
        if (!currentBackupFile.isFile) {
            throw PluginInstallException("SYSTEM_CURRENT_BACKUP_MISSING", "Current Plugin Center backup is unavailable")
        }
        scope.launch {
            delay(MAINTENANCE_HANDOFF_DELAY_MS)
            runCatching { performRepair() }
                .onFailure { AppLogger.e(TAG, "Plugin Center repair failed", it) }
        }
    }

    fun requestRollback() {
        if (!previousBackupFile.isFile) {
            throw PluginInstallException("SYSTEM_PREVIOUS_BACKUP_MISSING", "Previous Plugin Center backup is unavailable")
        }
        scope.launch {
            delay(MAINTENANCE_HANDOFF_DELAY_MS)
            runCatching { performRollback() }
                .onFailure { AppLogger.e(TAG, "Plugin Center rollback failed", it) }
        }
    }

    private suspend fun performUpgrade(staged: File, validation: SystemPluginValidationResult) {
        val previousActiveVersion = activeSession?.manifest?.version ?: readStateVersion()
        val previousPackage = previousActiveVersion?.let(::packageFile)?.takeIf(File::isFile)
        val candidate = stageVersion(staged, validation)
        staged.delete()
        stopActive()
        try {
            mountPackage(candidate, candidate.name)
            if (currentBackupFile.isFile) copyAtomically(currentBackupFile, previousBackupFile)
            copyAtomically(candidate, currentBackupFile)
            writeState(validation.manifest.version)
        } catch (error: Throwable) {
            stopActive()
            versionDir(validation.manifest.version).deleteRecursively()
            if (previousPackage != null) {
                runCatching { mountPackage(previousPackage, previousPackage.name) }
                if (previousActiveVersion != null) writeState(previousActiveVersion)
            }
            throw error
        }
    }

    private suspend fun performRepair() {
        val backup = currentBackupFile
        val validation = SystemPluginPackageValidator.validateForPluginCenterBootstrap(backup, backup.name)
        checkRuntimeSupported(validation.manifest)
        val oldVersion = activeSession?.manifest?.version ?: readStateVersion()
        val fallback = oldVersion?.let(::packageFile)?.takeIf(File::isFile)
        stopActive()
        versionDir(validation.manifest.version).deleteRecursively()
        val installed = stageVersion(backup, validation)
        try {
            mountPackage(installed, installed.name)
            writeState(validation.manifest.version)
        } catch (error: Throwable) {
            stopActive()
            if (fallback != null && fallback.isFile) runCatching { mountPackage(fallback, fallback.name) }
            throw error
        }
    }

    private suspend fun performRollback() {
        val rollbackSource = previousBackupFile
        val validation = SystemPluginPackageValidator.validateForPluginCenterBootstrap(rollbackSource, rollbackSource.name)
        checkRuntimeSupported(validation.manifest)
        val badVersion = activeSession?.manifest?.version ?: readStateVersion()
        val badPackage = badVersion?.let(::packageFile)?.takeIf(File::isFile)
        stopActive()
        val installed = stageVersion(rollbackSource, validation)
        try {
            mountPackage(installed, installed.name)
            currentBackupFile.delete()
            copyAtomically(rollbackSource, currentBackupFile)
            previousBackupFile.delete()
            writeState(validation.manifest.version)
            if (badVersion != null && badVersion != validation.manifest.version) {
                versionDir(badVersion).deleteRecursively()
            }
        } catch (error: Throwable) {
            stopActive()
            if (badPackage != null && badPackage.isFile) runCatching { mountPackage(badPackage, badPackage.name) }
            throw error
        }
    }

    private fun stageVersion(source: File, validation: SystemPluginValidationResult): File {
        val dir = versionDir(validation.manifest.version)
        val content = File(dir, "content")
        dir.deleteRecursively()
        content.mkdirs()
        val storedPackage = File(dir, "PluginCenter${SystemPluginProtocolV1.PACKAGE_EXTENSION}")
        copyAtomically(source, storedPackage)
        extractValidatedPackage(storedPackage, content)
        return storedPackage
    }

    private fun mountPackage(packageFile: File, originalName: String) {
        val validation = SystemPluginPackageValidator.validateForPluginCenterBootstrap(packageFile, originalName)
        val manifest = validation.manifest
        checkRuntimeSupported(manifest)
        val entryClass = manifest.runtime.entryClass
            ?: throw PluginInstallException("RUNTIME_ENTRY_CLASS_MISSING", "Plugin Center runtime entry class is missing")
        val content = File(versionDir(manifest.version), "content")
        if (!content.isDirectory) extractValidatedPackage(packageFile, content)
        val apk = File(content, manifest.runtime.entry).canonicalFile
        val canonicalContent = content.canonicalFile
        if (!apk.isFile || !apk.path.startsWith(canonicalContent.path + File.separator)) {
            throw PluginInstallException("RUNTIME_ENTRY_MISSING", "Plugin Center runtime APK is missing")
        }
        val optimized = File(appContext.codeCacheDir, "system_plugins/${manifest.pluginId}/${manifest.version}").apply { mkdirs() }
        val loader = DexClassLoader(apk.absolutePath, optimized.absolutePath, null, appContext.classLoader)
        val entry = loader.loadClass(entryClass).getDeclaredConstructor().newInstance() as? SystemPluginEntryV1
            ?: throw PluginInstallException("SYSTEM_ENTRY_TYPE_INVALID", "$entryClass does not implement SystemPluginEntryV1")
        val host = hostFactory(manifest.pluginId, manifest.role)
        val handle = entry.mount(host)
        if (!uiRegistry.hasEntryForOwner(manifest.pluginId)) {
            runCatching { handle.close() }
            throw PluginInstallException("SYSTEM_UI_HEALTH_FAILED", "Plugin Center mounted without a Toolbox UI entry")
        }
        activeSession = ActiveSession(manifest, handle, loader)
        AppLogger.i(TAG, "Plugin Center mounted: ${manifest.pluginId}@${manifest.version}")
    }

    private fun stopActive() {
        val session = activeSession ?: return
        activeSession = null
        runCatching { session.handle.close() }
            .onFailure { AppLogger.w(TAG, "Plugin Center stop failed", it) }
    }

    private fun extractValidatedPackage(packageFile: File, destination: File) {
        destination.deleteRecursively()
        destination.mkdirs()
        val rootCanonical = destination.canonicalFile
        ZipFile(packageFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val target = File(rootCanonical, entry.name).canonicalFile
                if (!target.path.startsWith(rootCanonical.path + File.separator)) {
                    throw PluginInstallException("SYSTEM_ARCHIVE_PATH_INVALID", "Unsafe system plugin path: ${entry.name}")
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().buffered().use(input::copyTo) }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun copyUriToPending(uriText: String, originalName: String): File {
        val uri = Uri.parse(uriText)
        val target = File(pendingDir, "upgrade-${System.nanoTime()}${SystemPluginProtocolV1.PACKAGE_EXTENSION}")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_PACKAGE_BYTES) throw PluginInstallException("PACKAGE_TOO_LARGE", "$originalName exceeds 512 MiB")
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw PluginInstallException("SOURCE_OPEN_FAILED", "Unable to read selected system plugin package")
        return target
    }

    private fun checkRuntimeSupported(manifest: SystemPluginManifestV1) {
        if (manifest.runtime.kind != "android_inprocess") {
            throw PluginInstallException("SYSTEM_RUNTIME_NOT_EXECUTABLE", "Plugin Center V1 requires android_inprocess runtime")
        }
    }

    private fun readStateVersion(): String? = runCatching {
        if (!stateFile.isFile) null else JSONObject(stateFile.readText()).optString("active_version").trim().ifBlank { null }
    }.getOrNull()

    private fun writeState(version: String) {
        root.mkdirs()
        atomicWrite(stateFile, JSONObject().put("active_version", version).toString(2).toByteArray())
    }

    private fun backupVersion(file: File): String? = runCatching {
        if (!file.isFile) null
        else SystemPluginPackageValidator.validateForPluginCenterBootstrap(file, file.name).manifest.version
    }.getOrNull()

    private fun versionDir(version: String) = File(versionsDir, version)
    private fun packageFile(version: String) = File(versionDir(version), "PluginCenter${SystemPluginProtocolV1.PACKAGE_EXTENSION}")
    private val currentBackupFile get() = File(backupDir, "current${SystemPluginProtocolV1.PACKAGE_EXTENSION}")
    private val previousBackupFile get() = File(backupDir, "previous${SystemPluginProtocolV1.PACKAGE_EXTENSION}")

    fun shutdown() {
        stopActive()
    }

    private fun copyAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        source.inputStream().buffered().use { input -> temp.outputStream().buffered().use(input::copyTo) }
        if (target.exists() && !target.delete()) throw PluginInstallException("SYSTEM_STORE_REPLACE_FAILED", "Unable to replace ${target.name}")
        if (!temp.renameTo(target)) {
            temp.delete()
            throw PluginInstallException("SYSTEM_STORE_COMMIT_FAILED", "Unable to commit ${target.name}")
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) throw PluginInstallException("SYSTEM_STATE_COMMIT_FAILED", "Unable to commit system plugin state")
    }

    companion object {
        private const val TAG = "SystemPluginController"
        private const val MAINTENANCE_HANDOFF_DELAY_MS = 350L
        private const val MAX_PACKAGE_BYTES = 512L * 1024L * 1024L
    }
}
