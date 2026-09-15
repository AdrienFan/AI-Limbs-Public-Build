package com.ai.limbs.plugins.systemenvironment

import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentSubsystemContribution
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Host-only page entry. System-environment business and child lifecycle remain in Resident Core. */
class SystemEnvironmentCenterPresentationEntry : InProcessPluginPresentationEntry {
    override suspend fun mount(
        host: InProcessPluginPresentationHost
    ): InProcessPluginPresentationHandle {
        require(host.pluginId == SystemEnvironmentContract.PARENT_PLUGIN_ID) {
            "Unexpected System Environment Center presentation identity: ${host.pluginId}"
        }
        val registry = SystemEnvironmentSubsystemRegistry()
        val mounted = ConcurrentHashMap<String, MountedPresentation>()

        suspend fun reconcile() {
            val desired = host.providers.snapshot()
                .filter(::isSystemEnvironmentPresentation)
                .associateBy { it.ownerPluginId }

            mounted.entries.toList().forEach { (extensionId, current) ->
                val next = desired[extensionId]
                if (next == null || fingerprint(next) != current.fingerprint) {
                    current.handle.close()
                    mounted.remove(extensionId, current)
                }
            }
            desired.forEach { (extensionId, binding) ->
                val nextFingerprint = fingerprint(binding)
                if (mounted[extensionId]?.fingerprint == nextFingerprint) return@forEach
                val contribution = binding.payload as? SystemEnvironmentSubsystemContribution
                    ?: error("Host-local system environment presentation has incompatible payload")
                val handle = registry.bindPresentation(
                    extensionId = extensionId,
                    version = binding.metadata["version"].orEmpty(),
                    displayName = binding.metadata["display_name"].orEmpty().ifBlank { contribution.displayName },
                    contribution = contribution
                )
                val candidate = MountedPresentation(nextFingerprint, handle)
                val existing = mounted.putIfAbsent(extensionId, candidate)
                if (existing != null) {
                    handle.close()
                    error("Duplicate system environment presentation owner: $extensionId")
                }
            }
        }

        reconcile()
        val observer: Job = host.scope.launch {
            while (isActive) {
                delay(300L)
                runCatching { reconcile() }.onFailure { error ->
                    host.logger.w(
                        "SystemEnvironmentPresentation",
                        "Presentation child reconciliation failed: ${error.message}"
                    )
                }
            }
        }
        val page = host.registerPageProvider(
            PAGE_PROVIDER_ID,
            SystemEnvironmentCenterPageProvider(host, registry),
            mapOf("kind" to "plugin_page", "screen_id" to SystemEnvironmentContract.SCREEN_ID)
        )
        return InProcessPluginPresentationHandle {
            val failures = mutableListOf<Throwable>()
            observer.cancel()
            runCatching { observer.join() }.exceptionOrNull()?.let(failures::add)
            runCatching { page.close() }.exceptionOrNull()?.let(failures::add)
            mounted.values.toList().asReversed().forEach { owner ->
                runCatching { owner.handle.close() }.exceptionOrNull()?.let(failures::add)
            }
            mounted.clear()
            if (failures.isNotEmpty()) {
                throw IllegalStateException(
                    "System Environment Center presentation retirement failed"
                ).also { failure -> failures.forEach(failure::addSuppressed) }
            }
        }
    }

    private data class MountedPresentation(
        val fingerprint: String,
        val handle: AutoCloseable
    )

    private fun isSystemEnvironmentPresentation(binding: InProcessProviderBinding): Boolean =
        binding.metadata["kind"] == PRESENTATION_KIND &&
            binding.metadata["extension_id"] == binding.ownerPluginId &&
            binding.metadata["parent_plugin_id"] == SystemEnvironmentContract.PARENT_PLUGIN_ID &&
            binding.metadata["point"] == SystemEnvironmentContract.EXTENSION_POINT &&
            binding.metadata["api_version"] == SystemEnvironmentContract.API_VERSION.toString()

    private fun fingerprint(binding: InProcessProviderBinding): String =
        buildString {
            append(binding.ownerPluginId)
            append('|').append(binding.id)
            append('|').append(binding.metadata["version"].orEmpty())
            append('|').append(System.identityHashCode(binding.payload))
        }

    private companion object {
        const val PAGE_PROVIDER_ID = "plugin.system_environment_center.page"
        const val PRESENTATION_KIND = "system_environment_presentation"
    }
}
