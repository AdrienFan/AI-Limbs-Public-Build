package com.ai.limbs.plugin.runtime

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface InProcessPluginEntry {
    suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle
}

fun interface InProcessPluginHandle {
    suspend fun stop()
}

fun interface InProcessCapabilityExecutor {
    suspend fun invoke(parametersJson: String): String
}

data class InProcessProviderBinding(
    val ownerPluginId: String,
    val id: String,
    val metadata: Map<String, String>,
    val payload: Any?
)

interface InProcessProviderDirectory {
    fun resolve(id: String): InProcessProviderBinding?
    fun snapshot(): List<InProcessProviderBinding>
    fun observe(id: String): StateFlow<InProcessProviderBinding?>
}

/**
 * Generic opaque state/event channel for Plugin Center-owned UI components.
 *
 * Stable Kernel deliberately does not define fields, buttons, selectors, queues or any other widget
 * model here. [stateJson] is interpreted only by the Plugin Center schema named by the screen
 * document. [perform] carries component events back to the owning plugin as opaque JSON.
 *
 * This is the permanent escape hatch that lets future complex controls evolve without adding a new
 * Host enum/data class for every visual concept.
 */
interface InProcessUiStateProvider {
    val stateJson: StateFlow<String?>
    suspend fun perform(eventId: String, payloadJson: String = "{}"): String
}

/**
 * Non-destructive UI contribution owned by one plugin or child extension instance.
 *
 * [documentJson] contains only Plugin Center-defined component instances. This contract cannot
 * register component types, replace the Component Registry, or mutate a shared component definition.
 * [perform] is the contribution-local event channel, so contributed buttons return to their owner
 * instead of borrowing the parent screen's capability identity.
 */
interface InProcessUiContributionProvider {
    val documentJson: StateFlow<String?>
    suspend fun perform(eventId: String, payloadJson: String = "{}"): String
}

data class InProcessNotificationAction(
    val id: String,
    val label: String,
    val priority: Int = 0,
    val enabled: Boolean = true
)

data class InProcessNotificationState(
    val title: String,
    val summary: String = "",
    val statusLines: List<String> = emptyList(),
    val actions: List<InProcessNotificationAction> = emptyList()
)

fun interface InProcessNotificationActionHandler {
    suspend fun perform(actionId: String)
}

interface InProcessNotificationHost {
    fun publish(
        state: StateFlow<InProcessNotificationState?>,
        actionHandler: InProcessNotificationActionHandler
    ): AutoCloseable
}

data class InProcessHomeTile(
    val id: String,
    val title: String,
    val description: String,
    val screenId: String
)

/**
 * Opaque plugin UI surface descriptor.
 *
 * The Host deliberately does not know which controls exist inside [documentJson].  It only stores
 * and routes this document to the Plugin Center UI runtime identified by [schemaId].  This boundary
 * is what lets Plugin Center add, replace or compose complex controls without requiring another
 * Stable Kernel release.
 *
 * Plugin code must treat the document schema as a Plugin Center contract.  Host code must never add
 * a `when(type)` renderer for document components again; doing so would recreate the coupling this
 * contract is designed to remove.
 */
data class InProcessScreen(
    val id: String,
    val title: String,
    val description: String? = null,
    val schemaId: String,
    val documentJson: String
)

class InProcessServiceBinding(
    val ownerPluginId: String,
    val id: String,
    val apiVersion: Int,
    val metadata: Map<String, String>,
    private val invokeOperation: suspend (operation: String, parametersJson: String) -> String
) {
    suspend fun invoke(operation: String, parametersJson: String = "{}"): String =
        invokeOperation(operation, parametersJson)
}

interface InProcessServiceDirectory {
    fun resolve(id: String, minApi: Int? = null): InProcessServiceBinding?
}

interface InProcessPluginHost {
    val applicationContext: Context
    val pluginId: String
    val version: String
    val scope: CoroutineScope
    val dataDir: File
    val cacheDir: File
    val providers: InProcessProviderDirectory
    val services: InProcessServiceDirectory

    fun registerProvider(
        id: String,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    )

    fun registerCapability(
        id: String,
        displayName: String,
        description: String = "",
        executor: InProcessCapabilityExecutor
    )

    fun registerHomeTile(tile: InProcessHomeTile)
    fun registerScreen(screen: InProcessScreen)

    suspend fun invokeHostCapability(id: String, parametersJson: String = "{}"): String
}

enum class ChildExtensionLifecycle {
    INSTALLED,
    ACTIVE,
    BLOCKED,
    DISABLED,
    FAILED
}

data class ChildExtensionTarget(
    val parentPluginId: String,
    val point: String,
    val apiVersion: Int
)

