package com.ai.assistance.operit.plugins.center

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable Kernel-owned persistence for privileged official plugin identities.
 *
 * Plugin Center is the only control-plane caller allowed to mutate this registry. The Kernel still
 * enforces runtime kind, manifest-role binding and the official .ailp signer before executable code
 * may enter the app process. The legacy hard-coded identities below are migration seeds only.
 */
internal data class OfficialPluginIdentityRecord(
    val pluginId: String,
    val runtimeKind: String,
    val approvedRoles: Set<String>,
    val source: String
)

internal class OfficialPluginIdentityRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    init {
        ensureLegacyMigration()
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
            val records = readLocked().toMutableMap()
            records[record.pluginId] = record
            writeLocked(records)
        }
        return record
    }

    fun revoke(pluginId: String): Boolean = synchronized(lock) {
        val id = normalizePluginId(pluginId)
        val records = readLocked().toMutableMap()
        val removed = records.remove(id) != null
        if (removed) writeLocked(records)
        removed
    }

    fun restore(pluginId: String, previous: OfficialPluginIdentityRecord?) = synchronized(lock) {
        val id = normalizePluginId(pluginId)
        val records = readLocked().toMutableMap()
        if (previous == null) records.remove(id) else records[id] = previous
        writeLocked(records)
    }

    private fun ensureLegacyMigration() = synchronized(lock) {
        if (prefs.getBoolean(KEY_MIGRATED_V1, false)) return@synchronized
        val records = readLocked().toMutableMap()
        LEGACY_MIGRATION_SEEDS.forEach { record -> records.putIfAbsent(record.pluginId, record) }
        writeLocked(records)
        if (!prefs.edit().putBoolean(KEY_MIGRATED_V1, true).commit()) {
            throw PluginInstallException("OFFICIAL_IDENTITY_STORE_FAILED", "Could not persist identity migration state")
        }
    }

    private fun readLocked(): Map<String, OfficialPluginIdentityRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyMap()
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
        }.getOrDefault(emptyMap())
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
        if (!prefs.edit().putString(KEY_RECORDS, array.toString()).commit()) {
            throw PluginInstallException("OFFICIAL_IDENTITY_STORE_FAILED", "Could not persist official plugin identities")
        }
    }

    private fun normalizePluginId(raw: String): String = raw.trim().lowercase().also { value ->
        if (value.isBlank() || !PLUGIN_ID_PATTERN.matches(value)) {
            throw PluginInstallException("OFFICIAL_IDENTITY_ID_INVALID", "Invalid official plugin id: $raw")
        }
    }

    private fun normalizeRuntimeKind(raw: String): String = raw.trim().lowercase().also { value ->
        if (value.isBlank()) {
            throw PluginInstallException("OFFICIAL_IDENTITY_RUNTIME_INVALID", "Runtime kind is required")
        }
    }

    private fun normalizeRoles(raw: Collection<String>): Set<String> =
        raw.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSortedSet().also { roles ->
            if (roles.isEmpty()) {
                throw PluginInstallException("OFFICIAL_IDENTITY_ROLE_INVALID", "At least one approved role is required")
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
        private const val KEY_RECORDS = "records_json"
        private const val KEY_MIGRATED_V1 = "legacy_v1_migrated"
        private const val SOURCE_PLUGIN_CENTER = "plugin_center"
        private val PLUGIN_ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]{2,127}")
        private val LEGACY_MIGRATION_SEEDS = listOf(
            seed("plugin.system.extension_hub", "system_extension_hub"),
            seed("plugin.system.bridge", "system_bridge"),
            seed("plugin.system.developer_guide", "system_plugin"),
            seed("plugin.system.packager", "system_packager"),
            seed("plugin.system.ubuntu_terminal", "ubuntu_terminal")
        )

        private fun seed(pluginId: String, role: String) = OfficialPluginIdentityRecord(
            pluginId = pluginId,
            runtimeKind = RUNTIME_ANDROID_INPROCESS,
            approvedRoles = setOf(role),
            source = "legacy_migration_v1"
        )
    }
}
