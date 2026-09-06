package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.data.model.ModelConfigData
import java.util.concurrent.ConcurrentHashMap

/** Public typed payload for ai_limbs.model.local_loader@1. */
data class PluginLocalModelLoaderSpec(
    val displayName: String,
    val providerTypeIds: Set<String>,
    val createService: (ModelConfigData) -> AIService
)

data class LocalModelLoaderSnapshot(
    val loaderId: String,
    val ownerPluginId: String?,
    val displayName: String,
    val providerTypeIds: Set<String>,
    val builtIn: Boolean
)

class LocalModelLoaderRegistry {
    private data class Entry(
        val loaderId: String,
        val ownerPluginId: String?,
        val displayName: String,
        val providerTypeIds: Set<String>,
        val createService: ((ModelConfigData) -> AIService)?
    )

    private val entriesByLoaderId = ConcurrentHashMap<String, Entry>()
    private val loaderIdByProviderType = ConcurrentHashMap<String, String>()

    fun registerBuiltIn(loaderId: String, displayName: String, providerTypeIds: Set<String>) {
        register(loaderId, null, displayName, providerTypeIds, null)
    }

    fun registerPlugin(
        ownerPluginId: String,
        extensionId: String,
        spec: PluginLocalModelLoaderSpec
    ): AutoCloseable {
        require(ownerPluginId.isNotBlank()) { "ownerPluginId must not be blank" }
        val loaderId = "plugin:${ownerPluginId.trim()}:${extensionId.trim()}"
        register(loaderId, ownerPluginId.trim(), spec.displayName, spec.providerTypeIds, spec.createService)
        return AutoCloseable { unregister(loaderId) }
    }

    fun createPluginService(providerTypeId: String, config: ModelConfigData): AIService? {
        val loaderId = loaderIdByProviderType[normalizeProviderType(providerTypeId)] ?: return null
        val entry = entriesByLoaderId[loaderId] ?: return null
        return entry.createService?.invoke(config)
    }

    fun snapshots(): List<LocalModelLoaderSnapshot> =
        entriesByLoaderId.values
            .map { entry ->
                LocalModelLoaderSnapshot(
                    loaderId = entry.loaderId,
                    ownerPluginId = entry.ownerPluginId,
                    displayName = entry.displayName,
                    providerTypeIds = entry.providerTypeIds,
                    builtIn = entry.ownerPluginId == null
                )
            }
            .sortedBy { it.loaderId }

    private fun register(
        loaderId: String,
        ownerPluginId: String?,
        displayName: String,
        providerTypeIds: Set<String>,
        createService: ((ModelConfigData) -> AIService)?
    ) {
        val normalizedLoaderId = loaderId.trim()
        require(normalizedLoaderId.isNotBlank()) { "loaderId must not be blank" }
        val normalizedDisplayName = displayName.trim()
        require(normalizedDisplayName.isNotBlank()) { "displayName must not be blank" }
        val normalizedProviders = providerTypeIds.map(::normalizeProviderType).filter { it.isNotBlank() }.toSet()
        require(normalizedProviders.isNotEmpty()) { "providerTypeIds must not be empty" }

        synchronized(this) {
            check(!entriesByLoaderId.containsKey(normalizedLoaderId)) {
                "Local model loader is already registered: $normalizedLoaderId"
            }
            normalizedProviders.forEach { providerType ->
                check(!loaderIdByProviderType.containsKey(providerType)) {
                    "Local model provider type is already owned: $providerType"
                }
            }
            val entry = Entry(
                loaderId = normalizedLoaderId,
                ownerPluginId = ownerPluginId,
                displayName = normalizedDisplayName,
                providerTypeIds = normalizedProviders,
                createService = createService
            )
            entriesByLoaderId[normalizedLoaderId] = entry
            normalizedProviders.forEach { loaderIdByProviderType[it] = normalizedLoaderId }
        }
    }

    private fun unregister(loaderId: String) {
        synchronized(this) {
            val entry = entriesByLoaderId.remove(loaderId) ?: return
            entry.providerTypeIds.forEach { providerType ->
                loaderIdByProviderType.remove(providerType, loaderId)
            }
        }
    }

    private fun normalizeProviderType(value: String): String = value.trim().lowercase()
}