data class ChildExtensionSnapshot(
    val extensionId: String,
    val version: String,
    val displayName: String,
    val description: String?,
    val target: ChildExtensionTarget,
    val lifecycle: ChildExtensionLifecycle,
    val enabled: Boolean,
    val roles: Set<String> = emptySet(),
    val useCount: Long = 0L,
    val lastError: String? = null
)

data class ChildExtensionBackupSnapshot(
    val extensionId: String,
    val version: String,
    val displayName: String,
    val description: String?,
    val target: ChildExtensionTarget,
    val roles: Set<String>,
    val packageSha256: String,
    val backedUpAtEpochMs: Long,
    val wasEnabled: Boolean,
    val installed: Boolean,
    val installedVersion: String? = null
)

data class ChildExtensionBinding(
    val extensionId: String,
    val version: String,
    val target: ChildExtensionTarget,
    val displayName: String,
    val metadata: Map<String, String>,
    val payload: Any
)

/**
 * Host-attested child contribution routed to one named slot of one parent-owned screen component.
 *
 * Identity fields are supplied by Extension Hub from the mounted .ailx record; child code cannot
 * choose another parentPluginId or extension point. Plugin Center still decides whether the target
 * slot exists and whether that parent screen opened the slot to this extension point.
 */
data class ChildUiContributionSnapshot(
    val extensionId: String,
    val target: ChildExtensionTarget,
    val screenId: String,
    val componentId: String,
    val slotId: String,
    val contributionId: String,
    val provider: InProcessUiContributionProvider
)

fun interface ChildExtensionBinder {
    fun bind(binding: ChildExtensionBinding): AutoCloseable
}

interface ExtensionHubService {
    fun publishPoint(
        ownerPluginId: String,
        point: String,
        apiVersion: Int,
        title: String,
        description: String = "",
        allowedHostCapabilities: Set<String> = emptySet(),
        binder: ChildExtensionBinder
    ): AutoCloseable

    suspend fun install(
        packageFile: File,
        expectedParentPluginId: String? = null,
        expectedPoint: String? = null
    ): ChildExtensionSnapshot
    suspend fun uninstall(extensionId: String): Boolean
    suspend fun setEnabled(extensionId: String, enabled: Boolean): ChildExtensionSnapshot
    suspend fun backup(extensionId: String): ChildExtensionBackupSnapshot
    suspend fun restoreBackup(extensionId: String): ChildExtensionSnapshot
    suspend fun deleteBackup(extensionId: String): Boolean
    suspend fun setAutoBackupPolicy(enabled: Boolean, highFrequencyUseCount: Long = 10L)
    fun recordUse(extensionId: String)
    fun snapshots(): StateFlow<List<ChildExtensionSnapshot>>
    fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>>
    fun backupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>>

    /**
     * Live child UI contributions. Plugin Center filters this attested stream against the current
     * parent screen's declared child_slots before rendering anything.
     */
    fun uiContributions(): StateFlow<List<ChildUiContributionSnapshot>>
}

interface ChildExtensionEntry {
    suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle
}

fun interface ChildExtensionHandle {
    suspend fun stop()
}

interface ChildExtensionHost {
    val applicationContext: Context
    val extensionId: String
    val version: String
    val target: ChildExtensionTarget
    val scope: CoroutineScope
    val dataDir: File
    val cacheDir: File

    fun publish(
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    )

    /**
     * Contributes Plugin Center-defined UI into a slot explicitly opened by this extension's parent.
     *
     * The caller names only the parent's screen/component/slot and its own contribution id. Extension
     * Hub binds the real extension identity and target from the verified manifest, and Plugin Center
     * rejects unknown/closed slots. Closing the returned handle removes only this instance overlay.
     */
    fun publishUiContribution(
        screenId: String,
        componentId: String,
        slotId: String,
        contributionId: String,
        provider: InProcessUiContributionProvider
    ): AutoCloseable

    suspend fun invokeHostCapability(id: String, parametersJson: String = "{}"): String
}

object InProcessSystemIds {
    const val EXTENSION_HUB_PROVIDER = "system.extension.hub"
    const val EXTENSION_HUB_PLUGIN_ID = "plugin.system.extension_hub"
    const val PLUGIN_CENTER_PLUGIN_ID = "ai_limbs.system.plugin_center"
    const val PLUGIN_CENTER_DELEGATED_GATEWAY_SERVICE = "system.plugin_center.delegated_gateway"
    const val BRIDGE_PLUGIN_ID = "plugin.system.bridge"
    const val BRIDGE_PROVIDER_POINT = "ai_limbs.bridge.provider"
    const val NOTIFICATION_HOST_PROVIDER = "system.notification.host"
}
