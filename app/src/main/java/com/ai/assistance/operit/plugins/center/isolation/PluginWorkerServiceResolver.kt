package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.plugins.center.HostSurfacePolicy
import com.ai.assistance.operit.plugins.center.PluginCallerLease
import com.ai.assistance.operit.plugins.center.PluginContributionRegistry
import com.ai.assistance.operit.plugins.center.PluginManifest
import com.ai.assistance.operit.plugins.center.PluginResolvedService
import com.ai.assistance.operit.plugins.center.PluginServiceResolver
import com.ai.assistance.operit.plugins.center.PluginSurfaceIds
import com.ai.assistance.operit.plugins.center.ScopedPluginServiceResolver
import org.json.JSONObject

/** Worker service directory: local plugin services first, authoritative Core services second. */
internal class PluginWorkerServiceResolver(
    private val manifest: PluginManifest,
    private val version: String,
    grantedScopes: Set<String>,
    contributions: PluginContributionRegistry,
    private val surfacePolicy: HostSurfacePolicy,
    private val callerLease: PluginCallerLease,
    private val coreClient: PluginRuntimeCoreClient
) : PluginServiceResolver {
    private val local = ScopedPluginServiceResolver(manifest, grantedScopes, contributions, surfacePolicy, callerLease)

    override fun resolve(serviceId: String, minApi: Int?): PluginResolvedService? {
        local.resolve(serviceId, minApi)?.let { return it }
        val remote = coreClient.describeService(manifest.pluginId, version, serviceId.trim(), minApi)
        if (!remote.optBoolean("available", false)) return null
        val metadataJson = remote.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap<String, String> {
            val keys = metadataJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, metadataJson.optString(key))
            }
        }
        val ownerPluginId = remote.getString("owner_plugin_id")
        val apiVersion = remote.getInt("api_version")
        return PluginResolvedService(
            ownerPluginId = ownerPluginId,
            serviceId = serviceId.trim(),
            apiVersion = apiVersion,
            metadata = metadata,
            invokeOperation = { operation, parameters ->
                callerLease.requireActive(manifest.pluginId)
                surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_SERVICE)
                coreClient.invokeService(
                    manifest.pluginId, version, serviceId.trim(), minApi,
                    operation, JSONObject(parameters.toString())
                )
            }
        )
    }
}
