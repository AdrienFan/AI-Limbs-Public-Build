package com.ai.assistance.operit.ui.main.navigation

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_NAV_SURFACE_MAIN_SIDEBAR_PLUGINS
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_NAV_SURFACE_TOOLBOX
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.common.icons.MaterialIconNameResolver
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.main.screens.ScreenRouteRegistry

object AppRouteCatalog {
    private const val DYNAMIC_ROUTE_PREFIX = "dynamic.navigation."
    private const val SYSTEM_PLUGIN_ROUTE_PREFIX = "system.plugin.page."
    private const val PLUGIN_DECLARATIVE_ROUTE_PREFIX = "plugin.declarative.page."

    fun build(context: Context): AppNavigationModel {
        val packageManager = PackageManager.getInstance(context, AIToolHandler.getInstance(context))
        val toolPkgRoutes = packageManager.getToolPkgUiRoutes(resolveContext = context).map { route ->
            RouteSpec(
                routeId = route.routeId,
                runtime = RouteRuntime.TOOLPKG_COMPOSE_DSL,
                title = route.title,
                icon = Icons.Default.Extension,
                ownerPackageName = route.containerPackageName,
                toolPkgUiModuleId = route.uiModuleId,
                keepAlive = route.keepAlive
            )
        }
        val toolPkgNavigationEntries =
            packageManager.getToolPkgNavigationEntries(resolveContext = context).mapNotNull { entry ->
                val surface = when (entry.surface.trim().lowercase()) {
                    TOOLPKG_NAV_SURFACE_TOOLBOX -> null
                    TOOLPKG_NAV_SURFACE_MAIN_SIDEBAR_PLUGINS -> NavigationSurface.MAIN_SIDEBAR_PLUGINS
                    else -> null
                } ?: return@mapNotNull null
                NavigationEntrySpec(
                    entryId = "toolpkg:${entry.containerPackageName}:${entry.entryId}",
                    routeId = entry.routeId,
                    surface = surface,
                    title = entry.title,
                    description = entry.description,
                    icon = MaterialIconNameResolver.resolveOrDefault(entry.icon, Icons.Default.Extension),
                    order = entry.order,
                    action = entry.action?.let { action ->
                        NavigationEntryActionSpec(action.functionName, action.functionSource)
                    },
                    kind = NavigationEntryKind.PLUGIN,
                    ownerPackageName = entry.containerPackageName
                )
            }

        val dynamicSurfaces = runCatching { PluginPlatformKernel.dynamicNavigationRegistry.snapshot() }
            .getOrDefault(emptyList())
        val dynamicRoutes = dynamicSurfaces.map { surface ->
            RouteSpec(
                routeId = dynamicRouteId(surface.id),
                runtime = RouteRuntime.NATIVE,
                title = surface.title,
                icon = MaterialIconNameResolver.resolveOrDefault(surface.iconKey, Icons.Default.Extension)
            )
        }
        val dynamicEntries = dynamicSurfaces.map { surface ->
            NavigationEntrySpec(
                entryId = "dynamic:${surface.id}",
                routeId = dynamicRouteId(surface.id),
                surface = NavigationSurface.MAIN_SIDEBAR_DYNAMIC,
                title = surface.title,
                description = "动态页面",
                icon = MaterialIconNameResolver.resolveOrDefault(surface.iconKey, Icons.Default.Extension),
                order = surface.order,
                routeArgs = mapOf("surfaceId" to surface.id)
            )
        }

        val pluginHomeTiles = runCatching { PluginPlatformKernel.uiRegistry.homeTiles.value }
            .getOrDefault(emptyList())
            .filter { tile -> PluginPlatformKernel.uiRegistry.screen(tile.screenId) != null }
        val pluginRoutes = pluginHomeTiles
            .distinctBy { it.screenId }
            .map { tile ->
                RouteSpec(
                    routeId = pluginDeclarativeRouteId(tile.screenId),
                    runtime = RouteRuntime.NATIVE,
                    title = PluginPlatformKernel.uiRegistry.screen(tile.screenId)?.title ?: tile.title,
                    icon = Icons.Default.Extension
                )
            }
        val pluginToolboxEntries = pluginHomeTiles.mapIndexed { index, tile ->
            NavigationEntrySpec(
                entryId = "plugin:${tile.ownerPluginId}:${tile.id}",
                routeId = pluginDeclarativeRouteId(tile.screenId),
                surface = NavigationSurface.TOOLBOX,
                title = tile.title,
                description = tile.description,
                icon = Icons.Default.Extension,
                order = 100 + index,
                routeArgs = mapOf("screenId" to tile.screenId),
                kind = NavigationEntryKind.PLUGIN
            )
        }

        val systemUiEntries = runCatching { PluginPlatformKernel.systemUiRegistry.toolboxEntries.value }
            .getOrDefault(emptyList())
        val systemRoutes = systemUiEntries.map { entry ->
            RouteSpec(
                routeId = systemPluginRouteId(entry.id),
                runtime = RouteRuntime.NATIVE,
                title = entry.title,
                icon = MaterialIconNameResolver.resolveOrDefault(entry.iconKey, Icons.Default.Extension)
            )
        }
        val systemToolboxEntries = systemUiEntries.mapIndexed { index, entry ->
            NavigationEntrySpec(
                entryId = "system:${entry.id}",
                routeId = systemPluginRouteId(entry.id),
                surface = NavigationSurface.TOOLBOX,
                title = entry.title,
                description = entry.description,
                icon = MaterialIconNameResolver.resolveOrDefault(entry.iconKey, Icons.Default.Extension),
                order = index,
                routeArgs = mapOf("entryId" to entry.id),
                kind = NavigationEntryKind.PLUGIN
            )
        }

        val hostToolboxEntries = ScreenRouteRegistry.toolboxEntries(context).let { entries ->
            if (systemUiEntries.isNotEmpty()) {
                entries.filterNot { it.entryId == "toolbox.plugin_center_bootstrap" }
            } else {
                entries
            }
        }

        return AppNavigationModel(
            routes = ScreenRouteRegistry.hostRouteSpecs(context) + toolPkgRoutes + dynamicRoutes + pluginRoutes + systemRoutes,
            navigationEntries = (
                ScreenRouteRegistry.mainSidebarEntries(context) +
                    hostToolboxEntries +
                    toolPkgNavigationEntries +
                    dynamicEntries +
                    pluginToolboxEntries +
                    systemToolboxEntries
                ).sortedWith(compareBy<NavigationEntrySpec>({ it.surface.ordinal }, { it.order }, { it.title }))
        )
    }

