package com.ai.limbs.plugin.runtime

import android.content.Context
import android.view.View
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

enum class InProcessCapabilityEffect {
    READ_ONLY,
    STATE_CHANGE,
    PERSISTENT_WRITE,
    EXTERNAL_COMMUNICATION,
    PROCESS_EXECUTION,
    UI_INTERACTION,
    EXTERNAL_CAPABILITY
}

enum class InProcessCapabilityDomain {
    CORE_PROTOCOL,
    MANAGED_DOCUMENT,
    LANER_CHAT,
    SYSTEM_ENVIRONMENT,
    ANDROID_UI,
    STORAGE,
    HOST,
    PLUGIN
}

enum class InProcessCapabilityReceipt {
    WORK_MANUAL
}

data class InProcessCapabilityParameterSpec(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
    val default: String? = null
)

data class InProcessCapabilitySpec(
    val id: String,
    val displayName: String,
    val description: String = "",
    val invokeAliases: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val parameters: List<InProcessCapabilityParameterSpec> = emptyList(),
    val suggestedParamsJson: String? = null,
    val inputSchema: String? = null,
    val effect: InProcessCapabilityEffect = InProcessCapabilityEffect.EXTERNAL_CAPABILITY,
    val domain: InProcessCapabilityDomain = InProcessCapabilityDomain.PLUGIN,
    val workContextRequiredReceipts: Set<InProcessCapabilityReceipt> = emptySet(),
    val executor: InProcessCapabilityExecutor
)

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
 * Plugin-owned full page surface. Basic widgets and layout remain inside the plugin. [sharedUi]
 * exposes only Plugin Center components that were explicitly declared reusable.
 */
interface InProcessPageProvider {
    fun createView(context: Context, sharedUi: InProcessSharedUiHost): View
}

/** Optional host-owned components that a plugin page may embed without giving up page ownership. */
interface InProcessSharedUiHost {
    fun supports(componentId: String): Boolean
    fun createComponent(componentId: String, parametersJson: String = "{}"): View
}

object InProcessSharedUiComponentIds {
    const val CHILD_EXTENSION_INSTALLER = "plugin_center.shared.child_extension_installer"
    const val CHILD_EXTENSION_SELECTOR = "plugin_center.shared.child_extension_selector"
    const val CHILD_EXTENSION_LIST = "plugin_center.shared.child_extension_list"
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

/** Versioned host-owned native executable substrate. */
class InProcessNativeRuntime(
    val apiVersion: Int,
    private val executables: Map<String, File>
) {
    fun resolveExecutable(id: String): File? =
        executables[id.trim()]?.takeIf { it.isFile && it.canExecute() }

    fun availableExecutableIds(): Set<String> =
        executables.filterValues { it.isFile && it.canExecute() }.keys.toSet()

    companion object {
        val UNAVAILABLE = InProcessNativeRuntime(apiVersion = 0, executables = emptyMap())
    }
}

object InProcessNativeExecutableIds {
    const val POSIX_BASH = "posix.bash"
    const val BUSYBOX = "tool.busybox"
    const val PROOT = "sandbox.proot"
    const val PROOT_LOADER = "sandbox.proot_loader"
    const val SUDO = "privilege.sudo"
}

/** Host-bound logger. The runtime owns source identity; callers provide only tag/message. */
interface InProcessRuntimeLogger {
    fun d(tag: String, message: String): Int
    fun i(tag: String, message: String): Int
    fun w(tag: String, message: String): Int
    fun w(tag: String, message: String, error: Throwable): Int
    fun e(tag: String, message: String): Int
    fun e(tag: String, message: String, error: Throwable): Int
}

interface InProcessPluginHost {
    val applicationContext: Context
    val pluginId: String
    val version: String
    val scope: CoroutineScope
    val dataDir: File
    val cacheDir: File
    val logger: InProcessRuntimeLogger
    /** Exact mounted runtime payload (for example payload/plugin.apk). */
    val runtimeEntryFile: File
    /** Host-owned executable substrate; apiVersion=0 means unavailable. */
    val nativeRuntime: InProcessNativeRuntime
        get() = InProcessNativeRuntime.UNAVAILABLE
    val providers: InProcessProviderDirectory
    val services: InProcessServiceDirectory
    val childExtensions: InProcessChildExtensionRuntime
        get() = InProcessChildExtensionRuntime.UNAVAILABLE

    /**
     * Builds a UI Context backed by this trusted runtime APK's Resources/ClassLoader while retaining
     * the supplied Activity/window context. Ordinary non-inprocess plugins never receive this host.
     */
    fun createPluginContext(baseContext: Context): Context = baseContext

