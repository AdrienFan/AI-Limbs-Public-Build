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
        val selectCapabilityId: String
    ) : InProcessScreenBlock
    data class ChildExtensionInstaller(
        val label: String,
        val point: String
    ) : InProcessScreenBlock
    data class ChildExtensionList(
        val point: String
    ) : InProcessScreenBlock
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
    val lastError: String? = null
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
    fun snapshots(): StateFlow<List<ChildExtensionSnapshot>>
    fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>>
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
}
