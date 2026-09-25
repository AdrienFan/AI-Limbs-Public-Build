package com.ai.assistance.operit.plugins.center

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable Kernel-owned persistence for privileged official plugin identities.
 *
 * Plugin Center is the only control-plane caller allowed to mutate this registry. The Kernel still
 * enforces runtime kind, manifest-role binding and the official .ailp signer before executable code
 * may enter the app process. The legacy hard-coded identities below are migration seeds only.
 *
 * The authoritative record lives in an explicit file instead of SharedPreferences so Resident Core
 * and the isolated Worker observe the same identity approval immediately. SharedPreferences remains
 * only as a v1 migration source / compatibility mirror.
 */
internal data class OfficialPluginIdentityRecord(
    val pluginId: String,
    val runtimeKind: String,
    val approvedRoles: Set<String>,
    val source: String
)

internal class OfficialPluginIdentityFileStore(
    private val file: File
) {
    private val lockFile = File(file.parentFile, "${file.name}.lock")

    fun exists(): Boolean = file.isFile

    fun readRaw(): String? =
        if (!file.isFile) null else file.readText(Charsets.UTF_8)

    fun writeRaw(raw: String) {
        val parent = file.parentFile ?: error("Identity store has no parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not create official identity store directory"
            )
        }
        val temp = File(
            parent,
            ".${file.name}.${UUID.randomUUID()}.tmp"

        )
        try {
            FileOutputStream(temp).use { output ->
                output.write(raw.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (error: Throwable) {
            temp.delete()
            if (error is PluginInstallException) throw error
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not persist official plugin identities: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    fun <T> withExclusiveLock(block: () -> T): T {
        val parent = lockFile.parentFile ?: error("Identity lock has no parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not create official identity store directory"
            )
        }
        return RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    block()
                }
            }
        }
    }
}

