package com.ai.assistance.operit.plugins.center

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

data class PluginPersistentState(
    val pluginId: String,
    val activeVersion: String?,
    val previousVersion: String?,
    val enabled: Boolean,
    val lastState: PluginLifecycleState,
    val lastError: String?,
    val quarantinedVersions: Set<String>,
    val updatedAtEpochMs: Long
)

data class PluginInstallMetadata(
    val pluginId: String,
    val version: String,
    val packageSha256: String,
    val installedAtEpochMs: Long,
    val trustVerdict: PluginTrustVerdict,
    val signerId: String?,
    val sourceFileName: String,
    val grantedScopes: Set<String>
)

class PluginStateRepository(
    private val store: PluginStore
) {
    fun read(pluginId: String): PluginPersistentState? {
        val file = store.stateFile(pluginId)
        parseState(file, pluginId)?.let { return it }
        return recoverInterruptedState(pluginId, file)
    }

    private fun parseState(file: File, expectedPluginId: String): PluginPersistentState? {
        if (!file.isFile) return null
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val storedPluginId = root.optString("plugin_id").trim()
        if (storedPluginId.isNotEmpty() && storedPluginId != expectedPluginId) return null
        val activeVersion = root.stringOrNull("active_version")
        val previousVersion = root.stringOrNull("previous_version")
        val lastState = runCatching { PluginLifecycleState.valueOf(root.optString("last_state")) }
            .getOrDefault(PluginLifecycleState.INSTALLED)
        return PluginPersistentState(
            pluginId = expectedPluginId,
            activeVersion = activeVersion,
            previousVersion = previousVersion,
            enabled = root.optBoolean("enabled", false),
            lastState = lastState,
            lastError = root.stringOrNull("last_error"),
            quarantinedVersions = root.optJSONArray("quarantined_versions").toStringSet(),
            updatedAtEpochMs = root.optLong("updated_at", 0L)
        )
    }

    private fun recoverInterruptedState(pluginId: String, target: File): PluginPersistentState? {
        val parent = target.parentFile ?: return null
        val now = System.currentTimeMillis()
        val prefix = ".${target.name}."
        val candidates = parent.listFiles()
            ?.asSequence()
            ?.filter { candidate ->
                candidate.isFile && candidate.name.startsWith(prefix) && candidate.name.endsWith(".tmp") &&
                    now - candidate.lastModified() >= ORPHAN_RECOVERY_MIN_AGE_MS
            }
            ?.sortedByDescending(File::lastModified)
            ?.toList()
            .orEmpty()
        for (candidate in candidates) {
            val recovered = parseState(candidate, pluginId) ?: continue
            val activeVersion = recovered.activeVersion
            if (activeVersion != null && !store.versionDir(pluginId, activeVersion).isDirectory) continue
            val promoted = runCatching { replaceAtomically(candidate, target) }.isSuccess
            if (!promoted) continue
            parent.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".tmp") }
                ?.forEach(File::delete)
            return parseState(target, pluginId) ?: recovered
        }
        return null
    }

    fun write(state: PluginPersistentState) {
        val root = JSONObject()
            .put("plugin_id", state.pluginId)
            .put("active_version", state.activeVersion ?: JSONObject.NULL)
            .put("previous_version", state.previousVersion ?: JSONObject.NULL)
            .put("enabled", state.enabled)
            .put("last_state", state.lastState.name)
            .put("last_error", state.lastError ?: JSONObject.NULL)
            .put("quarantined_versions", JSONArray(state.quarantinedVersions.sorted()))
            .put("updated_at", state.updatedAtEpochMs)
        atomicWrite(store.stateFile(state.pluginId), root.toString(2))
    }

    fun readInstallMetadata(pluginId: String, version: String): PluginInstallMetadata? {
        val file = store.installMetadataIn(store.versionDir(pluginId, version))
        if (!file.isFile) return null
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        return PluginInstallMetadata(
            pluginId = root.optString("plugin_id"),
            version = root.optString("version"),
            packageSha256 = root.optString("package_sha256"),
            installedAtEpochMs = root.optLong("installed_at"),
            trustVerdict = runCatching { PluginTrustVerdict.valueOf(root.optString("trust_verdict")) }.getOrDefault(PluginTrustVerdict.UNSIGNED),
            signerId = root.optString("signer_id").trim().ifBlank { null },
            sourceFileName = root.optString("source_file_name"),
            grantedScopes = root.optJSONArray("granted_scopes").toStringSet()
        )
    }

    fun writeInstallMetadata(versionDir: File, metadata: PluginInstallMetadata) {
        val root = JSONObject()
            .put("plugin_id", metadata.pluginId)
            .put("version", metadata.version)
            .put("package_sha256", metadata.packageSha256)
            .put("installed_at", metadata.installedAtEpochMs)
            .put("trust_verdict", metadata.trustVerdict.name)
            .put("signer_id", metadata.signerId ?: JSONObject.NULL)
            .put("source_file_name", metadata.sourceFileName)
            .put("granted_scopes", JSONArray(metadata.grantedScopes.sorted()))
        atomicWrite(store.installMetadataIn(versionDir), root.toString(2))
    }

    fun readInstalledManifest(pluginId: String, version: String): PluginManifest {
        val file = File(store.contentIn(store.versionDir(pluginId, version)), PluginAbi.MANIFEST_ENTRY)
        if (!file.isFile) throw PluginInstallException("INSTALLED_MANIFEST_MISSING", "Installed plugin manifest is missing")
        return PluginManifestParser.parse(file.readText(Charsets.UTF_8))
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replaceAtomically(temp, target)
        } catch (error: Throwable) {
            temp.delete()
            throw PluginInstallException("STATE_WRITE_FAILED", "Could not atomically commit plugin state", error)
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    private companion object {
        const val ORPHAN_RECOVERY_MIN_AGE_MS = 5_000L
    }
}
