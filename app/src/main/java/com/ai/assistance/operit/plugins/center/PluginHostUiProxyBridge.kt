package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker
import com.ai.assistance.operit.core.tools.system.resident.ResidentUiProxyWire
import com.ai.assistance.operit.plugins.system.KernelSelfMaintenanceJsonServiceV1
import com.ai.assistance.operit.plugins.system.KernelSystemPluginHostV2
import com.ai.assistance.operit.plugins.system.KernelSystemUiHostV1
import com.ai.assistance.operit.plugins.system.PluginPlatformControlV1
import com.ai.assistance.operit.plugins.system.SystemHostGatewayV1
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveAvailability
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveDescriptor
import com.ai.assistance.operit.plugins.system.SystemJsonServiceV1
import com.ai.assistance.operit.plugins.system.SystemPluginChildExtensionControlV2
import com.ai.assistance.operit.plugins.system.SystemPluginDelegatedCapabilityInvokerV2
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginProviderBindingV2
import com.ai.assistance.operit.plugins.system.SystemPluginProviderDirectoryV2
import com.ai.assistance.operit.plugins.system.SystemPluginProtocolV1
import com.ai.assistance.operit.plugins.system.SystemPluginServiceEndpointV2
import com.ai.assistance.operit.plugins.system.SystemPluginServicePublisherV2
import com.ai.assistance.operit.plugins.system.SystemPluginController
import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionLifecycle
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.ChildUiContributionSnapshot
import com.ai.limbs.plugin.runtime.InProcessUiContributionProvider
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-side client for the Resident UI data/command plane.
 *
 * It owns only mirrored descriptors, local renderer objects and Android framework component work.
 * PluginManager, child runtime, capability executors and provider implementations remain in Core.
 */