internal class OfficialPluginIdentityRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val fileStore = OfficialPluginIdentityFileStore(
        File(
            appContext.filesDir,
            "ai_limbs/plugin_center/$STORE_FILE_NAME"
        )
    )
    private val lock = Any()

    init {
        synchronized(lock) {
            fileStore.withExclusiveLock {
                ensureFileStoreInitializedLocked()
            }
        }
        ensureLegacyMigration()
        ensureLogCenterIdentityMigration()
        ensureSystemEnvironmentCenterIdentityMigration()
        ensureRetiredIdentityMigration()
    }

    fun snapshot(): List<OfficialPluginIdentityRecord> = synchronized(lock) {
        readLocked().values.sortedBy { it.pluginId }
    }

    fun record(pluginId: String): OfficialPluginIdentityRecord? = synchronized(lock) {
        readLocked()[normalizePluginId(pluginId)]
    }

    fun isApproved(manifest: PluginManifest): Boolean {
        val record = record(manifest.pluginId) ?: return false
        return record.runtimeKind == normalizeRuntimeKind(manifest.runtime.kind) &&
            record.approvedRoles == normalizeRoles(manifest.roles)
    }

    fun isTrusted(manifest: PluginManifest, metadata: PluginInstallMetadata): Boolean =
        isApproved(manifest) &&
            metadata.pluginId == manifest.pluginId &&
            metadata.trustVerdict == PluginTrustVerdict.TRUSTED &&
            metadata.signerId == OFFICIAL_SIGNER_ID

    fun approve(
        pluginId: String,
        runtimeKind: String,
        roles: Collection<String>,
        source: String = SOURCE_PLUGIN_CENTER
    ): OfficialPluginIdentityRecord {
        val record = OfficialPluginIdentityRecord(
            pluginId = normalizePluginId(pluginId),
            runtimeKind = normalizeRuntimeKind(runtimeKind),
            approvedRoles = normalizeRoles(roles),
            source = source.trim().ifBlank { SOURCE_PLUGIN_CENTER }
        )
        if (record.runtimeKind != RUNTIME_ANDROID_INPROCESS) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_RUNTIME_UNSUPPORTED",
                "Official executable identity currently supports android_inprocess only"
            )
        }
        synchronized(lock) {
            fileStore.withExclusiveLock {
                val records = readLocked().toMutableMap()
                records[record.pluginId] = record
                writeLocked(records)
            }
        }
        return record
    }

    fun revoke(pluginId: String): Boolean = synchronized(lock) {
        fileStore.withExclusiveLock {
            val id = normalizePluginId(pluginId)
            val records = readLocked().toMutableMap()
            val removed = records.remove(id) != null
            if (removed) writeLocked(records)
            removed
        }
    }

    fun restore(pluginId: String, previous: OfficialPluginIdentityRecord?) = synchronized(lock) {
        fileStore.withExclusiveLock {
            val id = normalizePluginId(pluginId)
            val records = readLocked().toMutableMap()
            if (previous == null) records.remove(id) else records[id] = previous
            writeLocked(records)
        }
    }

    private fun ensureFileStoreInitializedLocked() {
        if (fileStore.exists()) return
        val legacyRaw = prefs.getString(KEY_RECORDS, null)
        fileStore.writeRaw(legacyRaw ?: "[]")
    }

    private fun ensureLegacyMigration() = synchronized(lock) {
        if (prefs.getBoolean(KEY_MIGRATED_V1, false)) return@synchronized
        fileStore.withExclusiveLock {
            val records = readLocked().toMutableMap()
            LEGACY_MIGRATION_SEEDS.forEach { record -> records.putIfAbsent(record.pluginId, record) }
            writeLocked(records)
        }
        if (!prefs.edit().putBoolean(KEY_MIGRATED_V1, true).commit()) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not persist identity migration state"
            )
        }
    }

    private fun ensureLogCenterIdentityMigration() = synchronized(lock) {
        if (prefs.getBoolean(KEY_LOG_CENTER_V1, false)) return@synchronized
        fileStore.withExclusiveLock {
            val records = readLocked().toMutableMap()
            val record = seed(
                pluginId = "plugin.system.log_center",
                role = "system_plugin",
                source = "log_center_migration_v1"
            )
            records.putIfAbsent(record.pluginId, record)
            writeLocked(records)
        }
        if (!prefs.edit().putBoolean(KEY_LOG_CENTER_V1, true).commit()) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not persist Log Center identity migration state"
            )
        }
    }

    private fun ensureSystemEnvironmentCenterIdentityMigration() = synchronized(lock) {
        if (prefs.getBoolean(KEY_SYSTEM_ENVIRONMENT_CENTER_V1, false)) return@synchronized
        fileStore.withExclusiveLock {
            val records = readLocked().toMutableMap()
            val record = seed(
                pluginId = "plugin.system.environment_center",
                role = "system_environment_center",
                source = "system_environment_center_migration_v1"
            )
            records.putIfAbsent(record.pluginId, record)
            writeLocked(records)
        }
        if (!prefs.edit().putBoolean(KEY_SYSTEM_ENVIRONMENT_CENTER_V1, true).commit()) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not persist System Environment Center identity migration state"
            )
        }
    }

    private fun ensureRetiredIdentityMigration() = synchronized(lock) {
        if (prefs.getBoolean(KEY_RETIRED_V1, false)) return@synchronized
        fileStore.withExclusiveLock {
            val records = readLocked().toMutableMap()
            RETIRED_PLUGIN_IDS.forEach(records::remove)
            writeLocked(records)
        }
        if (!prefs.edit().putBoolean(KEY_RETIRED_V1, true).commit()) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not persist retired identity migration state"
            )
        }
    }

    private fun readLocked(): Map<String, OfficialPluginIdentityRecord> {
        val raw = fileStore.readRaw()
            ?: prefs.getString(KEY_RECORDS, null)
            ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val record = OfficialPluginIdentityRecord(
                        pluginId = normalizePluginId(item.getString("plugin_id")),
                        runtimeKind = normalizeRuntimeKind(item.getString("runtime_kind")),
                        approvedRoles = normalizeRoles(item.optJSONArray("roles").toStringSet()),
                        source = item.optString("source", SOURCE_PLUGIN_CENTER)
                    )
                    put(record.pluginId, record)
                }
            }
        }.getOrElse { error ->
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_CORRUPT",
                "Could not parse official plugin identities: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun writeLocked(records: Map<String, OfficialPluginIdentityRecord>) {
        val array = JSONArray()
        records.values.sortedBy { it.pluginId }.forEach { record ->
            array.put(
                JSONObject()
                    .put("plugin_id", record.pluginId)
                    .put("runtime_kind", record.runtimeKind)
                    .put("roles", JSONArray(record.approvedRoles.sorted()))
                    .put("source", record.source)
            )
        }
        val raw = array.toString()
        fileStore.writeRaw(raw)

        // Keep the old preference as a compatibility mirror only. The explicit file above is
        // authoritative because Android SharedPreferences is not a reliable multi-process cache.
        if (!prefs.edit().putString(KEY_RECORDS, raw).commit()) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_STORE_FAILED",
                "Could not mirror official plugin identities to legacy preferences"
            )
        }
    }

    private fun normalizePluginId(raw: String): String = raw.trim().lowercase().also { value ->
        if (value.isBlank() || !PLUGIN_ID_PATTERN.matches(value)) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_ID_INVALID",
                "Invalid official plugin id: $raw"
            )
        }
    }

    private fun normalizeRuntimeKind(raw: String): String = raw.trim().lowercase().also { value ->
        if (value.isBlank()) {
            throw PluginInstallException(
                "OFFICIAL_IDENTITY_RUNTIME_INVALID",
                "Runtime kind is required"
            )
        }
    }

    private fun normalizeRoles(raw: Collection<String>): Set<String> =
        raw.map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSortedSet()
            .also { roles ->
                if (roles.isEmpty()) {
                    throw PluginInstallException(
                        "OFFICIAL_IDENTITY_ROLE_INVALID",
                        "At least one approved role is required"
                    )
                }
            }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) add(optString(index))
        }
    }

    companion object {
        const val OFFICIAL_SIGNER_ID = "ai-limbs-parent-plugin-dev-v1"
        const val RUNTIME_ANDROID_INPROCESS = "android_inprocess"

        private const val PREFS_NAME = "official_plugin_identity_registry"
        private const val STORE_FILE_NAME = "official_plugin_identity_registry_v2.json"
        private const val KEY_RECORDS = "records_json"
        private const val KEY_MIGRATED_V1 = "legacy_v1_migrated"
        private const val KEY_RETIRED_V1 = "retired_v1_migrated"
        private const val KEY_LOG_CENTER_V1 = "log_center_v1_migrated"
        private const val KEY_SYSTEM_ENVIRONMENT_CENTER_V1 = "system_environment_center_v1_migrated"
        private const val SOURCE_PLUGIN_CENTER = "plugin_center"
        private val PLUGIN_ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]{2,127}")
        private val RETIRED_PLUGIN_IDS = setOf("plugin.system.ubuntu_terminal")
        private val LEGACY_MIGRATION_SEEDS = listOf(
            seed("plugin.system.extension_hub", "system_extension_hub"),
            seed("plugin.system.bridge", "system_bridge"),
            seed("plugin.system.developer_guide", "system_plugin"),
            seed("plugin.system.packager", "system_packager")
        )

        private fun seed(
            pluginId: String,
            role: String,
            source: String = "legacy_migration_v1"
        ) = OfficialPluginIdentityRecord(
            pluginId = pluginId,
            runtimeKind = RUNTIME_ANDROID_INPROCESS,
            approvedRoles = setOf(role),
            source = source
        )
    }
}
