package com.ai.assistance.operit.plugins.lab

import com.ai.assistance.operit.plugins.center.PluginCapabilityBinder
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvoker
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvokerFactory
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Kernel-owned dispatch table. Plugins may publish only plugin.* capabilities and may call only
 * explicitly exposed core.* capabilities after the requested scopes were approved at install time.
 */
internal class LabCapabilityRegistry : PluginCapabilityBinder, PluginCapabilityInvokerFactory {
    private data class OwnedCapability(
        val token: String,
        val ownerPluginId: String,
        val spec: PluginCapabilitySpec
    )

    private data class HostCapability(
        val requiredScope: String?,
        val execute: suspend (JSONObject) -> JSONObject
    )

    private val capabilities = ConcurrentHashMap<String, OwnedCapability>()
    private val hostCapabilities = mapOf(
        "core.runtime.info" to HostCapability(null) {
            JSONObject()
                .put("kernel", "AI Limbs Plugin Lab")
                .put("plugin_api", 1)
                .put("runtime", "declarative-v1")
        },
        "core.logs.read" to HostCapability("host.logs.read", ::readLogs)
    )

    override fun register(
        ownerPluginId: String,
        capabilityId: String,
        capability: PluginCapabilitySpec
    ): AutoCloseable {
        val normalized = capabilityId.trim().lowercase()
        if (!normalized.startsWith("plugin.") || !CAPABILITY_ID.matches(normalized)) {
            throw PluginInstallException(
                "CAPABILITY_NAMESPACE_FORBIDDEN",
                "Plugin capabilities must use the plugin.* namespace: $capabilityId"
            )
        }
        val token = UUID.randomUUID().toString()
        val candidate = OwnedCapability(token, ownerPluginId, capability)
        val existing = capabilities.putIfAbsent(normalized, candidate)
        if (existing != null) {
            throw PluginInstallException(
                "CAPABILITY_CONFLICT",
                "$normalized is already owned by ${existing.ownerPluginId}"
            )
        }
        return AutoCloseable {
            capabilities.computeIfPresent(normalized) { _, current ->
                if (current.token == token) null else current
            }
        }
    }

    suspend fun invokePlugin(capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject {
        val normalized = capabilityId.trim().lowercase()
        val capability = capabilities[normalized]
            ?: throw PluginInstallException("CAPABILITY_NOT_ACTIVE", "Capability is not active: $normalized")
        return capability.spec.executor.execute(JSONObject(parameters.toString()))
    }

    fun activeIds(): Set<String> = capabilities.keys.toSortedSet()

    override fun create(ownerPluginId: String, grantedScopes: Set<String>): PluginCapabilityInvoker =
        PluginCapabilityInvoker { capabilityId, parameters ->
            val normalized = capabilityId.trim().lowercase()
            val capability = hostCapabilities[normalized]
                ?: throw PluginInstallException(
                    "HOST_CAPABILITY_NOT_EXPOSED",
                    "Host capability is not exposed to plugins: $normalized"
                )
            val scope = capability.requiredScope
            if (scope != null && scope !in grantedScopes) {
                throw PluginInstallException(
                    "PLUGIN_SCOPE_DENIED",
                    "$ownerPluginId was not granted required scope: $scope"
                )
            }
            capability.execute(JSONObject(parameters.toString()))
        }

    private suspend fun readLogs(parameters: JSONObject): JSONObject {
        val maximum = parameters.optInt("max_chars", 60_000).coerceIn(1_000, 120_000)
        val logFile = AppLogger.getLogFile()
        val full = if (logFile?.isFile == true) logFile.readText() else ""
        val content = if (full.length > maximum) full.takeLast(maximum) else full
        return JSONObject()
            .put("content", content)
            .put("truncated", full.length > content.length)
            .put("characters", content.length)
    }

    private companion object {
        val CAPABILITY_ID = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
    }
}