internal class ResidentUiProxyClient(
    private val appContext: Context,
    private val runtime: PluginHostUiProxyRuntime
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val revision = AtomicLong(-1L)
    private val lastSnapshot = AtomicReference(JSONObject())
    private val providerDirectory = ResidentProviderDirectory(this)
    private val childControl = ResidentChildControl(this)
    private val hostPrimitiveCache = AtomicReference<List<SystemHostPrimitiveDescriptor>>(emptyList())
    private val hostPrimitiveOperations = ConcurrentHashMap<String, List<String>>()
    @Volatile private var developerMode = false
    @Volatile private var developerDiscovery = false
    private val componentExecutor = ResidentHostComponentExecutor(appContext, this)

    private lateinit var systemPluginController: SystemPluginController

    fun start() {
        if (!started.compareAndSet(false, true)) return
        systemPluginController = SystemPluginController(
            context = appContext,
            runtimeRole = PluginRuntimeRole.UI_PROXY,
            uiRegistry = runtime.systemUiRegistry,
            hostFactory = { pluginId, role -> createSystemHost(pluginId, role) }
        )
        systemPluginController.initialize()
        scope.launch {
            var rendererRestored = false
            while (isActive) {
                try {
                    refresh(force = revision.get() < 0L)
                    if (!rendererRestored) {
                        systemPluginController.restore()
                        rendererRestored = true
                    }
                    componentExecutor.pollAndExecute()
                    delay(POLL_MS)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    revision.set(-1L)
                    com.ai.assistance.operit.util.AppLogger.w(TAG, "Resident UI proxy temporarily disconnected", error)
                    delay(RETRY_MS)
                }
            }
        }
    }

    fun updateAttachment() {
        // Session identity is checked by PluginHostUiProxyRuntime before this call. A refreshed attach
        // needs no new business runtime; the poll loop simply continues against the same Core session.
    }

    suspend fun command(payload: JSONObject): JSONObject =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ResidentUiProxyWire.request("command", sessionId(), JSONObject(payload.toString()))
        }

    fun commandBlocking(payload: JSONObject): JSONObject =
        ResidentUiProxyWire.request("command", sessionId(), JSONObject(payload.toString()))

    suspend fun invokeUiCapability(
        ownerPluginId: String,
        screenId: String,
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject = command(
        JSONObject()
            .put("command", "invoke_ui_capability")
            .put("owner_plugin_id", ownerPluginId)
            .put("screen_id", screenId)
            .put("capability_id", capabilityId)
            .put("parameters", JSONObject(parameters.toString()))
    )

    fun setActiveScreen(screenId: String?) {
        runtime.pagePresentationRegistry.setActiveScreen(screenId)
        scope.launch {
            runCatching {
                command(JSONObject().put("command", "set_active_screen").put("screen_id", screenId ?: ""))
            }.onFailure { com.ai.assistance.operit.util.AppLogger.w(TAG, "Core active-screen update failed", it) }
        }
    }

    fun setPresentationMode(ownerPluginId: String, screenId: String, mode: PluginPagePresentationMode) {
        scope.launch {
            runCatching {
                command(
                    JSONObject().put("command", "set_presentation_mode")
                        .put("owner_plugin_id", ownerPluginId)
                        .put("screen_id", screenId)
                        .put("mode", mode.name.lowercase())
                )
            }.onFailure { com.ai.assistance.operit.util.AppLogger.w(TAG, "Core presentation update failed", it) }
        }
    }

    fun providers(): SystemPluginProviderDirectoryV2 = providerDirectory
    fun children(): SystemPluginChildExtensionControlV2 = childControl
    fun hostPrimitives(): List<SystemHostPrimitiveDescriptor> = hostPrimitiveCache.get()
    fun primitiveOperations(id: String): List<String> = hostPrimitiveOperations[id.trim().lowercase()].orEmpty()
    fun developerModeEnabled(): Boolean = developerMode
    fun developerDiscoveryEnabled(): Boolean = developerDiscovery

    private fun sessionId(): String = checkNotNull(runtime.attachment.coreSessionId) {
        "UI proxy attachment has no Resident Core session"
    }

    private fun refresh(force: Boolean) {
        val result = if (force) {
            ResidentUiProxyWire.request("snapshot", sessionId())
        } else {
            ResidentUiProxyWire.request("events", sessionId(), JSONObject().put("since_revision", revision.get()))
        }
        val nextRevision = result.optLong("revision", revision.get())
        revision.set(nextRevision)
        if (!result.optBoolean("changed", false) || result.isNull("snapshot")) return
        val snapshot = result.getJSONObject("snapshot")
        lastSnapshot.set(JSONObject(snapshot.toString()))
        applySnapshot(snapshot)
    }

    private fun applySnapshot(snapshot: JSONObject) {
        val tiles = snapshot.getJSONArray("tiles").objects().map { item ->
            PluginHomeTileSpec(
                ownerPluginId = item.getString("owner_plugin_id"), id = item.getString("id"),
                title = item.getString("title"), description = item.optString("description", ""),
                screenId = item.getString("screen_id")
            )
        }
        val screens = snapshot.getJSONArray("screens").objects().map { item ->
            PluginScreenSpec(
                ownerPluginId = item.getString("owner_plugin_id"), id = item.getString("id"), title = item.getString("title"),
                description = item.stringOrNull("description"), schemaId = item.getString("schema_id"),
                documentJson = item.getString("document_json")
            )
        }
        val theme = snapshot.optJSONObject("theme")?.let { item ->
            PluginThemeSpec(
                ownerPluginId = item.getString("owner_plugin_id"), id = item.getString("id"),
                mode = PluginThemeMode.valueOf(item.getString("mode").uppercase()), pureBlack = item.optBoolean("pure_black", false),
                colors = item.optJSONObject("colors")?.stringMap().orEmpty(),
                backgroundGradient = item.optJSONArray("background_gradient")?.strings().orEmpty()
            )
        }
        runtime.uiRegistry.replaceFromResidentProxy(tiles, screens, theme)

        val presentations = snapshot.getJSONArray("presentations").objects().map { item ->
            PluginPagePresentationRequest(
                ownerPluginId = item.getString("owner_plugin_id"), screenId = item.getString("screen_id"),
                mode = PluginPagePresentationMode.parse(item.getString("mode"))
            )
        }
        runtime.pagePresentationRegistry.replaceFromResidentProxy(presentations)

        val navSurfaces = snapshot.getJSONArray("navigation_surfaces").objects().map { item ->
            DynamicNavigationSurfaceSpec(
                id = item.getString("id"), title = item.getString("title"), iconKey = item.getString("icon_key"),
                order = item.getInt("order"), createdAt = item.getLong("created_at")
            )
        }
        val navBindings = snapshot.getJSONArray("navigation_bindings").objects().map { item ->
            DynamicNavigationBinding(
                surfaceId = item.getString("surface_id"), ownerPluginId = item.getString("owner_plugin_id"),
                tileId = item.getString("tile_id"), screenId = item.getString("screen_id")
            )
        }
        runtime.dynamicNavigationRegistry.replaceFromResidentProxy(navSurfaces, navBindings)

        providerDirectory.update(snapshot.getJSONArray("providers"))
        childControl.update(
            snapshot.getJSONArray("children"), snapshot.getJSONArray("child_backups"),
            snapshot.getJSONArray("child_ui_contributions")
        )
        developerMode = snapshot.optBoolean("developer_mode", false)
        developerDiscovery = snapshot.optBoolean("developer_discovery_enabled", false)
        val primitives = mutableListOf<SystemHostPrimitiveDescriptor>()
        hostPrimitiveOperations.clear()
        snapshot.getJSONArray("host_primitives").objects().forEach { item ->
            val descriptor = SystemHostPrimitiveDescriptor(
                number = item.getInt("number"), id = item.getString("id"), title = item.getString("title"),
                description = item.getString("description"), boundary = item.getString("boundary"),
                maturity = item.getString("maturity"), exposure = item.getString("exposure"),
                requestableScope = item.getBoolean("requestable_scope"),
                policyAllowed = if (item.isNull("policy_allowed")) null else item.getBoolean("policy_allowed"),
                callable = item.getBoolean("callable")
            )
            primitives += descriptor
            hostPrimitiveOperations[descriptor.id.lowercase()] = item.optJSONArray("operations")?.strings().orEmpty()
        }
        hostPrimitiveCache.set(primitives)
    }

    private fun createSystemHost(pluginId: String, role: String): SystemPluginHostV2 {
        check(role.trim().lowercase() == SystemPluginProtocolV1.ROLE_PLUGIN_CENTER)
        val ui = KernelSystemUiHostV1(pluginId, role, runtime.systemUiRegistry)
        return KernelSystemPluginHostV2(
            SystemPluginProtocolV1.HOST_ABI,
            ResidentSystemHostGateway(this),
            ResidentPluginPlatformControl(this),
            ResidentSystemJsonService(this, "plugin_admin"),
            ResidentSystemJsonService(this, "admin_security"),
            KernelSelfMaintenanceJsonServiceV1(systemPluginController),
            ResidentSystemJsonService(this, "navigation"),
            ui,
            ResidentPresentationLocalServicePublisher,
            ResidentDelegatedCapabilityInvoker(this),
            providerDirectory,
            childControl
        )
    }

    internal fun componentRequest(operation: String, payload: JSONObject): JSONObject =
        ResidentUiProxyWire.request(operation, sessionId(), payload)

    internal fun completeComponentAsync(requestId: String, result: JSONObject) {
        scope.launch {
            runCatching {
                componentRequest(
                    "component_result",
                    JSONObject().put("request_id", requestId).put("result", JSONObject(result.toString()))
                )
            }.onFailure {
                com.ai.assistance.operit.util.AppLogger.w(TAG, "Host component result delivery failed: $requestId", it)
            }
        }
    }

    companion object {
        private const val TAG = "ResidentUiProxyClient"
        private const val POLL_MS = 350L
        private const val RETRY_MS = 750L
    }
}

