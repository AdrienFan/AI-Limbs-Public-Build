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
}

sealed interface InProcessScreenBlock {
    data class Text(val text: String) : InProcessScreenBlock
    data class CapabilityButton(
        val label: String,
        val capabilityId: String,
        val parametersJson: String = "{}"
    ) : InProcessScreenBlock
    data class ChildExtensionSelector(
        val label: String,
        val point: String,
        val selectCapabilityId: String,
        val selectionProviderId: String? = null
    ) : InProcessScreenBlock
    data class ChildExtensionInstaller(
        val label: String,
        val point: String
    ) : InProcessScreenBlock
    data class ChildExtensionList(
        val point: String
    ) : InProcessScreenBlock
    data class DynamicPanel(
        val providerId: String
    ) : InProcessScreenBlock
}

enum class InProcessPanelFieldKind { TEXT, SECRET }

data class InProcessPanelField(
    val id: String,
    val label: String,
    val kind: InProcessPanelFieldKind = InProcessPanelFieldKind.TEXT,
    val value: String = "",
    val placeholder: String = "",
    val enabled: Boolean = true
)

data class InProcessPanelAction(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val requiredFieldIds: Set<String> = emptySet()
)

data class InProcessPanelState(
    val title: String,
    val description: String = "",
    val statusLines: List<String> = emptyList(),
    val fields: List<InProcessPanelField> = emptyList(),
    val actions: List<InProcessPanelAction> = emptyList()
)

data class InProcessPanelResult(
    val message: String = "",
    val fieldValues: Map<String, String> = emptyMap()
)

interface InProcessDynamicPanelProvider {
    val state: StateFlow<InProcessPanelState?>
    suspend fun perform(actionId: String, fieldValues: Map<String, String>): InProcessPanelResult
}

interface InProcessSelectionProvider {
    val selectedId: StateFlow<String?>
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

data class InProcessScreen(
    val id: String,
    val title: String,
    val description: String? = null,
    val blocks: List<InProcessScreenBlock>
)

interface InProcessPluginHost {
    val applicationContext: Context
    val pluginId: String
    val version: String
    val scope: CoroutineScope
    val dataDir: File
    val cacheDir: File
    val providers: InProcessProviderDirectory

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

    suspend fun invokeHostCapability(id: String, parametersJson: String = "{}"): String
}

object InProcessSystemIds {
    const val EXTENSION_HUB_PROVIDER = "system.extension.hub"
    const val EXTENSION_HUB_PLUGIN_ID = "plugin.system.extension_hub"
    const val BRIDGE_PLUGIN_ID = "plugin.system.bridge"
    const val BRIDGE_PROVIDER_POINT = "ai_limbs.bridge.provider"
    const val NOTIFICATION_HOST_PROVIDER = "system.notification.host"
}
