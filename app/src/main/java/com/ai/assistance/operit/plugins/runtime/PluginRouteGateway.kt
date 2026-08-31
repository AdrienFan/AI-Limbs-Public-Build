package com.ai.assistance.operit.plugins.runtime

enum class PluginRouteRuntime {
    NATIVE,
    TOOLPKG_COMPOSE_DSL
}

enum class PluginRouteEntrySource {
    DEFAULT,
    SCRIPT
}

data class PluginRouteSpec(
    val routeId: String,
    val runtime: PluginRouteRuntime,
    val title: String? = null,
    val ownerPackageName: String? = null,
    val toolPkgUiModuleId: String? = null
)

object PluginRouterGateway {
    @Volatile
    private var navigateHandler: ((String, Map<String, Any?>, PluginRouteEntrySource) -> Unit)? = null

    fun install(handler: (String, Map<String, Any?>, PluginRouteEntrySource) -> Unit) {
        navigateHandler = handler
    }

    fun clear() {
        navigateHandler = null
    }

    fun navigate(
        routeId: String,
        args: Map<String, Any?> = emptyMap(),
        source: PluginRouteEntrySource = PluginRouteEntrySource.SCRIPT
    ) {
        navigateHandler?.invoke(routeId, args, source)
    }
}

object PluginRouteDiscoveryGateway {
    @Volatile
    private var routesProvider: (() -> List<PluginRouteSpec>)? = null

    fun install(provider: () -> List<PluginRouteSpec>) {
        routesProvider = provider
    }

    fun clear() {
        routesProvider = null
    }

    fun listRoutes(): List<PluginRouteSpec> = routesProvider?.invoke().orEmpty()
}