private class ResidentSystemJsonService(
    private val client: ResidentUiProxyClient,
    private val service: String
) : SystemJsonServiceV1 {
    override suspend fun call(operation: String, parameters: JSONObject): JSONObject = client.command(
        JSONObject().put("command", "system_json").put("service", service).put("operation", operation)
            .put("parameters", JSONObject(parameters.toString()))
    )
}

private class ResidentDelegatedCapabilityInvoker(
    private val client: ResidentUiProxyClient
) : SystemPluginDelegatedCapabilityInvokerV2 {
    override suspend fun invokeAsActivePlugin(pluginId: String, capabilityId: String, parameters: JSONObject): JSONObject =
        client.command(JSONObject().put("command", "delegated_capability").put("plugin_id", pluginId)
            .put("capability_id", capabilityId).put("parameters", JSONObject(parameters.toString())))
}

private class ResidentPluginPlatformControl(
    private val client: ResidentUiProxyClient
) : PluginPlatformControlV1 {
    override fun developerModeEnabled(): Boolean = client.developerModeEnabled()
    override fun developerDiscoveryEnabled(): Boolean = client.developerDiscoveryEnabled()
    override fun hostPrimitiveSnapshots(): List<SystemHostPrimitiveDescriptor> = client.hostPrimitives()
    override suspend fun setDeveloperMode(enabled: Boolean) {
        client.command(JSONObject().put("command", "plugin_platform").put("operation", "set_developer_mode").put("enabled", enabled))
    }
    override suspend fun setDeveloperDiscoveryEnabled(enabled: Boolean) {
        client.command(JSONObject().put("command", "plugin_platform").put("operation", "set_developer_discovery").put("enabled", enabled))
    }
    override suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean) {
        client.command(JSONObject().put("command", "plugin_platform").put("operation", "set_host_primitive_allowed")
            .put("primitive_id", primitiveId).put("allowed", allowed))
    }
}

