package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.plugins.center.AiLimbsHostPrimitiveCatalog
import com.ai.assistance.operit.plugins.center.HostPrimitiveExposure
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.plugins.center.PluginSurfaceIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only developer discovery catalog. The Plugin Center eye switch is the user-owned gate.
 * Visibility never grants invocation permission; execution still follows manifest + policy checks.
 */
internal class AiLimbsDeveloperCatalogService {
    fun read(args: JSONObject): JSONObject {
        if (!PluginPlatformKernel.isInitialized) {
            return disabled("PLUGIN_PLATFORM_NOT_READY", "Plugin Platform Kernel 尚未初始化")
        }
        val surfacePolicy = PluginPlatformKernel.hostSurfacePolicy
        if (!surfacePolicy.developerDiscoveryEnabled) {
            return disabled("DEVELOPER_DISCOVERY_DISABLED", "开发接口发现已关闭；请在 Plugin Center 开发界面打开 👁️")
        }

        val query = args.optString("query").trim().lowercase()
        val primitives = JSONArray()
        AiLimbsHostPrimitiveCatalog.all
            .filter { definition ->
                query.isBlank() || listOf(
                    "HP-${definition.number.toString().padStart(3, '0')}",
                    definition.id,
                    definition.title,
                    definition.description,
                    definition.boundary,
                    definition.maturity.name,
                    definition.exposure.name
                ).any { it.lowercase().contains(query) }
            }
            .forEach { definition ->
                val policyAllowed = if (
                    definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND
                ) {
                    surfacePolicy.isAllowed(PluginSurfaceIds.hostPrimitive(definition.id))
                } else null
                primitives.put(
                    JSONObject()
                        .put("number", definition.number)
                        .put("id", definition.id)
                        .put("title", definition.title)
                        .put("description", definition.description)
                        .put("boundary", definition.boundary)
                        .put("maturity", definition.maturity.name)
                        .put("exposure", definition.exposure.name)
                        .put("requestable_scope", definition.requestableScope)
                        .put("policy_allowed", policyAllowed ?: JSONObject.NULL)
                        .put("callable", PluginPlatformKernel.capabilities.isHostCallable(definition.id))
                )
            }

        val bindingCounts = PluginPlatformKernel.extensionRouter.listBindings()
            .groupingBy { it.point }
            .eachCount()
        val extensionPoints = JSONArray()
        PluginPlatformKernel.extensionPoints.list()
            .filter { definition ->
                query.isBlank() || listOf(
                    definition.point,
                    "api ${definition.apiVersion}",
                    "extension point"
                ).any { it.lowercase().contains(query) }
            }
            .forEach { definition ->
                extensionPoints.put(
                    JSONObject()
                        .put("point", definition.point)
                        .put("api_version", definition.apiVersion)
                        .put("allowed", surfacePolicy.isAllowed(PluginSurfaceIds.extension(definition.point)))
                        .put("active_binding_count", bindingCounts[definition.point] ?: 0)
                )
            }

        val surfaces = JSONArray()
        surfacePolicy.snapshots()
            .filter { item ->
                val d = item.definition
                query.isBlank() || listOf(
                    d.id,
                    d.title,
                    d.detail,
                    d.kind.name,
                    d.requiredScope.orEmpty(),
                    d.publicContracts.joinToString(" ")
                ).any { it.lowercase().contains(query) }
            }
            .forEach { item ->
                val d = item.definition
                surfaces.put(
                    JSONObject()
                        .put("id", d.id)
                        .put("title", d.title)
                        .put("detail", d.detail)
                        .put("kind", d.kind.name)
                        .put("required_scope", d.requiredScope ?: JSONObject.NULL)
                        .put("public_contracts", JSONArray(d.publicContracts))
                        .put("allowed", item.allowed)
                )
            }

        return JSONObject()
            .put("status", "ok")
            .put("enabled", true)
            .put("read_only", true)
            .put("query", query)
            .put("catalog_schema", "AI_LIMBS_DEVELOPER_CATALOG_V1")
            .put("host_primitive_count", primitives.length())
            .put("extension_point_count", extensionPoints.length())
            .put("surface_count", surfaces.length())
            .put("host_primitives", primitives)
            .put("extension_points", extensionPoints)
            .put("surfaces", surfaces)
            .put(
                "note",
                "目录可见不等于获得调用权限；任何实际执行仍受 manifest scope、Host Surface Policy 与 ALLOW/ASK/FORBID 约束。"
            )
    }

    private fun disabled(code: String, message: String): JSONObject =
        JSONObject()
            .put("status", "error")
            .put("error_code", code)
            .put("enabled", false)
            .put("message", message)
}
