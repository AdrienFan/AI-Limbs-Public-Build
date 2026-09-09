package com.ai.limbs.plugins.systemenvironment

import com.ai.limbs.plugin.runtime.ChildExtensionBinding
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentSubsystemContribution
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class MountedSystemEnvironment(
    val extensionId: String,
    val version: String,
    val displayName: String,
    val contribution: SystemEnvironmentSubsystemContribution
)

internal class SystemEnvironmentSubsystemRegistry {
    private val mountedById = ConcurrentHashMap<String, MountedSystemEnvironment>()
    private val mutableMounted = MutableStateFlow<List<MountedSystemEnvironment>>(emptyList())
    private val mutableForegroundExtensionId = MutableStateFlow<String?>(null)

    val mounted: StateFlow<List<MountedSystemEnvironment>> = mutableMounted.asStateFlow()
    val foregroundExtensionId: StateFlow<String?> = mutableForegroundExtensionId.asStateFlow()

    fun bind(binding: ChildExtensionBinding): AutoCloseable {
        val contribution = binding.payload as? SystemEnvironmentSubsystemContribution
            ?: error("System environment child published an incompatible contribution")
        require(contribution.subsystemId == binding.extensionId) {
            "Contribution subsystemId must match the attested extension identity"
        }
        require(contribution.capabilities.supportedCapabilityIds.isNotEmpty()) {
            "System environment child must publish at least one capability"
        }
        val mounted = MountedSystemEnvironment(
            extensionId = binding.extensionId,
            version = binding.version,
            displayName = binding.displayName,
            contribution = contribution
        )
        check(mountedById.putIfAbsent(binding.extensionId, mounted) == null) {
            "System environment child is already bound: ${binding.extensionId}"
        }
        publishMounted()
        if (mutableForegroundExtensionId.value == null) {
            mutableForegroundExtensionId.value = binding.extensionId
        }
        return AutoCloseable {
            if (mountedById.remove(binding.extensionId, mounted)) {
                if (mutableForegroundExtensionId.value == binding.extensionId) {
                    mutableForegroundExtensionId.value = null
                }
                publishMounted()
            }
        }
    }

    fun setForeground(extensionId: String?) {
        if (extensionId != null) {
            require(mountedById.containsKey(extensionId)) {
                "Cannot display an inactive system environment: $extensionId"
            }
        }
        mutableForegroundExtensionId.value = extensionId
    }

    fun foreground(): MountedSystemEnvironment? =
        mutableForegroundExtensionId.value?.let(mountedById::get)

    suspend fun invoke(capabilityId: String, parametersJson: String): String {
        val parameters = if (parametersJson.isBlank()) JSONObject() else JSONObject(parametersJson)
        val requestedSubsystemId = parameters.optString("subsystem_id").trim()
        val mounted = if (requestedSubsystemId.isNotBlank()) {
            mountedById[requestedSubsystemId]
                ?: error("System environment is not active: $requestedSubsystemId")
        } else {
            foreground() ?: error("No active system environment is available")
        }
        val subsystemId = mounted.extensionId
        require(capabilityId in mounted.contribution.capabilities.supportedCapabilityIds) {
            "System environment $subsystemId does not support $capabilityId"
        }
        parameters.remove("subsystem_id")
        return mounted.contribution.capabilities.invoke(capabilityId, parameters.toString())
    }

    private fun publishMounted() {
        mutableMounted.value = mountedById.values.sortedBy { it.displayName.lowercase() }
    }
}