private class ResidentSystemHostGateway(
    private val client: ResidentUiProxyClient
) : SystemHostGatewayV1 {
    override fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor> = client.hostPrimitives()
    override fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor? = client.hostPrimitives().firstOrNull { it.id == id.trim().lowercase() }
    override fun listHostPrimitiveOperations(id: String): List<String> = client.primitiveOperations(id)
    override fun availabilityHostPrimitive(id: String, operation: String?): SystemHostPrimitiveAvailability {
        val descriptor = describeHostPrimitive(id)
            ?: return SystemHostPrimitiveAvailability(id, operation, false, false, false, "HOST_PRIMITIVE_UNKNOWN", "Unknown Host Primitive")
        val operationKnown = operation == null || operation.trim().lowercase() in client.primitiveOperations(descriptor.id)
        return SystemHostPrimitiveAvailability(
            descriptor.id, operation, true, descriptor.callable, descriptor.callable && operationKnown,
            if (operationKnown) null else "HOST_OPERATION_UNKNOWN", if (operationKnown) null else "Unknown Host Primitive operation"
        )
    }
    override suspend fun invokeHostPrimitive(id: String, parameters: JSONObject): JSONObject =
        client.command(JSONObject().put("command", "host_primitive").put("id", id).put("parameters", JSONObject(parameters.toString())))
    override suspend fun invokeHostPrimitive(id: String, operation: String, parameters: JSONObject): JSONObject =
        client.command(JSONObject().put("command", "host_primitive").put("id", id).put("host_operation", operation)
            .put("parameters", JSONObject(parameters.toString())))
}

/**
 * Plugin Center may publish presentation helper services while its UI package mounts in Host.
 * They are intentionally not exported to Core and therefore cannot become a second business owner.
 */
private object ResidentPresentationLocalServicePublisher : SystemPluginServicePublisherV2 {
    override fun publish(
        id: String, apiVersion: Int, endpoint: SystemPluginServiceEndpointV2, metadata: Map<String, String>
    ): AutoCloseable = AutoCloseable { }
}

