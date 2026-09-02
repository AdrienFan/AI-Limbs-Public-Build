package com.ai.assistance.operit.plugins.system

import com.ai.assistance.operit.plugins.center.AdminSecurityManager
import com.ai.assistance.operit.plugins.center.DynamicNavigationBinding
import com.ai.assistance.operit.plugins.center.DynamicNavigationSurfaceRegistry
import com.ai.assistance.operit.plugins.center.DynamicNavigationSurfaceSpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginUiRegistry
import org.json.JSONArray
import org.json.JSONObject

internal class KernelDynamicNavigationJsonServiceV1(
    private val registry: DynamicNavigationSurfaceRegistry,
    private val uiRegistry: PluginUiRegistry,
    private val adminSecurity: AdminSecurityManager
) : SystemJsonServiceV1 {
    override suspend fun call(operation: String, parameters: JSONObject): JSONObject =
        when (operation.trim().lowercase()) {
            "list_surfaces" -> listSurfaces()
            "describe_surface" -> describeSurface(parameters)
            "create_surface" -> createSurface(parameters)
            "rename_surface" -> renameSurface(parameters)
            "delete_surface" -> deleteSurface(parameters)
            "list_contributions" -> listContributions()
            "bind_contribution" -> bindContribution(parameters)
            "unbind_contribution" -> unbindContribution(parameters)
            else -> throw PluginInstallException(
                "DYNAMIC_NAV_OPERATION_UNSUPPORTED",
                "Unsupported dynamic navigation operation: $operation"
            )
        }

    private fun listSurfaces(): JSONObject = JSONObject().put(
        "surfaces",
        JSONArray().apply { registry.snapshot().forEach { put(surfaceJson(it)) } }
    )

    private fun describeSurface(parameters: JSONObject): JSONObject {
        val surface = registry.find(required(parameters, "surface_id"))
            ?: throw PluginInstallException("DYNAMIC_SURFACE_NOT_FOUND", "动态页面不存在")
        return JSONObject().put("surface", surfaceJson(surface))
    }

    private fun createSurface(parameters: JSONObject): JSONObject {
        val surface = registry.create(
            title = parameters.optString("title").trim().ifBlank { null },
            iconKey = parameters.optString("icon_key", "extension")
        )
        return JSONObject().put("surface", surfaceJson(surface))
    }

    private fun renameSurface(parameters: JSONObject): JSONObject {
        val surface = registry.rename(
            surfaceId = required(parameters, "surface_id"),
            title = required(parameters, "title"),
            iconKey = parameters.optString("icon_key").trim().ifBlank { null }
        )
        return JSONObject().put("surface", surfaceJson(surface))
    }

    private fun deleteSurface(parameters: JSONObject): JSONObject {
        val surfaceId = required(parameters, "surface_id")
        val password = required(parameters, "admin_password")
        if (!adminSecurity.snapshot().configured) {
            throw PluginInstallException(
                "ADMIN_SECURITY_NOT_CONFIGURED",
                "删除动态页面前必须先设置 Plugin Center 管理员密码"
            )
        }
        if (!adminSecurity.verifyPassword(password)) {
            throw PluginInstallException("ADMIN_PASSWORD_INVALID", "管理员密码错误")
        }
        // Kernel-side second check. There is deliberately no cascade delete path.
        registry.deleteIfEmpty(surfaceId)
        return JSONObject().put("deleted", true).put("surface_id", surfaceId)
    }

    private fun listContributions(): JSONObject {
        val allBindings = registry.bindings.value
        val items = uiRegistry.homeTileSnapshots().map { tile ->
            JSONObject()
                .put("owner_plugin_id", tile.ownerPluginId)
                .put("tile_id", tile.id)
                .put("title", tile.title)
                .put("description", tile.description)
                .put("screen_id", tile.screenId)
                .put("screen_active", uiRegistry.screen(tile.screenId) != null)
                .put("surface_ids", JSONArray().apply {
                    allBindings.filter { it.tileId == tile.id && it.ownerPluginId == tile.ownerPluginId }
                        .forEach { put(it.surfaceId) }
                })
        }
        return JSONObject().put("contributions", JSONArray(items))
    }

    private fun bindContribution(parameters: JSONObject): JSONObject {
        val surfaceId = required(parameters, "surface_id")
        val tileId = required(parameters, "tile_id")
        val tile = uiRegistry.homeTile(tileId)
            ?: throw PluginInstallException("PLUGIN_UI_TILE_NOT_ACTIVE", "插件 UI 入口不存在：$tileId")
        if (uiRegistry.screen(tile.screenId) == null) {
            throw PluginInstallException("PLUGIN_UI_SCREEN_NOT_ACTIVE", "插件页面未激活：${tile.screenId}")
        }
        registry.bind(surfaceId, tile)
        return JSONObject().put("bound", true)
            .put("surface_id", surfaceId)
            .put("tile_id", tileId)
    }

    private fun unbindContribution(parameters: JSONObject): JSONObject {
        val surfaceId = required(parameters, "surface_id")
        val tileId = required(parameters, "tile_id")
        registry.unbind(surfaceId, tileId)
        return JSONObject().put("unbound", true)
            .put("surface_id", surfaceId)
            .put("tile_id", tileId)
    }

    private fun surfaceJson(surface: DynamicNavigationSurfaceSpec): JSONObject {
        val bindings = registry.bindingsFor(surface.id)
        return JSONObject()
            .put("surface_id", surface.id)
            .put("title", surface.title)
            .put("icon_key", surface.iconKey)
            .put("order", surface.order)
            .put("created_at", surface.createdAt)
            .put("binding_count", bindings.size)
            .put("empty", bindings.isEmpty())
            .put("bindings", JSONArray().apply { bindings.forEach { put(bindingJson(it)) } })
    }

    private fun bindingJson(binding: DynamicNavigationBinding): JSONObject = JSONObject()
        .put("surface_id", binding.surfaceId)
        .put("owner_plugin_id", binding.ownerPluginId)
        .put("tile_id", binding.tileId)
        .put("screen_id", binding.screenId)

    private fun required(parameters: JSONObject, key: String): String =
        parameters.optString(key).trim().takeIf { it.isNotEmpty() }
            ?: throw PluginInstallException("DYNAMIC_NAV_FIELD_REQUIRED", "$key is required")
}
