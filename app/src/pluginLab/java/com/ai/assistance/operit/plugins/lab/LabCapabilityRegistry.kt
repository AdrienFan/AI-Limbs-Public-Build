package com.ai.assistance.operit.plugins.lab

import com.ai.assistance.operit.plugins.center.PluginCapabilityBinder
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvoker
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvokerFactory
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.HostSurfaceDefinition
import com.ai.assistance.operit.plugins.center.HostSurfaceKind
import com.ai.assistance.operit.plugins.center.HostSurfacePolicy
import com.ai.assistance.operit.plugins.center.PluginSurfaceIds
import com.ai.assistance.operit.plugins.center.PluginUsageStore
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Kernel-owned dispatch table. Plugins may publish only plugin.* capabilities and may call only
 * explicitly exposed core.* capabilities after the requested scopes were approved at install time.
 */
internal class LabCapabilityRegistry(
    private val surfacePolicy: HostSurfacePolicy?,
    private val usageStore: PluginUsageStore? = null
) : PluginCapabilityBinder, PluginCapabilityInvokerFactory {
    /** JVM unit-test constructor. Production wiring always supplies HostSurfacePolicy. */
    internal constructor() : this(null, null)

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
        "core.logs.read" to HostCapability("host.logs.read", ::readLogs),
        "core.bridge.remote.invoke" to HostCapability(null, ::bridgeRemoteInvoke)
    )

    init {
        surfacePolicy?.register(
            HostSurfaceDefinition(
                PluginSurfaceIds.PUBLISH_CAPABILITY,
                "plugin.* capability 发布总线",
                "允许插件注册自己的无界面能力",
                HostSurfaceKind.PLUGIN_CAPABILITY_BUS
            )
        )
        surfacePolicy?.register(
            HostSurfaceDefinition(
                PluginSurfaceIds.PUBLISH_SERVICE,
                "Plugin Service 发布总线",
                "允许插件向宿主发布 service",
                HostSurfaceKind.PLUGIN_SERVICE_BUS
            )
        )
        surfacePolicy?.register(
            HostSurfaceDefinition(
                PluginSurfaceIds.PUBLISH_PROVIDER,
                "Plugin Provider 发布总线",
                "允许插件向宿主发布 provider",
                HostSurfaceKind.PLUGIN_PROVIDER_BUS
            )
        )
        hostCapabilities.forEach { (id, capability) ->
            surfacePolicy?.register(
                HostSurfaceDefinition(
                    id = PluginSurfaceIds.hostCapability(id),
                    title = id,
                    detail = capability.requiredScope?.let { "宿主能力 · scope: $it · 可在 mount 前预判" }
                        ?: "宿主能力 · 无预声明 scope · 每次调用时强制检查策略",
                    kind = HostSurfaceKind.HOST_CAPABILITY,
                    requiredScope = capability.requiredScope
                )
            )
        }
    }

    override fun register(
        ownerPluginId: String,
        capabilityId: String,
        capability: PluginCapabilitySpec
    ): AutoCloseable {
        surfacePolicy?.requireAllowed(PluginSurfaceIds.PUBLISH_CAPABILITY)
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
        val result = capability.spec.executor.execute(JSONObject(parameters.toString()))
        usageStore?.recordUse(capability.ownerPluginId)
        return result
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
            surfacePolicy?.requireAllowed(PluginSurfaceIds.hostCapability(normalized))
            val scope = capability.requiredScope
            if (scope != null && scope !in grantedScopes) {
                throw PluginInstallException(
                    "PLUGIN_SCOPE_DENIED",
                    "$ownerPluginId was not granted required scope: $scope"
                )
            }
            capability.execute(JSONObject(parameters.toString()))
        }


    private suspend fun bridgeRemoteInvoke(parameters: JSONObject): JSONObject {
        val tool = parameters.optString("tool").trim()
        val transport = parameters.optString("transport").trim()
        val args = parameters.optJSONObject("args") ?: JSONObject()
        return when (tool.lowercase()) {
            "ping" -> JSONObject().put("success", true).put("content", "Pong").put("transport", transport)
            "core.runtime.info" -> JSONObject().put("success", true).put("kernel", "AI Limbs Plugin Lab").put("transport", transport)
            else -> JSONObject()
                .put("success", false)
                .put("error", "Plugin Lab does not expose the formal AI Limbs dispatcher for '$tool' yet")
                .put("transport", transport)
                .put("args", args)
        }
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