private class ResidentProviderDirectory(
    private val client: ResidentUiProxyClient
) : SystemPluginProviderDirectoryV2 {
    private val state = MutableStateFlow<Map<String, SystemPluginProviderBindingV2>>(emptyMap())
    private val proxies = ConcurrentHashMap<String, ResidentUiStateProviderProxy>()

    fun update(array: JSONArray) {
        val next = linkedMapOf<String, SystemPluginProviderBindingV2>()
        array.objects().forEach { item ->
            if (item.optString("kind") != "ui_state") return@forEach
            val id = item.getString("id")
            val owner = item.getString("owner_plugin_id")
            val proxy = proxies.compute(id) { _, old ->
                if (old != null && old.ownerPluginId == owner) old else ResidentUiStateProviderProxy(client, owner, id)
            }!!
            proxy.update(item.stringOrNull("state_json"))
            next[id] = SystemPluginProviderBindingV2(owner, id, item.optJSONObject("metadata")?.stringMap().orEmpty(), proxy)
        }
        proxies.keys.retainAll(next.keys)
        state.value = next
    }

    override fun resolve(id: String): SystemPluginProviderBindingV2? = state.value[id.trim()]
    override fun snapshot(): List<SystemPluginProviderBindingV2> = state.value.values.sortedBy { it.id }
    override fun observe(id: String): Flow<SystemPluginProviderBindingV2?> = state.map { it[id.trim()] }
}

private class ResidentUiStateProviderProxy(
    private val client: ResidentUiProxyClient,
    val ownerPluginId: String,
    private val providerId: String
) : InProcessUiStateProvider {
    private val mutableState = MutableStateFlow<String?>(null)
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()
    fun update(value: String?) { mutableState.value = value }
    override suspend fun perform(eventId: String, payloadJson: String): String =
        client.command(JSONObject().put("command", "ui_provider_event").put("owner_plugin_id", ownerPluginId)
            .put("provider_id", providerId).put("event_id", eventId).put("payload_json", payloadJson))
            .getString("result_json")
}

private class ResidentChildContributionProxy(
    private val client: ResidentUiProxyClient,
    private val extensionId: String,
    private val contributionId: String
) : InProcessUiContributionProvider {
    private val mutableDocument = MutableStateFlow<String?>(null)
    override val documentJson: StateFlow<String?> = mutableDocument.asStateFlow()
    fun update(value: String?) { mutableDocument.value = value }
    override suspend fun perform(eventId: String, payloadJson: String): String =
        client.command(JSONObject().put("command", "child_ui_event").put("extension_id", extensionId)
            .put("contribution_id", contributionId).put("event_id", eventId).put("payload_json", payloadJson))
            .getString("result_json")
}

private class ResidentChildControl(
    private val client: ResidentUiProxyClient
) : SystemPluginChildExtensionControlV2 {
    private val mutableSnapshots = MutableStateFlow<List<ChildExtensionSnapshot>>(emptyList())
    private val mutableBackups = MutableStateFlow<List<ChildExtensionBackupSnapshot>>(emptyList())
    private val mutableUi = MutableStateFlow<List<ChildUiContributionSnapshot>>(emptyList())
    private val pointFlows = ConcurrentHashMap<String, MutableStateFlow<List<ChildExtensionSnapshot>>>()
    private val contributionProxies = ConcurrentHashMap<String, ResidentChildContributionProxy>()

    fun update(snapshots: JSONArray, backups: JSONArray, ui: JSONArray) {
        val nextSnapshots = snapshots.objects().map(::childSnapshot)
        mutableSnapshots.value = nextSnapshots
        pointFlows.forEach { (point, flow) -> flow.value = nextSnapshots.filter { it.target.point == point } }
        mutableBackups.value = backups.objects().map(::childBackup)
        val activeKeys = linkedSetOf<String>()
        mutableUi.value = ui.objects().map { item ->
            val extensionId = item.getString("extension_id")
            val contributionId = item.getString("contribution_id")
            val key = "$extensionId|$contributionId"
            activeKeys += key
            val provider = contributionProxies.computeIfAbsent(key) { ResidentChildContributionProxy(client, extensionId, contributionId) }
            provider.update(item.stringOrNull("document_json"))
            ChildUiContributionSnapshot(
                extensionId = extensionId,
                target = ChildExtensionTarget(item.getString("parent_plugin_id"), item.getString("point"), item.getInt("api_version")),
                screenId = item.getString("screen_id"), componentId = item.getString("component_id"),
                slotId = item.getString("slot_id"), contributionId = contributionId, provider = provider
            )
        }
        contributionProxies.keys.retainAll(activeKeys)
    }

    override suspend fun uninstall(extensionId: String): Boolean = childCommand("uninstall", extensionId).getBoolean("removed")
    override suspend fun setEnabled(extensionId: String, enabled: Boolean): ChildExtensionSnapshot =
        childSnapshot(childCommand("set_enabled", extensionId, JSONObject().put("enabled", enabled)))
    override suspend fun backup(extensionId: String): ChildExtensionBackupSnapshot = childBackup(childCommand("backup", extensionId))
    override suspend fun restoreBackup(extensionId: String): ChildExtensionSnapshot = childSnapshot(childCommand("restore_backup", extensionId))
    override suspend fun deleteBackup(extensionId: String): Boolean = childCommand("delete_backup", extensionId).getBoolean("deleted")
    override suspend fun setAutoBackupPolicy(enabled: Boolean, highFrequencyUseCount: Long) {
        childCommand("set_auto_backup_policy", "", JSONObject().put("enabled", enabled).put("high_frequency_use_count", highFrequencyUseCount))
    }
    override suspend fun exportBackups(extensionIds: Collection<String>, treeUri: String): List<String> {
        val result = childCommand("export_backups", "", JSONObject().put("extension_ids", JSONArray(extensionIds)).put("tree_uri", treeUri))
        return result.getJSONArray("exported").strings()
    }
    override fun snapshots(): StateFlow<List<ChildExtensionSnapshot>> = mutableSnapshots.asStateFlow()
    override fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>> =
        pointFlows.computeIfAbsent(point) { MutableStateFlow(mutableSnapshots.value.filter { it.target.point == point }) }.asStateFlow()
    override fun backupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>> = mutableBackups.asStateFlow()
    override fun uiContributions(): StateFlow<List<ChildUiContributionSnapshot>> = mutableUi.asStateFlow()

    private suspend fun childCommand(operation: String, id: String, extras: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject(extras.toString()).put("command", "child_control").put("operation", operation)
        if (id.isNotBlank()) request.put("extension_id", id)
        return client.command(request)
    }
}

