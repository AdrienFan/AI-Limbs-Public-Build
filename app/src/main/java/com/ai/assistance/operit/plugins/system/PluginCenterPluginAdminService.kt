package com.ai.assistance.operit.plugins.system

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.ai.assistance.operit.plugins.center.HostSurfacePolicy
import com.ai.assistance.operit.plugins.center.InactivityThresholdMode
import com.ai.assistance.operit.plugins.center.PluginBackupPolicyStore
import com.ai.assistance.operit.plugins.center.PluginBackupSnapshot
import com.ai.assistance.operit.plugins.center.PluginInactivityPolicyStore
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginInstallMetadata
import com.ai.assistance.operit.plugins.center.PluginInstallOptions
import com.ai.assistance.operit.plugins.center.PluginLifecycleState
import com.ai.assistance.operit.plugins.center.PluginManager
import com.ai.assistance.operit.plugins.center.PluginManifest
import com.ai.assistance.operit.plugins.center.PluginPackageInspector
import com.ai.assistance.operit.plugins.center.PluginPackageVerifier
import com.ai.assistance.operit.plugins.center.PluginPersistentState
import com.ai.assistance.operit.plugins.center.PluginSnapshot
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class KernelPluginAdminJsonServiceV1(
    context: Context,
    private val manager: PluginManager,
    private val surfacePolicy: HostSurfacePolicy,
    private val inactivityPolicy: PluginInactivityPolicyStore,
    private val backupPolicy: PluginBackupPolicyStore
) : SystemJsonServiceV1 {
    private val appContext = context.applicationContext
    private val importDir = File(appContext.cacheDir, "plugin-center-imports").apply { mkdirs() }

    override suspend fun call(
        operation: String,
        parameters: JSONObject
    ): JSONObject = when (operation.trim()) {
        "snapshots" -> JSONObject().put(
            "plugins",
            JSONArray(manager.snapshots().map(::snapshotJson))
        )
        "snapshot" -> snapshotJson(
            manager.snapshot(parameters.requireAdminText("plugin_id"))
        )
        "inspect_uri" -> withUriPackage(parameters) { file, sourceName ->
            JSONObject()
                .put("source_name", sourceName)
                .put("manifest", manifestJson(PluginPackageInspector.inspect(file)))
        }
        "install_uri" -> withUriPackage(parameters) { file, _ ->
            installUri(file, parameters)
        }
        "enable" -> stateJson(manager.enable(parameters.requireAdminText("plugin_id")))
        "disable" -> stateJson(
            manager.disable(
                parameters.requireAdminText("plugin_id"),
                parameters.optBoolean("admin_authorized", false)
            )
        )
        "activate_version" -> stateJson(
            manager.activateVersion(
                parameters.requireAdminText("plugin_id"),
                parameters.requireAdminText("version")
            )
        )
        "rollback" -> stateJson(
            manager.rollback(parameters.requireAdminText("plugin_id"))
        )
        "uninstall" -> {
            manager.uninstall(
                pluginId = parameters.requireAdminText("plugin_id"),
                removeData = parameters.optBoolean("remove_data", false),
                adminAuthorized = parameters.optBoolean("admin_authorized", false)
            )
            JSONObject().put("ok", true)
        }
        "host_surfaces" -> JSONObject().put(
            "surfaces",
            JSONArray(surfacePolicy.snapshots().map { item ->
                JSONObject()
                    .put("id", item.definition.id)
                    .put("title", item.definition.title)
                    .put("detail", item.definition.detail)
                    .put("kind", item.definition.kind.name)
                    .put("required_scope", item.definition.requiredScope ?: JSONObject.NULL)
                    .put("public_contracts", JSONArray(item.definition.publicContracts))
                    .put("allowed", item.allowed)
            })
        )
        "set_host_surface" -> {
            surfacePolicy.setAllowed(
                parameters.requireAdminText("surface_id"),
                parameters.getBoolean("allowed")
            )
            manager.reconcileHostSurfacePolicy()
            JSONObject().put("ok", true)
        }
        "set_host_surfaces" -> {
            val ids = parameters.optJSONArray("surface_ids").toAdminStringSet()
            surfacePolicy.setAllowed(ids, parameters.getBoolean("allowed"))
            manager.reconcileHostSurfacePolicy()
            JSONObject().put("ok", true)
        }
        "inactivity_status" -> inactivityPolicy.snapshot().let { policy ->
            JSONObject()
                .put("enabled", policy.enabled)
                .put("mode", policy.mode.name)
                .put("days", policy.days)
                .put("test_seconds", policy.testSeconds)
                .put("enabled_at", policy.enabledAtEpochMs)
        }
        "configure_inactivity" -> {
            val mode = runCatching {
                InactivityThresholdMode.valueOf(parameters.requireAdminText("mode"))
            }.getOrElse {
                throw PluginInstallException("INACTIVITY_MODE_INVALID", "Invalid inactivity mode")
            }
            inactivityPolicy.configure(
                enabled = parameters.getBoolean("enabled"),
                mode = mode,
                days = parameters.getInt("days"),
                testSeconds = parameters.getInt("test_seconds")
            )
            manager.reconcileInactivityPolicy()
            JSONObject().put("ok", true)
        }
        "run_inactivity_check" -> {
            manager.reconcileInactivityPolicy()
            JSONObject().put("ok", true)
        }
        "backup_policy_status" -> JSONObject()
            .put("enabled", backupPolicy.snapshot().enabled)
        "configure_backup_policy" -> {
            backupPolicy.configure(parameters.getBoolean("enabled"))
            manager.reconcileBackupPolicy()
            JSONObject().put("ok", true)
        }
        "backups" -> JSONObject().put(
            "backups",
            JSONArray(manager.backupSnapshots().map(::backupJson))
        )
        "export_backups" -> exportBackups(parameters)
        "backup" -> backupJson(
            manager.backup(parameters.requireAdminText("plugin_id"))
        )
        "restore_backup" -> stateJson(
            manager.restoreBackup(parameters.requireAdminText("plugin_id"))
        )
        "delete_backup" -> {
            manager.deleteBackup(parameters.requireAdminText("plugin_id"))
            JSONObject().put("ok", true)
        }
        else -> throw PluginInstallException(
            "PLUGIN_ADMIN_OPERATION_UNKNOWN",
            "Unknown plugin administration operation: $operation"
        )
    }
    /**
     * Exports already-created backup packages to a user-granted document tree.
     *
     * Plugin Center selects the destination through Android SAF, but the Stable Kernel-owned backup
     * store remains the only code allowed to read the private backup package file. This keeps export
     * as a narrow copy operation rather than exposing internal backup paths to the system plugin.
     */
    private suspend fun exportBackups(parameters: JSONObject): JSONObject {
        val pluginIds = parameters.optJSONArray("plugin_ids").toAdminStringSet()
        if (pluginIds.isEmpty()) {
            throw PluginInstallException("BACKUP_EXPORT_EMPTY", "At least one plugin backup must be selected")
        }
        val treeUri = Uri.parse(parameters.requireAdminText("tree_uri"))
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw PluginInstallException("BACKUP_EXPORT_TREE_INVALID", "Selected export directory is invalid", it) }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val snapshots = manager.backupSnapshots().associateBy { it.pluginId }
        val exported = JSONArray()
        pluginIds.sorted().forEach { pluginId ->
            val backup = snapshots[pluginId]
                ?: throw PluginInstallException("BACKUP_EXPORT_MISSING", "Plugin backup does not exist: $pluginId")
            if (!backup.packageFile.isFile || PluginPackageVerifier.sha256(backup.packageFile) != backup.packageSha256) {
                throw PluginInstallException("BACKUP_EXPORT_DIGEST_MISMATCH", "Plugin backup is missing or corrupted: $pluginId")
            }
            val fileName = exportFileName(backup.manifest.display.name, backup.version, ".ailp")
            val targetUri = DocumentsContract.createDocument(
                appContext.contentResolver,
                parentUri,
                "application/zip",
                fileName
            ) ?: throw PluginInstallException("BACKUP_EXPORT_CREATE_FAILED", "Could not create exported backup: $fileName")
            val output = appContext.contentResolver.openOutputStream(targetUri, "w")
                ?: throw PluginInstallException("BACKUP_EXPORT_OPEN_FAILED", "Could not open exported backup: $fileName")
            output.use { destination -> backup.packageFile.inputStream().buffered().use { it.copyTo(destination) } }
            exported.put(fileName)
        }
        return JSONObject().put("ok", true).put("count", exported.length()).put("files", exported)
    }

    private fun exportFileName(displayName: String, version: String, suffix: String): String {
        val safeName = displayName.trim()
            .map { char -> if (char.isISOControl() || char in "\\/:*?\"<>|") '_' else char }
            .joinToString("")
            .trim(' ', '.')
            .ifBlank { "plugin-backup" }
            .take(96)
        val safeVersion = version.trim().replace(Regex("[^0-9A-Za-z._+-]+"), "_").ifBlank { "unknown" }
        return "$safeName v$safeVersion$suffix"
    }

    private suspend fun installUri(
        file: File,
        parameters: JSONObject
    ): JSONObject {
        val result = manager.install(
            file,
            PluginInstallOptions(
                allowUntrustedForDevelopment = parameters.optBoolean(
                    "allow_untrusted_for_development",
                    false
                ),
                enableAfterInstall = parameters.optBoolean("enable_after_install", false),
                approvedScopes = parameters.optJSONArray("approved_scopes").toAdminStringSet()
            )
        )
        return JSONObject()
            .put("disposition", result.disposition.name)
            .put("plugin_id", result.pluginId)
            .put("version", result.version)
            .put("package_sha256", result.packageSha256)
            .put("state", stateJson(result.state))
    }

    private suspend fun <T> withUriPackage(
        parameters: JSONObject,
        block: suspend (File, String) -> T
    ): T {
        val uri = Uri.parse(parameters.requireAdminText("uri"))
        val sourceName = resolveDisplayName(uri)
            ?: throw PluginInstallException("SOURCE_NAME_UNAVAILABLE", "Unable to resolve selected plugin file name")
        if (!sourceName.lowercase().endsWith(".ailp")) {
            throw PluginInstallException(
                "PACKAGE_EXTENSION_INVALID",
                "Plugin package must use .ailp"
            )
        }
        val target = File(importDir, "import-${System.nanoTime()}.ailp")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw PluginInstallException("SOURCE_OPEN_FAILED", "Unable to read selected plugin package")
            input.use { source ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) {
                            throw PluginInstallException("PACKAGE_TOO_LARGE", "Plugin package exceeds 512 MiB")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return block(target, sourceName)
        } finally {
            target.delete()
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

    private fun snapshotJson(snapshot: PluginSnapshot): JSONObject {
        val state = snapshot.persistentState
        val health = when (state?.lastState) {
            PluginLifecycleState.FAILED,
            PluginLifecycleState.QUARANTINED -> "FAILED"
            PluginLifecycleState.MOUNTING,
            PluginLifecycleState.UNMOUNTING,
            PluginLifecycleState.PENDING_RESTART,
            PluginLifecycleState.BLOCKED -> "ATTENTION"
            else -> "OK"
        }
        return JSONObject()
            .put("plugin_id", snapshot.pluginId)
            .put("versions", JSONArray(snapshot.versions))
            .put("state", state?.let(::stateJson) ?: JSONObject.NULL)
            .put("manifest", snapshot.activeManifest?.let(::manifestJson) ?: JSONObject.NULL)
            .put("install_identity", snapshot.installMetadata?.let(::installIdentityJson) ?: JSONObject.NULL)
            .put("health", health)
            .put(
                "usage",
                JSONObject()
                    .put("use_count", snapshot.usage.useCount)
                    .put("last_used_at", snapshot.usage.lastUsedAtEpochMs ?: JSONObject.NULL)
            )
            .put("backup", snapshot.backup?.let(::backupJson) ?: JSONObject.NULL)
            .put("mounted_version", snapshot.mountedVersion ?: JSONObject.NULL)
    }
    private fun installIdentityJson(metadata: PluginInstallMetadata): JSONObject = JSONObject()
        .put("plugin_id", metadata.pluginId)
        .put("version", metadata.version)
        .put("package_sha256", metadata.packageSha256)
        .put("trust_verdict", metadata.trustVerdict.name)
        .put("signer_id", metadata.signerId ?: JSONObject.NULL)
        .put("installed_at", metadata.installedAtEpochMs)

    private fun stateJson(state: PluginPersistentState): JSONObject = JSONObject()
        .put("plugin_id", state.pluginId)
        .put("active_version", state.activeVersion ?: JSONObject.NULL)
        .put("previous_version", state.previousVersion ?: JSONObject.NULL)
        .put("enabled", state.enabled)
        .put("last_state", state.lastState.name)
        .put("last_error", state.lastError ?: JSONObject.NULL)
        .put("quarantined_versions", JSONArray(state.quarantinedVersions.sorted()))
        .put("updated_at", state.updatedAtEpochMs)

    private fun backupJson(backup: PluginBackupSnapshot): JSONObject = JSONObject()
        .put("plugin_id", backup.pluginId)
        .put("version", backup.version)
        .put("package_sha256", backup.packageSha256)
        .put("backed_up_at", backup.backedUpAtEpochMs)
        .put("was_enabled", backup.wasEnabled)
        .put("installed", backup.installed)
        .put("installed_version", backup.installedVersion ?: JSONObject.NULL)
        .put("manifest", manifestJson(backup.manifest))

    private fun manifestJson(manifest: PluginManifest): JSONObject = JSONObject()
        .put("plugin_id", manifest.pluginId)
        .put("version", manifest.version)
        .put(
            "display",
            JSONObject()
                .put("name", manifest.display.name)
                .put("description", manifest.display.description ?: JSONObject.NULL)
                .put("icon_entry", manifest.display.iconEntry ?: JSONObject.NULL)
        )
        .put("roles", JSONArray(manifest.roles.sorted()))
        .put("activation_mode", manifest.activationMode.wireName)
        .put(
            "runtime",
            JSONObject()
                .put("kind", manifest.runtime.kind)
                .put("entry", manifest.runtime.entry ?: JSONObject.NULL)
        )
        .put("requested_scopes", JSONArray(manifest.permissions.requestedScopes.sorted()))
        .put("capabilities", JSONArray(manifest.provides.capabilities.sorted()))
        .put("services", JSONArray(manifest.provides.services.sorted()))
        .put("providers", JSONArray(manifest.provides.providers.sorted()))
        .put(
            "dependencies",
            JSONObject()
                .put("plugins", JSONArray(manifest.dependencies.plugins.map { dependency ->
                    JSONObject()
                        .put("plugin_id", dependency.pluginId)
                        .put("min_version", dependency.minVersion ?: JSONObject.NULL)
                }))
                .put("services", JSONArray(manifest.dependencies.services.map { dependency ->
                    JSONObject()
                        .put("service_id", dependency.serviceId)
                        .put("min_api", dependency.minApi ?: JSONObject.NULL)
                }))
        )
        .put(
            "extensions",
            JSONArray(manifest.provides.extensions.map { extension ->
                JSONObject()
                    .put("point", extension.point)
                    .put("id", extension.id)
                    .put("api_version", extension.apiVersion)
            })
        )

    companion object {
        private const val MAX_IMPORT_BYTES = 512L * 1024L * 1024L
    }
}

private fun JSONObject.requireAdminText(key: String): String =
    optString(key).trim().takeIf { it.isNotEmpty() }
        ?: throw PluginInstallException("FIELD_MISSING", "$key is required")

private fun JSONArray?.toAdminStringSet(): Set<String> = buildSet {
    val array = this@toAdminStringSet ?: return@buildSet
    for (index in 0 until array.length()) {
        array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
    }
}