    fun resolveScreen(model: AppNavigationModel, entry: RouteEntry): Screen? {
        if (entry.routeId.startsWith(DYNAMIC_ROUTE_PREFIX)) {
            val surfaceId = (entry.args["surfaceId"] as? String)
                ?: entry.routeId.removePrefix(DYNAMIC_ROUTE_PREFIX)
            return Screen.DynamicNavigationPage(surfaceId)
        }
        if (entry.routeId.startsWith(PLUGIN_DECLARATIVE_ROUTE_PREFIX)) {
            val screenId = (entry.args["screenId"] as? String)
                ?: entry.routeId.removePrefix(PLUGIN_DECLARATIVE_ROUTE_PREFIX)
            return Screen.PluginDeclarativePage(screenId)
        }
        if (entry.routeId.startsWith(SYSTEM_PLUGIN_ROUTE_PREFIX)) {
            val entryId = (entry.args["entryId"] as? String)
                ?: entry.routeId.removePrefix(SYSTEM_PLUGIN_ROUTE_PREFIX)
            return Screen.SystemPluginPage(entryId)
        }
        ScreenRouteRegistry.screenFromEntry(entry)?.let { return it }

        val spec = model.routesById[entry.routeId] ?: return null
        if (spec.runtime != RouteRuntime.TOOLPKG_COMPOSE_DSL) return null
        val containerPackageName = spec.ownerPackageName ?: return null
        val uiModuleId = spec.toolPkgUiModuleId ?: return null
        return Screen.ToolPkgComposeDsl(
            containerPackageName = containerPackageName,
            uiModuleId = uiModuleId,
            title = spec.title ?: uiModuleId,
            keepAlive = spec.keepAlive
        )
    }

    fun initialEntry(navItem: NavItem): RouteEntry = ScreenRouteRegistry.initialEntry(navItem)

    fun toEntry(
        screen: Screen,
        source: RouteEntrySource = RouteEntrySource.DEFAULT
    ): RouteEntry = ScreenRouteRegistry.toEntry(screen = screen, source = source)

    private fun dynamicRouteId(surfaceId: String): String = DYNAMIC_ROUTE_PREFIX + surfaceId
    private fun systemPluginRouteId(entryId: String): String = SYSTEM_PLUGIN_ROUTE_PREFIX + entryId
    private fun pluginDeclarativeRouteId(screenId: String): String = PLUGIN_DECLARATIVE_ROUTE_PREFIX + screenId
}