/** Executes only Android framework work in Host. Raw framework objects never enter the wire. */
private class ResidentHostComponentExecutor(
    private val appContext: Context,
    private val client: ResidentUiProxyClient
) {
    private val windowLeases = ConcurrentHashMap<String, WeakReference<android.view.Window>>()

    fun pollAndExecute() {
        val result = client.componentRequest("component_poll", JSONObject().put("max_items", 8))
        result.optJSONArray("requests")?.objects().orEmpty().forEach { request ->
            val id = request.getString("request_id")
            val outcome: JSONObject? = try {
                execute(id, request.getString("kind"), request.optJSONObject("payload") ?: JSONObject())
            } catch (error: Throwable) {
                JSONObject().put("ok", false).put("error", error.toString().take(1024))
            }
            if (outcome != null) {
                runCatching {
                    client.componentRequest(
                        "component_result",
                        JSONObject().put("request_id", id).put("result", outcome)
                    )
                }
            }
        }
    }

    /** null means an asynchronous ActivityResult was launched and will complete later. */
    private fun execute(requestId: String, kind: String, payload: JSONObject): JSONObject? {
        return when (kind) {
        ResidentComponentProxyBroker.KIND_ACTIVITY_PRESENCE -> {
            val activity = ActivityLifecycleManager.getCurrentActivity()
            JSONObject().put("ok", true).put("present", activity != null)
                .put("activity_class", activity?.javaClass?.name ?: JSONObject.NULL)
        }
        ResidentComponentProxyBroker.KIND_WINDOW_LEASE -> {
            val window = ActivityLifecycleManager.getCurrentActivity()?.window
                ?: return JSONObject().put("ok", false).put("error", "NO_FOREGROUND_ACTIVITY")
            val leaseId = UUID.randomUUID().toString()
            windowLeases[leaseId] = WeakReference(window)
            JSONObject().put("ok", true).put("window_lease_id", leaseId)
        }
        ResidentComponentProxyBroker.KIND_WINDOW_FLAGS -> {
            val leaseId = payload.optString("window_lease_id")
            val window = windowLeases[leaseId]?.get() ?: ActivityLifecycleManager.getCurrentActivity()?.window
                ?: return JSONObject().put("ok", false).put("error", "WINDOW_LEASE_UNAVAILABLE")
            val addFlags = payload.optInt("add_flags", 0)
            val clearFlags = payload.optInt("clear_flags", 0)
            runOnUiThread {
                if (addFlags != 0) window.addFlags(addFlags)
                if (clearFlags != 0) window.clearFlags(clearFlags)
            }
            JSONObject().put("ok", true).put("window_lease_id", leaseId.ifBlank { JSONObject.NULL })
        }
        ResidentComponentProxyBroker.KIND_START_ACTIVITY -> {
            val activity = ActivityLifecycleManager.getCurrentActivity()
                ?: return JSONObject().put("ok", false).put("error", "NO_FOREGROUND_ACTIVITY")
            val intent = buildNeutralIntent(payload)
            runOnUiThread { activity.startActivity(intent) }
            JSONObject().put("ok", true)
        }
        ResidentComponentProxyBroker.KIND_ACTIVITY_RESULT -> launchActivityResult(requestId, payload)
        else -> JSONObject().put("ok", false).put("error", "UNKNOWN_COMPONENT_KIND")
        }
    }

    private fun launchActivityResult(requestId: String, payload: JSONObject): JSONObject? {
        val activity = ActivityLifecycleManager.getCurrentActivity() as? ComponentActivity
            ?: return JSONObject().put("ok", false).put("error", "NO_COMPONENT_ACTIVITY")
        val intent = buildNeutralIntent(payload)
        runOnUiThread {
            val key = "resident-ui-proxy:$requestId"
            val launcherRef = AtomicReference<ActivityResultLauncher<Intent>?>(null)
            val launcher = activity.activityResultRegistry.register(
                key,
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val response = serializeActivityResult(result.resultCode, result.data)
                launcherRef.getAndSet(null)?.unregister()
                client.completeComponentAsync(requestId, response)
            }
            launcherRef.set(launcher)
            try {
                launcher.launch(intent)
            } catch (error: Throwable) {
                launcherRef.getAndSet(null)?.unregister()
                throw error
            }
        }
        return null
    }

    private fun buildNeutralIntent(payload: JSONObject): Intent {
        val intent = Intent()
        payload.stringOrNull("action")?.trim()?.takeIf { it.isNotEmpty() }?.let { intent.action = it }
        val dataUri = payload.stringOrNull("data_uri")?.trim()?.takeIf { it.isNotEmpty() }
        val mimeType = payload.stringOrNull("mime_type")?.trim()?.takeIf { it.isNotEmpty() }
        when {
            dataUri != null && mimeType != null -> intent.setDataAndType(Uri.parse(dataUri), mimeType)
            dataUri != null -> intent.data = Uri.parse(dataUri)
            mimeType != null -> intent.type = mimeType
        }
        payload.stringOrNull("package_name")?.trim()?.takeIf { it.isNotEmpty() }?.let(intent::setPackage)
        val componentPackage = payload.stringOrNull("component_package")?.trim()?.takeIf { it.isNotEmpty() }
        val componentClass = payload.stringOrNull("component_class")?.trim()?.takeIf { it.isNotEmpty() }
        if (componentPackage != null || componentClass != null) {
            require(componentPackage != null && componentClass != null) {
                "component_package and component_class must be supplied together"
            }
            intent.component = if (componentClass.startsWith('.')) {
                ComponentName.createRelative(componentPackage, componentClass)
            } else {
                ComponentName(componentPackage, componentClass)
            }
        }
        payload.optJSONArray("categories")?.strings().orEmpty().forEach(intent::addCategory)
        if (payload.has("flags")) intent.flags = payload.optInt("flags", 0)
        payload.optJSONObject("extras")?.let { putNeutralExtras(intent, it) }
        require(intent.action != null || intent.data != null || intent.component != null) {
            "Neutral Intent requires action, data_uri or explicit component"
        }
        return intent
    }

    private fun putNeutralExtras(intent: Intent, extras: JSONObject) {
        val keys = extras.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = extras.get(key)) {
                JSONObject.NULL -> Unit
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is JSONArray -> {
                    val values = value.strings()
                    require(values.size == value.length()) { "Only string arrays are allowed in Intent extras: $key" }
                    intent.putStringArrayListExtra(key, ArrayList(values))
                }
                else -> error("Unsupported neutral Intent extra type for $key: ${value.javaClass.name}")
            }
        }
    }

    private fun serializeActivityResult(resultCode: Int, data: Intent?): JSONObject {
        val result = JSONObject().put("ok", true).put("result_code", resultCode)
        if (data == null) return result.put("data", JSONObject.NULL)
        val neutral = JSONObject()
            .put("action", data.action ?: JSONObject.NULL)
            .put("data_uri", data.dataString ?: JSONObject.NULL)
            .put("mime_type", data.type ?: JSONObject.NULL)
            .put("flags", data.flags)
            .put("categories", JSONArray(data.categories?.toList()?.sorted().orEmpty()))
        val clipUris = JSONArray()
        data.clipData?.let { clip ->
            for (index in 0 until minOf(clip.itemCount, 32)) {
                clip.getItemAt(index).uri?.toString()?.let(clipUris::put)
            }
        }
        neutral.put("clip_uris", clipUris)
        neutral.put("extras", safeResultExtras(data.extras))
        return result.put("data", neutral)
    }

    private fun safeResultExtras(bundle: android.os.Bundle?): JSONObject {
        val result = JSONObject()
        if (bundle == null) return result
        bundle.keySet().forEach { key ->
            when (val value = bundle.get(key)) {
                null -> result.put(key, JSONObject.NULL)
                is String, is Boolean, is Int, is Long, is Double, is Float -> result.put(key, value)
                is Array<*> -> if (value.all { it is String }) result.put(key, JSONArray(value.toList()))
                is List<*> -> if (value.all { it is String }) result.put(key, JSONArray(value))
                else -> Unit // Parcelable, Binder and arbitrary framework objects are intentionally dropped.
            }
        }
        return result
    }

    private fun runOnUiThread(block: () -> Unit) {
        val activity = ActivityLifecycleManager.getCurrentActivity() ?: error("No foreground Activity")
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        activity.runOnUiThread {
            try { block() } catch (error: Throwable) { failure.set(error) } finally { latch.countDown() }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "Host UI thread did not execute component request" }
        failure.get()?.let { throw it }
    }
}

