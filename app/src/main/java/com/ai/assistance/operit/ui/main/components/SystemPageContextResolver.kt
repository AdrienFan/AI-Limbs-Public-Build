package com.ai.assistance.operit.ui.main.components

import com.ai.assistance.operit.plugins.center.DynamicNavigationBinding
import com.ai.assistance.operit.plugins.center.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.center.PluginScreenSpec
import com.ai.assistance.operit.plugins.system.SystemPageContextV1
import com.ai.assistance.operit.ui.main.navigation.RouteEntry
import com.ai.assistance.operit.ui.main.screens.Screen

internal fun resolveSystemPageContext(
    routeEntry: RouteEntry,
    screen: Screen,
    pluginScreens: List<PluginScreenSpec>,
    pluginHomeTiles: List<PluginHomeTileSpec>,
    dynamicBindings: List<DynamicNavigationBinding>
): SystemPageContextV1 = when (screen) {
    is Screen.PluginDeclarativePage -> {
        val pluginScreen = pluginScreens.firstOrNull { it.id == screen.screenId }
        SystemPageContextV1(
            pageId = "plugin:${screen.screenId}",
            kind = "plugin",
            ownerPluginId = pluginScreen?.ownerPluginId,
            screenId = screen.screenId,
            documentJson = pluginScreen?.documentJson
        )
    }

    is Screen.DynamicNavigationPage -> SystemPageContextV1(
        pageId = "dynamic:${screen.surfaceId}",
        kind = "dynamic",
        surfaceId = screen.surfaceId,
        embeddedPluginIds = dynamicBindings
            .asSequence()
            .filter { it.surfaceId == screen.surfaceId }
            .map { it.ownerPluginId }
            .distinct()
            .toList()
    )

    is Screen.SystemPluginPage -> SystemPageContextV1(
        pageId = "system:${screen.entryId}",
        kind = "system_plugin"
    )

    Screen.Toolbox -> SystemPageContextV1(
        pageId = "host:${routeEntry.routeId}",
        kind = "host",
        embeddedPluginIds = pluginHomeTiles
            .asSequence()
            .map { it.ownerPluginId }
            .distinct()
            .toList()
    )

    else -> SystemPageContextV1(
        pageId = "host:${routeEntry.routeId}",
        kind = "host"
    )
}