    /** Builds a resource Context for a verified child runtime APK. */
    fun createRuntimeContext(
        baseContext: Context,
        runtimeEntryFile: File,
        runtimeClassLoader: ClassLoader
    ): Context = baseContext

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

    fun registerCapability(spec: InProcessCapabilitySpec) {
        registerCapability(
            id = spec.id,
            displayName = spec.displayName,
            description = spec.description,
            executor = spec.executor
        )
    }

    fun registerHomeTile(tile: InProcessHomeTile)
    fun registerScreen(screen: InProcessScreen)

    fun registerExtension(
        point: String,
        id: String,
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    )

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
 * Identity fields are supplied by the Host child runtime from the admitted .ailx record; child code cannot
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

/** Host-owned child-extension runtime. Hub may admit new code, but does not own mounted children. */
interface InProcessChildExtensionRuntime {
    fun publishPoint(
        point: String,
        apiVersion: Int,
        title: String,
        description: String = "",
        allowedHostCapabilities: Set<String> = emptySet(),
        binder: ChildExtensionBinder
    ): AutoCloseable

    suspend fun installAdmitted(
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
    fun uiContributions(): StateFlow<List<ChildUiContributionSnapshot>>

    companion object {
        val UNAVAILABLE: InProcessChildExtensionRuntime = object : InProcessChildExtensionRuntime {
            private fun unavailable(): Nothing = error("Host child-extension runtime is unavailable")
            override fun publishPoint(point: String, apiVersion: Int, title: String, description: String, allowedHostCapabilities: Set<String>, binder: ChildExtensionBinder): AutoCloseable = unavailable()
            override suspend fun installAdmitted(packageFile: File, expectedParentPluginId: String?, expectedPoint: String?): ChildExtensionSnapshot = unavailable()
            override suspend fun uninstall(extensionId: String): Boolean = unavailable()
            override suspend fun setEnabled(extensionId: String, enabled: Boolean): ChildExtensionSnapshot = unavailable()
            override suspend fun backup(extensionId: String): ChildExtensionBackupSnapshot = unavailable()
            override suspend fun restoreBackup(extensionId: String): ChildExtensionSnapshot = unavailable()
            override suspend fun deleteBackup(extensionId: String): Boolean = unavailable()
            override suspend fun setAutoBackupPolicy(enabled: Boolean, highFrequencyUseCount: Long) = unavailable()
            override fun recordUse(extensionId: String) = unavailable()
            override fun snapshots(): StateFlow<List<ChildExtensionSnapshot>> = unavailable()
            override fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>> = unavailable()
            override fun backupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>> = unavailable()
            override fun uiContributions(): StateFlow<List<ChildUiContributionSnapshot>> = unavailable()
        }
    }
}

interface ExtensionHubService {
    /** Admission-only surface: verifies a new .ailx package, then hands it to the Host child runtime. */
    suspend fun install(
        packageFile: File,
        expectedParentPluginId: String? = null,
        expectedPoint: String? = null
    ): ChildExtensionSnapshot
}

interface ChildExtensionEntry {
    suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle
}

fun interface ChildExtensionHandle {
    suspend fun stop()
}

/** Child-owned knowledge delivered once when AI first enters this subsystem in an Interaction Cycle. */
data class ChildAiIngressDiscovery(
    val schemaId: String,
    val payloadJson: String
)

interface ChildExtensionHost {
    val applicationContext: Context
    val extensionId: String
    val version: String
    val target: ChildExtensionTarget
    val scope: CoroutineScope
    val dataDir: File
    val cacheDir: File
    val logger: InProcessRuntimeLogger
    /** Exact verified child runtime APK. */
    val runtimeEntryFile: File
    /** Parent-delegated host-owned native executable substrate. */
    val nativeRuntime: InProcessNativeRuntime
    /** Builds a UI/resource Context backed by this child runtime APK. */
    fun createExtensionContext(baseContext: Context): Context

    /** Publishes a capability owned by this verified child extension instance. */
    fun registerCapability(spec: InProcessCapabilitySpec): AutoCloseable =
        error("Child capability publication is not supported by this host")

    /**
     * Publishes child-owned AI ingress knowledge. Host binds the real extension identity and decides
     * when it is delivered; child code owns only the opaque discovery document.
     */
    fun publishAiIngressDiscovery(discovery: ChildAiIngressDiscovery): AutoCloseable =
        error("AI ingress discovery publication is not supported by this host")

    fun publish(
        payload: Any,
        metadata: Map<String, String> = emptyMap()
    )

    /**
     * Contributes Plugin Center-defined UI into a slot explicitly opened by this extension's parent.
     *
     * The caller names only the parent's screen/component/slot and its own contribution id. Extension
     * Host binds the real extension identity and target from the admitted manifest, and Plugin Center
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