private fun childSnapshot(item: JSONObject): ChildExtensionSnapshot = ChildExtensionSnapshot(
    extensionId = item.getString("extension_id"), version = item.getString("version"), displayName = item.getString("display_name"),
    description = item.stringOrNull("description"),
    target = ChildExtensionTarget(item.getString("parent_plugin_id"), item.getString("point"), item.getInt("api_version")),
    lifecycle = ChildExtensionLifecycle.valueOf(item.getString("lifecycle").uppercase()), enabled = item.getBoolean("enabled"),
    roles = item.optJSONArray("roles")?.strings()?.toSet().orEmpty(), useCount = item.optLong("use_count", 0L),
    lastError = item.stringOrNull("last_error")
)

private fun childBackup(item: JSONObject): ChildExtensionBackupSnapshot = ChildExtensionBackupSnapshot(
    extensionId = item.getString("extension_id"), version = item.getString("version"), displayName = item.getString("display_name"),
    description = item.stringOrNull("description"),
    target = ChildExtensionTarget(item.getString("parent_plugin_id"), item.getString("point"), item.getInt("api_version")),
    roles = item.optJSONArray("roles")?.strings()?.toSet().orEmpty(), packageSha256 = item.getString("package_sha256"),
    backedUpAtEpochMs = item.getLong("backed_up_at"), wasEnabled = item.getBoolean("was_enabled"),
    installed = item.getBoolean("installed"), installedVersion = item.stringOrNull("installed_version")
)

private fun JSONArray.objects(): List<JSONObject> = buildList { for (index in 0 until length()) add(getJSONObject(index)) }
private fun JSONArray.strings(): List<String> = buildList { for (index in 0 until length()) add(getString(index)) }
private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.stringMap(): Map<String, String> = buildMap {
    val iterator = keys()
    while (iterator.hasNext()) { val key = iterator.next(); put(key, getString(key)) }
}
