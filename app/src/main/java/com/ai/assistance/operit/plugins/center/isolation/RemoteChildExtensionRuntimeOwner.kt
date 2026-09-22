package com.ai.assistance.operit.plugins.center.isolation

import android.content.Context
import com.ai.assistance.operit.plugins.center.*
import com.ai.limbs.plugin.runtime.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Core facade for .ailx. Executable child code remains inside ail_plugin_runtime. */
internal class RemoteChildExtensionRuntimeOwner(
    context: Context,
    private val contributions: PluginContributionRegistry,
    private val capabilityRegistry: PluginCapabilityGateway
) : ChildExtensionRuntimeOwner {
    private data class Binding(val contribution: AutoCloseable, val capability: AutoCloseable) {
        fun close() { runCatching { capability.close() }; runCatching { contribution.close() } }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val snapshots = MutableStateFlow<List<ChildExtensionSnapshot>>(emptyList())
    private val backups = MutableStateFlow<List<ChildExtensionBackupSnapshot>>(emptyList())
    private val ui = MutableStateFlow<List<ChildUiContributionSnapshot>>(emptyList())
    @Volatile private var canonicalDescriptors: List<CanonicalChildDescriptor> = emptyList()
    private val points = ConcurrentHashMap<String, MutableStateFlow<List<ChildExtensionSnapshot>>>()
    private val uiProviders = ConcurrentHashMap<String, RemoteUi>()
    private val capabilityBindings = linkedMapOf<String, Binding>()
    @Volatile private var presentations = JSONArray()
    @Volatile private var runtime = JSONObject()
    @Volatile private var capabilityFingerprint = ""
    @Volatile private var started = false
    private var refreshJob: Job? = null

    override suspend fun start() {
        if (started) return
        started = true
        request("start_children")
        refresh()
        refreshJob = scope.launch {
            while (isActive && started) {
                delay(1_000L)
                runCatching { refresh() }
            }
        }
    }

    override suspend fun stop() {
        if (!started) return
        started = false
        refreshJob?.cancel(); refreshJob = null
        val state = runCatching { PluginRuntimeController.status(appContext) }.getOrNull()
        if (state?.optBoolean("available", false) == true) {
            runCatching { PluginRuntimeWire.request("stop_children", state.getString("session_id")) }
        }
        clearMirrors()
    }

    override fun bound(ownerPluginId: String, roles: Set<String>, grantedScopes: Set<String>): InProcessChildExtensionRuntime =
        object : InProcessChildExtensionRuntime {
            override fun publishPoint(point: String, apiVersion: Int, title: String, description: String,
                allowedHostCapabilities: Set<String>, binder: ChildExtensionBinder): AutoCloseable =
                throw PluginInstallException("REMOTE_CHILD_POINT_FORBIDDEN", "Parent points live in ail_plugin_runtime")

            override suspend fun installAdmitted(packageFile: File, expectedParentPluginId: String?, expectedPoint: String?): ChildExtensionSnapshot {
                check(ChildRuntimeAuthorityRoles.ADMISSION in roles) {
                    "Remote child admission requires an approved child admission authority role"
                }
                val value = request(
                    "child_install",
                    JSONObject()
                        .put("owner_plugin_id", ownerPluginId)
                        .put("package_path", packageFile.absolutePath)
                        .put("expected_parent_plugin_id", expectedParentPluginId ?: "")
                        .put("expected_point", expectedPoint ?: "")
                )
                refresh(); return parseChild(value)
            }
            override suspend fun uninstall(extensionId: String): Boolean { controller(roles); val v = control("uninstall", extensionId); refresh(); return v.getBoolean("removed") }
            override suspend fun setEnabled(extensionId: String, enabled: Boolean): ChildExtensionSnapshot { controller(roles); val v = control("set_enabled", extensionId, JSONObject().put("enabled", enabled)); refresh(); return parseChild(v) }
            override suspend fun backup(extensionId: String): ChildExtensionBackupSnapshot { controller(roles); val v = control("backup", extensionId); refresh(); return parseBackup(v) }
            override suspend fun restoreBackup(extensionId: String): ChildExtensionSnapshot { controller(roles); val v = control("restore_backup", extensionId); refresh(); return parseChild(v) }
            override suspend fun deleteBackup(extensionId: String): Boolean { controller(roles); val v = control("delete_backup", extensionId); refresh(); return v.getBoolean("deleted") }
            override fun versions(extensionId: String): List<String> { controller(roles); return snapshots.value.firstOrNull { it.extensionId == extensionId }?.let { listOf(it.version) }.orEmpty() }
            override fun retentionLimit(extensionId: String): Int { controller(roles); return 3 }
            override fun immediateRollbackVersion(extensionId: String): String? { controller(roles); return null }
            override suspend fun activateVersion(extensionId: String, version: String): ChildExtensionSnapshot { controller(roles); val v = control("activate_version", extensionId, JSONObject().put("version", version)); refresh(); return parseChild(v) }
            override suspend fun immediateRollback(extensionId: String): ChildExtensionSnapshot { controller(roles); val v = control("immediate_rollback", extensionId); refresh(); return parseChild(v) }
            override suspend fun deleteVersion(extensionId: String, version: String): Boolean { controller(roles); val v = control("delete_version", extensionId, JSONObject().put("version", version)); refresh(); return v.getBoolean("deleted") }
            override suspend fun setVersionRetention(extensionId: String, limit: Int) { controller(roles); control("set_version_retention", extensionId, JSONObject().put("limit", limit)); refresh() }
            override suspend fun setAutoBackupPolicy(enabled: Boolean, highFrequencyUseCount: Long) {
                controller(roles); control("set_auto_backup_policy", "", JSONObject().put("enabled", enabled).put("high_frequency_use_count", highFrequencyUseCount)); refresh()
            }
            override fun recordUse(extensionId: String) { scope.launch { runCatching { control("record_use", extensionId); refresh() } } }
            override fun snapshots(): StateFlow<List<ChildExtensionSnapshot>> { controller(roles); return snapshots.asStateFlow() }
            override fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>> {
                controller(roles); return points.computeIfAbsent(point) { MutableStateFlow(snapshots.value.filter { it.target.point == point }) }.asStateFlow()
            }
            override fun backupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>> { controller(roles); return backups.asStateFlow() }
            override fun uiContributions(): StateFlow<List<ChildUiContributionSnapshot>> { controller(roles); return ui.asStateFlow() }
        }

    override suspend fun exportBackups(extensionIds: Collection<String>, treeUriRaw: String): List<String> {
        val a = request("child_export_backups", JSONObject().put("extension_ids", JSONArray(extensionIds.toList())).put("tree_uri", treeUriRaw)).getJSONArray("exported")
        return buildList { for (i in 0 until a.length()) add(a.getString(i)) }
    }
    override fun loggingSnapshots() = snapshots.value.toList()
    override fun loggingBackupSnapshots() = backups.value.toList()
    override fun loggingUiContributions() = ui.value.toList()
    override fun loggingCanonicalDescriptors() = canonicalDescriptors.toList()
    override fun residentPresentationDescriptors() = JSONArray(presentations.toString())

    override suspend fun awaitEnabledPointReady(point: String, timeoutMs: Long) {
        request(
            "await_enabled_point_ready",
            JSONObject().put("point", point).put("timeout_ms", timeoutMs)
        )
        refresh()
    }

    override suspend fun awaitBusinessChildrenReady(timeoutMs: Long): JSONObject {
        val ready = request(
            "await_business_children_ready",
            JSONObject().put("timeout_ms", timeoutMs)
        )
        refresh()
        return ready
    }

    override suspend fun invokePresentationCommand(extensionId: String, command: String, parameters: JSONObject): JSONObject =
        request("child_presentation_command", JSONObject().put("extension_id", extensionId).put("command", command).put("parameters", JSONObject(parameters.toString())))

    private suspend fun control(operation: String, extensionId: String, extra: JSONObject = JSONObject()): JSONObject =
        request("child_control", JSONObject(extra.toString()).put("child_operation", operation).put("extension_id", extensionId))

    private suspend fun request(operation: String, payload: JSONObject = JSONObject()): JSONObject {
        val state = PluginRuntimeController.probe(appContext)
        check(state.optBoolean("consistent", false)) { "Plugin runtime is not attested: $state" }
        return PluginRuntimeWire.request(operation, state.getString("session_id"), payload, PluginRuntimeWire.BUSINESS_TIMEOUT_MS)
            .getJSONObject("operation_result")
    }

    private suspend fun refresh() = refreshMutex.withLock {
        if (!started) return@withLock
        val value = request("child_snapshot")
        runtime = JSONObject(value.optJSONObject("runtime")?.toString() ?: "{}")
        presentations = JSONArray(value.optJSONArray("presentations")?.toString() ?: "[]")
        val childValues = value.optJSONArray("children") ?: JSONArray()
        val backupValues = value.optJSONArray("backups") ?: JSONArray()
        val parsedChildren = buildList { for (i in 0 until childValues.length()) add(parseChild(childValues.getJSONObject(i))) }
        snapshots.value = parsedChildren
        backups.value = buildList { for (i in 0 until backupValues.length()) add(parseBackup(backupValues.getJSONObject(i))) }
        points.forEach { (point, flow) -> flow.value = parsedChildren.filter { it.target.point == point } }
        val descriptorValues = value.optJSONArray("descriptors") ?: JSONArray()
        val envelopes = buildList {
            for (index in 0 until descriptorValues.length()) {
                add(CanonicalChildDescriptorEnvelopeCodec.decode(descriptorValues.getJSONObject(index)))
            }
        }
        canonicalDescriptors = envelopes.map { it.descriptor }
        reconcileUi(envelopes.filter {
            it.descriptor.kind == CanonicalChildDescriptorKind.UI_CONTRIBUTION
        })
        reconcileCapabilities(envelopes.filter {
            it.descriptor.kind == CanonicalChildDescriptorKind.CAPABILITY
        })
    }

    private fun reconcileCapabilities(values: List<CanonicalChildDescriptorEnvelope>) {
        val fingerprint = JSONArray(values.map { CanonicalChildDescriptorEnvelopeCodec.encode(it) }).toString()
        if (fingerprint == capabilityFingerprint) return
        synchronized(capabilityBindings) {
            capabilityBindings.values.forEach(Binding::close)
            capabilityBindings.clear()
            values.forEach { envelope ->
                val descriptor = envelope.descriptor
                val owner = descriptor.ownerId
                val id = descriptor.id
                val spec = capabilitySpec(envelope.payload, owner, id)
                val contribution = contributions.register(
                    PluginContributionRecord(
                        contract = CanonicalContributionContracts.capability(owner, id),
                        payload = spec
                    )
                )
                val capability = try {
                    capabilityRegistry.register(owner, id, spec)
                } catch (error: Throwable) {
                    contribution.close()
                    throw error
                }
                capabilityBindings[owner + "|" + id] = Binding(contribution, capability)
            }
            capabilityFingerprint = fingerprint
        }
    }

    private fun capabilitySpec(d: JSONObject, owner: String, id: String): PluginCapabilitySpec =
        PluginCapabilitySpec(
            displayName = d.getString("display_name"), description = d.optString("description", ""),
            invokeAliases = d.strings("invoke_aliases"), keywords = d.strings("keywords"),
            parameters = buildList {
                val a = d.optJSONArray("parameters") ?: JSONArray()
                for (i in 0 until a.length()) { val p = a.getJSONObject(i); add(PluginCapabilityParameterSpec(p.getString("name"), p.optString("type", "string"), p.optString("description", ""), p.optBoolean("required", true), p.nullable("default"))) }
            },
            suggestedParamsJson = d.nullable("suggested_params_json"), inputSchema = d.nullable("input_schema"),
            effect = PluginCapabilityEffect.valueOf(d.getString("effect")), domain = PluginCapabilityDomain.valueOf(d.getString("domain")),
            workContextRequiredReceipts = d.strings("work_receipts").mapTo(linkedSetOf()) { PluginCapabilityReceipt.valueOf(it) },
            executor = PluginCapabilityExecutor { params -> refresh(); request("invoke_capability", JSONObject().put("plugin_id", owner).put("capability_id", id).put("parameters", JSONObject(params.toString()))) }
        )

    private fun reconcileUi(values: List<CanonicalChildDescriptorEnvelope>) {
        val live = linkedSetOf<String>()
        ui.value = buildList {
            values.forEach { envelope ->
                val descriptor = envelope.descriptor
                val extensionId = descriptor.ownerId
                val contributionId = descriptor.id
                val key = extensionId + "|" + contributionId
                live += key
                val provider = uiProviders.computeIfAbsent(key) {
                    RemoteUi(extensionId, contributionId, envelope.payload.nullable("document_json"))
                }
                provider.update(envelope.payload.nullable("document_json"))
                add(
                    ChildUiContributionSnapshot(
                        extensionId = extensionId,
                        target = descriptor.target,
                        screenId = descriptor.metadata.getValue("screen_id"),
                        componentId = descriptor.metadata.getValue("component_id"),
                        slotId = descriptor.metadata.getValue("slot_id"),
                        contributionId = contributionId,
                        provider = provider
                    )
                )
            }
        }
        uiProviders.keys.removeIf { it !in live }
    }

    private inner class RemoteUi(private val extensionId: String, private val contributionId: String, initial: String?) : InProcessUiContributionProvider {
        private val state = MutableStateFlow(initial)
        override val documentJson: StateFlow<String?> = state.asStateFlow()
        fun update(value: String?) { state.value = value }
        override suspend fun perform(eventId: String, payloadJson: String): String {
            val result = request("child_ui_event", JSONObject().put("extension_id", extensionId).put("contribution_id", contributionId).put("event_id", eventId).put("payload_json", payloadJson))
            refresh(); return result.getString("result_json")
        }
    }

    private fun parseChild(v: JSONObject) = ChildExtensionSnapshot(
        v.getString("extension_id"), v.getString("version"), v.getString("display_name"), v.nullable("description"),
        ChildExtensionTarget(v.getString("parent_plugin_id"), v.getString("point"), v.getInt("api_version")),
        ChildExtensionLifecycle.valueOf(v.getString("lifecycle").uppercase()), v.getBoolean("enabled"),
        v.strings("roles").toSet(), v.optLong("use_count", 0L), v.nullable("last_error")
    )
    private fun parseBackup(v: JSONObject) = ChildExtensionBackupSnapshot(
        v.getString("extension_id"), v.getString("version"), v.getString("display_name"), v.nullable("description"),
        ChildExtensionTarget(v.getString("parent_plugin_id"), v.getString("point"), v.getInt("api_version")),
        v.strings("roles").toSet(), v.getString("package_sha256"), v.getLong("backed_up_at"), v.getBoolean("was_enabled"),
        v.getBoolean("installed"), v.nullable("installed_version")
    )

    private fun clearMirrors() {
        synchronized(capabilityBindings) { capabilityBindings.values.forEach(Binding::close); capabilityBindings.clear(); capabilityFingerprint = "" }
        snapshots.value = emptyList(); backups.value = emptyList(); ui.value = emptyList(); canonicalDescriptors = emptyList()
        points.values.forEach { it.value = emptyList() }; uiProviders.clear(); presentations = JSONArray(); runtime = JSONObject()
    }
    private fun controller(roles: Set<String>) {
        check(ChildRuntimeAuthorityRoles.RUNTIME_CONTROLLER in roles) {
            "Child runtime administration requires the kernel runtime-controller role"
        }
    }
}

private fun JSONObject.nullable(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.strings(key: String): List<String> = buildList {
    val a = optJSONArray(key) ?: return@buildList
    for (i in 0 until a.length()) add(a.getString(i))
}
