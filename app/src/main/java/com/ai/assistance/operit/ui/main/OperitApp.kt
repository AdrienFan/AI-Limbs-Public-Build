package com.ai.assistance.operit.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.plugins.center.PluginPagePresentationMode
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.data.announcement.RemoteAnnouncementDisplay
import com.ai.assistance.operit.data.announcement.RemoteAnnouncementRepository
import com.ai.assistance.operit.data.mcp.MCPRepository
import com.ai.assistance.operit.data.security.PluginDenylistRepository
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.RemoteAnnouncementPreferences
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.announcement.RemoteAnnouncementDialog
import com.ai.assistance.operit.ui.main.components.AppContent
import com.ai.assistance.operit.ui.main.layout.PhoneLayout
import com.ai.assistance.operit.ui.main.layout.TabletLayout
import com.ai.assistance.operit.ui.main.navigation.AppNavigationModel
import com.ai.assistance.operit.ui.main.navigation.AppRouteCatalog
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.main.navigation.AppRouterGateway
import com.ai.assistance.operit.ui.main.navigation.AppRouterState
import com.ai.assistance.operit.ui.main.navigation.AppRouteDiscoveryGateway
import com.ai.assistance.operit.ui.main.navigation.screenKeysAliveOnStack
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.navigation.NavigationSurface
import com.ai.assistance.operit.ui.main.navigation.RouteEntrySource
import com.ai.assistance.operit.ui.main.navigation.LocalRouteBackGuardRegistry
import com.ai.assistance.operit.ui.main.navigation.RouteBackGuardRegistry
import com.ai.assistance.operit.ui.theme.LocalThemePreferenceSnapshot
import com.ai.assistance.operit.util.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger

// 为TopAppBar的actions提供CompositionLocal
// 它允许子组件（如AIChatScreen）向上提供它们的action Composable
val LocalTopBarActions = compositionLocalOf<(@Composable (RowScope.() -> Unit)) -> Unit> { {} }

class TopBarTitleContent(val content: @Composable () -> Unit)

val LocalTopBarTitleContent = compositionLocalOf<(TopBarTitleContent?) -> Unit> { {} }
val LocalAppNavigationModel = compositionLocalOf<AppNavigationModel?> { null }

enum class NavigationTransitionSource {
    DEFAULT,
    DRAWER
}

private const val TAG = "OperitApp"

private data class NetworkStateSnapshot(
    val isAvailable: Boolean,
    val type: String
)

@Composable
fun OperitApp(
    initialNavItem: NavItem = NavItem.AiChat,
    toolHandler: AIToolHandler? = null,
    shortcutNavRequest: NavItem? = null,
    shortcutNavRequestId: Long = 0L,
    routeNavRequest: String? = null,
    routeNavArgs: Map<String, Any?> = emptyMap(),
    routeNavRequestId: Long = 0L,
    onShortcutNavHandled: (Long) -> Unit = {},
    onCurrentNavItemChanged: (NavItem) -> Unit = {},
    onRouteNavHandled: (Long) -> Unit = {}
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val packageManager = remember {
        PackageManager.getInstance(context, AIToolHandler.getInstance(context))
    }
    val remoteAnnouncementRepository = remember { RemoteAnnouncementRepository() }
    val remoteAnnouncementPreferences = remember { RemoteAnnouncementPreferences(context) }
    val pluginDenylistRepository = remember { PluginDenylistRepository(appContext) }
    var navigationRevision by remember { mutableStateOf(0) }
    val configuration = LocalConfiguration.current
    val dynamicSurfaces by PluginPlatformKernel.dynamicNavigationRegistry.surfaces.collectAsState()
    val pluginHomeTiles by PluginPlatformKernel.uiRegistry.homeTiles.collectAsState()
    val pluginScreens by PluginPlatformKernel.uiRegistry.activeScreens.collectAsState()
    val pagePresentationRequests by PluginPlatformKernel.pagePresentationRegistry.requests.collectAsState()
    val systemToolboxEntries by PluginPlatformKernel.systemUiRegistry.toolboxEntries.collectAsState()
    val canCreateDynamicPage = systemToolboxEntries.any { it.id == "plugin_center.main" }
    val navigationModel = remember(
        context, configuration, navigationRevision, dynamicSurfaces, pluginHomeTiles, pluginScreens, systemToolboxEntries
    ) { AppRouteCatalog.build(context) }

    val routerState = remember {
        AppRouterState(AppRouteCatalog.initialEntry(initialNavItem))
    }
    val routeBackGuardRegistry = remember { RouteBackGuardRegistry() }
    val currentRouteEntry = routerState.currentEntry
    val currentScreen = AppRouteCatalog.resolveScreen(navigationModel, currentRouteEntry) ?: Screen.AiChat
    val selectedItem = currentScreen.navItem
    val currentPluginScreenId = (currentScreen as? Screen.PluginDeclarativePage)?.screenId
    val currentPluginOwnerId = currentPluginScreenId?.let { screenId ->
        pluginScreens.firstOrNull { it.id == screenId }?.ownerPluginId
    }
    val currentPagePresentation = currentPluginScreenId?.let { screenId ->
        pagePresentationRequests[screenId]?.takeIf { it.ownerPluginId == currentPluginOwnerId }
    }
    val currentPagePresentationMode =
        currentPagePresentation?.mode ?: PluginPagePresentationMode.NORMAL
    val isImmersivePluginPage = currentPagePresentationMode != PluginPagePresentationMode.NORMAL
    val themeSnapshot = LocalThemePreferenceSnapshot.current
    PluginPagePresentationWindowEffect(
        mode = currentPagePresentationMode,
        restoreStatusBarHidden = themeSnapshot.statusBarHidden
    )
    // Presentation is a lease owned by the plugin screen that is visible right now.
    // Changing routes releases the old lease and prevents background plugins from pre-arming fullscreen.
    DisposableEffect(currentPluginScreenId) {
        PluginPlatformKernel.pagePresentationRegistry.setActiveScreen(currentPluginScreenId)
        onDispose {
            PluginPlatformKernel.pagePresentationRegistry.setActiveScreen(null)
        }
    }
    // 当前导航栈中仍存活的路由 screenKey（路由级 ViewModelStore 清理依据：
    // AppContent 在转场完成时只保留这些键的 owner）
    val aliveScreenKeys: Set<String> =
        screenKeysAliveOnStack(routerState.backStack) { entry ->
            AppRouteCatalog.resolveScreen(navigationModel, entry)
        }
    val pluginSidebarEntries =
        remember(navigationModel) {
            navigationModel.navigationEntries.filter {
                it.surface == NavigationSurface.MAIN_SIDEBAR_PLUGINS ||
                    it.surface == NavigationSurface.MAIN_SIDEBAR_DYNAMIC
            }
        }

    // 跟踪是否是返回操作
    var isNavigatingBack by remember { mutableStateOf(false) }
    var navigationTransitionSource by remember {
        mutableStateOf(NavigationTransitionSource.DEFAULT)
    }

    // 用于存储由子屏幕提供的TopAppBar Actions
    var topBarActions by remember { mutableStateOf<@Composable RowScope.() -> Unit>({}) }
    var topBarTitleContent by remember { mutableStateOf<TopBarTitleContent?>(null) }
    var lastHandledShortcutRequestId by remember { mutableStateOf(0L) }
    var lastHandledRouteRequestId by remember { mutableStateOf(0L) }
    var isRouteTransitionInProgress by remember { mutableStateOf(false) }
    var queuedRouteTransition by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requestRouteTransition(
        queueIfBusy: Boolean = true,
        onAllowed: () -> Unit,
    ) {
        scope.launch {
            if (isRouteTransitionInProgress) {
                if (queueIfBusy) {
                    queuedRouteTransition = onAllowed
                }
                return@launch
            }
            isRouteTransitionInProgress = true
            val routeInstanceId = routerState.currentEntry.instanceId
            try {
                val canLeave = routeBackGuardRegistry.canLeaveRoute(routeInstanceId)
                if (canLeave && routerState.currentEntry.instanceId == routeInstanceId) {
                    onAllowed()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Navigation handling failed", e)
            } finally {
                isRouteTransitionInProgress = false
                val queuedTransition = queuedRouteTransition
                queuedRouteTransition = null
                if (queuedTransition != null) {
                    requestRouteTransition(onAllowed = queuedTransition)
                }
            }
        }
    }

    LaunchedEffect(selectedItem) {
        selectedItem?.let { navItem ->
            onCurrentNavItemChanged(navItem)
        }
    }

    LaunchedEffect(shortcutNavRequestId, shortcutNavRequest) {
        val requestNavItem = shortcutNavRequest
        if (requestNavItem == null || shortcutNavRequestId == 0L) {
            return@LaunchedEffect
        }
        if (shortcutNavRequestId == lastHandledShortcutRequestId) {
            return@LaunchedEffect
        }

        val targetEntry = AppRouteCatalog.initialEntry(requestNavItem)
        lastHandledShortcutRequestId = shortcutNavRequestId
        onShortcutNavHandled(shortcutNavRequestId)
        requestRouteTransition {
            isNavigatingBack = false
            navigationTransitionSource = NavigationTransitionSource.DEFAULT
            routerState.resetTo(targetEntry)
        }
    }

    LaunchedEffect(routeNavRequestId, routeNavRequest, routeNavArgs, navigationModel) {
        val requestRouteId = routeNavRequest?.trim().orEmpty()
        if (requestRouteId.isBlank() || routeNavRequestId == 0L) {
            return@LaunchedEffect
        }
        if (routeNavRequestId == lastHandledRouteRequestId) {
            return@LaunchedEffect
        }
        if (navigationModel.routesById[requestRouteId] == null) {
            AppLogger.w(TAG, "Ignored pending route navigation for unknown routeId=$requestRouteId")
            lastHandledRouteRequestId = routeNavRequestId
            onRouteNavHandled(routeNavRequestId)
            return@LaunchedEffect
        }
        lastHandledRouteRequestId = routeNavRequestId
        onRouteNavHandled(routeNavRequestId)
        requestRouteTransition {
            isNavigatingBack = false
            navigationTransitionSource = NavigationTransitionSource.DEFAULT
            routerState.resetTo(
                com.ai.assistance.operit.ui.main.navigation.RouteEntry(
                    routeId = requestRouteId,
                    args = routeNavArgs,
                    source = RouteEntrySource.DEFAULT
                )
            )
        }
    }

    // 当currentScreen改变时，检查是否需要清空TopBarActions
    // 这是为了解决从有action的屏幕导航到无action的屏幕时，action残留的问题
    LaunchedEffect(currentScreen) {
        if (currentScreen !is Screen.AiChat && currentScreen !is Screen.TokenConfig) {
            topBarActions = {}
        }
        topBarTitleContent = null
    }

    // Navigation functions
    fun navigateTo(newScreen: Screen, fromDrawer: Boolean = false) {
        val nextEntry =
            AppRouteCatalog.toEntry(
                screen = newScreen,
                source =
                    if (fromDrawer) RouteEntrySource.DRAWER
                    else RouteEntrySource.DEFAULT
            )
        if (currentRouteEntry.routeId == nextEntry.routeId && currentRouteEntry.args == nextEntry.args) {
            return
        }
        requestRouteTransition {
            isNavigatingBack = false
            navigationTransitionSource =
                if (fromDrawer) NavigationTransitionSource.DRAWER
                else NavigationTransitionSource.DEFAULT
            if (fromDrawer) {
                routerState.resetTo(nextEntry)
            } else {
                routerState.navigate(
                    routeId = nextEntry.routeId,
                    args = nextEntry.args,
                    source = nextEntry.source,
                    routeSpec = navigationModel.routesById[nextEntry.routeId]
                )
            }
        }
    }

    fun performGoBack() {
        if (!routerState.canPop) return
        isNavigatingBack = true
        navigationTransitionSource = NavigationTransitionSource.DEFAULT
        routerState.pop()
    }

    fun requestGoBack() {
        requestRouteTransition(queueIfBusy = false, onAllowed = ::performGoBack)
    }

    fun navigateToNavigationEntry(entry: NavigationEntrySpec) {
        val action = entry.action
        if (action != null) {
            val ownerPackageName = entry.ownerPackageName ?: return
            scope.launch(Dispatchers.IO) {
                packageManager.runToolPkgNavigationEntryAction(
                    containerPackageName = ownerPackageName,
                    entryId = entry.entryId,
                    functionName = action.functionName,
                    inlineFunctionSource = action.functionSource,
                    eventPayload =
                        mapOf(
                            "entryId" to entry.entryId,
                            "routeId" to entry.routeId,
                            "surface" to entry.surface.name.lowercase(),
                            "title" to entry.title,
                            "description" to entry.description
                        )
                ).onFailure { error ->
                    AppLogger.e(
                        TAG,
                        "ToolPkg navigation action failed: entryId=${entry.entryId}, package=$ownerPackageName",
                        error
                    )
                }
            }
            return
        }
        if (currentRouteEntry.routeId == entry.routeId && currentRouteEntry.args == entry.routeArgs) {
            return
        }
        requestRouteTransition {
            isNavigatingBack = false
            navigationTransitionSource = NavigationTransitionSource.DRAWER
            routerState.resetTo(
                com.ai.assistance.operit.ui.main.navigation.RouteEntry(
                    routeId = entry.routeId,
                    args = entry.routeArgs,
                    source = RouteEntrySource.DRAWER
                )
            )
        }
    }

    // Function to navigate to TokenConfig, treated as sub-navigation.
    fun navigateToTokenConfig() {
        navigateTo(Screen.TokenConfig)
    }

    // Root-level destinations leave Android Back to MainActivity, which owns double-back-to-exit.
    BackHandler(
        enabled = routerState.canPop && currentScreen !is Screen.AiChat && !isImmersivePluginPage,
        onBack = { requestGoBack() }
    )
    BackHandler(enabled = isImmersivePluginPage && currentPagePresentation != null) {
        currentPagePresentation?.let { presentation ->
            PluginPlatformKernel.pagePresentationRegistry.set(
                ownerPluginId = presentation.ownerPluginId,
                screenId = presentation.screenId,
                mode = PluginPagePresentationMode.NORMAL
            )
        }
    }

    val canGoBack = routerState.canPop

    var isLoading by remember { mutableStateOf(false) }

    // Tablet mode sidebar state
    var isTabletSidebarExpanded by remember { mutableStateOf(false) }
    var tabletSidebarWidth by remember { mutableStateOf(280.dp) } // 侧边栏默认宽度
    val collapsedTabletSidebarWidth = 64.dp // 收起时的宽度

    // Device screen size calculation
    val screenWidthDp = configuration.screenWidthDp

    // Determine if using tablet layout based on screen width
    // Using Material Design 3 guidelines:
    // - Less than 600dp: phone
    // - 600dp and above: tablet
    val useTabletLayout = screenWidthDp >= 600

    var remoteAnnouncement by remember { mutableStateOf<RemoteAnnouncementDisplay?>(null) }

    fun dismissRemoteAnnouncement() {
        remoteAnnouncement?.let { announcement ->
            remoteAnnouncementPreferences.setAcknowledgedVersion(announcement.version)
        }
        remoteAnnouncement = null
    }

    val navItems = listOf(
        NavItem.AiChat,
        NavItem.AssistantConfig,
        NavItem.Packages,
        NavItem.MemoryBase,
        NavItem.Toolbox,
        NavItem.ShizukuCommands,
        NavItem.Workflow,
        NavItem.Settings
    )

    // Network state monitoring
    var isNetworkAvailable by remember { mutableStateOf(false) }
    var networkType by remember { mutableStateOf(context.getString(R.string.not_connected)) }

    // Periodically check network status
    LaunchedEffect(Unit) {
        while (true) {
            val snapshot =
                withContext(Dispatchers.IO) {
                    NetworkStateSnapshot(
                        isAvailable = NetworkUtils.isNetworkAvailable(appContext),
                        type = NetworkUtils.getNetworkType(appContext)
                    )
                }
            isNetworkAvailable = snapshot.isAvailable
            networkType = snapshot.type
            delay(10000) // Check every 10 seconds
        }
    }

    LaunchedEffect(isNetworkAvailable) {
        if (!isNetworkAvailable) return@LaunchedEffect

        launch {
            pluginDenylistRepository.refreshFromRemote()
        }

        if (remoteAnnouncement != null) return@LaunchedEffect

        val announcement = remoteAnnouncementRepository.fetchDisplayableAnnouncement()
        if (announcement != null && remoteAnnouncementPreferences.shouldShow(announcement.version)) {
            remoteAnnouncement = announcement
        }
    }

    // Get FPS counter display setting
    val displayPreferencesManager = remember { DisplayPreferencesManager.getInstance(context) }
    val showFpsCounter = displayPreferencesManager.showFpsCounter.collectAsState(initial = false).value
    val enableNavigationAnimation =
        displayPreferencesManager.enableNavigationAnimation
            .collectAsState(initial = true)
            .value

    // Create an instance of MCPRepository
    val mcpRepository = remember { MCPRepository(context) }

    // Initialize MCP plugin status
    LaunchedEffect(Unit) {
        launch {
            // First scan local installed plugins
            mcpRepository.syncInstalledStatus()
        }
    }

    // Calculate drawer width for phone mode
    val drawerWidth = (screenWidthDp * 0.75).dp // Drawer width is 3/4 of screen width

    // Main app container
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        DisposableEffect(packageManager) {
            val listener = PackageManager.ToolPkgRuntimeChangeListener { _ ->
                navigationRevision += 1
            }
            packageManager.addToolPkgRuntimeChangeListener(listener)
            onDispose {
                packageManager.removeToolPkgRuntimeChangeListener(listener)
            }
        }
        DisposableEffect(routerState, navigationModel) {
            AppRouterGateway.install(
                handler = { routeId, args, source ->
                    val routeSpec = navigationModel.routesById[routeId] ?: return@install
                    val currentEntry = routerState.currentEntry
                    if (
                        routeSpec.reuseOnTop &&
                            currentEntry.routeId == routeId &&
                            currentEntry.args == args
                    ) {
                        return@install
                    }
                    requestRouteTransition {
                        isNavigatingBack = false
                        navigationTransitionSource =
                            if (source == RouteEntrySource.DRAWER) NavigationTransitionSource.DRAWER
                            else NavigationTransitionSource.DEFAULT
                        routerState.navigate(
                            routeId = routeId,
                            args = args,
                            source = source,
                            routeSpec = routeSpec,
                        )
                    }
                },
                reset = { routeId, args, source ->
                    navigationModel.routesById[routeId] ?: return@install
                    requestRouteTransition {
                        isNavigatingBack = false
                        navigationTransitionSource =
                            if (source == RouteEntrySource.DRAWER) NavigationTransitionSource.DRAWER
                            else NavigationTransitionSource.DEFAULT
                        routerState.resetTo(
                            com.ai.assistance.operit.ui.main.navigation.RouteEntry(
                                routeId = routeId,
                                args = args,
                                source = source,
                            )
                        )
                    }
                }
            )
            AppRouteDiscoveryGateway.install {
                navigationModel.routes
            }
            onDispose {
                AppRouterGateway.clear()
                AppRouteDiscoveryGateway.clear()
            }
        }
        CompositionLocalProvider(
            LocalAppNavigationModel provides navigationModel,
            LocalRouteBackGuardRegistry provides routeBackGuardRegistry,
            LocalTopBarActions provides { actions: @Composable RowScope.() -> Unit ->
                topBarActions = actions
            },
            LocalTopBarTitleContent provides { titleContent ->
                topBarTitleContent = titleContent
            }
        ) {
            if (isImmersivePluginPage) {
                AppContent(
                    currentRouteEntry = currentRouteEntry,
                    currentScreen = currentScreen,
                    selectedItem = selectedItem,
                    useTabletLayout = false,
                    isTabletSidebarExpanded = false,
                    isLoading = isLoading,
                    navController = navController,
                    scope = scope,
                    drawerState = drawerState,
                    showFpsCounter = showFpsCounter,
                    enableNavigationAnimation = enableNavigationAnimation,
                    navigationTransitionSource = navigationTransitionSource,
                    onScreenChange = { screen -> navigateTo(screen) },
                    onToggleSidebar = {},
                    navigateToTokenConfig = ::navigateToTokenConfig,
                    canGoBack = canGoBack,
                    onGoBack = ::requestGoBack,
                    isNavigatingBack = isNavigatingBack,
                    actions = { topBarActions() },
                    titleContent = topBarTitleContent,
                    hideHostChrome = true,
                    aliveScreenKeys = aliveScreenKeys
                )
            } else if (useTabletLayout) {
                // Tablet layout
                TabletLayout(
                    currentRouteEntry = currentRouteEntry,
                    currentScreen = currentScreen,
                    selectedItem = selectedItem,
                    isTabletSidebarExpanded = isTabletSidebarExpanded,
                    isLoading = isLoading,
                    navItems = navItems,
                    pluginSidebarEntries = pluginSidebarEntries,
                    selectedRouteId = currentRouteEntry.routeId,
                    isNetworkAvailable = isNetworkAvailable,
                    networkType = networkType,
                    navController = navController,
                    scope = scope,
                    drawerState = drawerState,
                    showFpsCounter = showFpsCounter,
                    enableNavigationAnimation = enableNavigationAnimation,
                    navigationTransitionSource = navigationTransitionSource,
                    tabletSidebarWidth = tabletSidebarWidth,
                    collapsedTabletSidebarWidth = collapsedTabletSidebarWidth,
                    onScreenChange = { screen -> navigateTo(screen) },
                    onDrawerItemSelected = { screen ->
                        navigateTo(screen, fromDrawer = true)
                    },
                    onNavigationEntrySelected = ::navigateToNavigationEntry,
                    canCreateDynamicPage = canCreateDynamicPage,
                    onCreateDynamicPage = {
                        if (canCreateDynamicPage) PluginPlatformKernel.dynamicNavigationRegistry.create()
                    },
                    onToggleSidebar = {
                        isTabletSidebarExpanded = !isTabletSidebarExpanded
                    },
                    navigateToTokenConfig = ::navigateToTokenConfig,
                    canGoBack = canGoBack,
                    onGoBack = ::requestGoBack,
                    isNavigatingBack = isNavigatingBack,
                    topBarActions = { topBarActions() },
                    topBarTitleContent = topBarTitleContent,
                    aliveScreenKeys = aliveScreenKeys
                )
            } else {
                // Phone layout
                PhoneLayout(
                    currentRouteEntry = currentRouteEntry,
                    currentScreen = currentScreen,
                    selectedItem = selectedItem,
                    isLoading = isLoading,
                    navItems = navItems,
                    pluginSidebarEntries = pluginSidebarEntries,
                    selectedRouteId = currentRouteEntry.routeId,
                    isNetworkAvailable = isNetworkAvailable,
                    networkType = networkType,
                    drawerWidth = drawerWidth,
                    navController = navController,
                    scope = scope,
                    drawerState = drawerState,
                    showFpsCounter = showFpsCounter,
                    enableNavigationAnimation = enableNavigationAnimation,
                    navigationTransitionSource = navigationTransitionSource,
                    onScreenChange = { screen -> navigateTo(screen) },
                    onDrawerItemSelected = { screen ->
                        navigateTo(screen, fromDrawer = true)
                    },
                    onNavigationEntrySelected = ::navigateToNavigationEntry,
                    canCreateDynamicPage = canCreateDynamicPage,
                    onCreateDynamicPage = {
                        if (canCreateDynamicPage) PluginPlatformKernel.dynamicNavigationRegistry.create()
                    },
                    navigateToTokenConfig = ::navigateToTokenConfig,
                    canGoBack = canGoBack,
                    onGoBack = ::requestGoBack,
                    isNavigatingBack = isNavigatingBack,
                    topBarActions = { topBarActions() },
                    topBarTitleContent = topBarTitleContent,
                    aliveScreenKeys = aliveScreenKeys
                )
            }
        }

        if (!isImmersivePluginPage) remoteAnnouncement?.let { announcement ->
            RemoteAnnouncementDialog(
                title = announcement.title,
                body = announcement.body,
                acknowledgeText = announcement.acknowledgeText,
                countdownSeconds = announcement.countdownSec,
                onAcknowledge = { dismissRemoteAnnouncement() }
            )
        }
    }
}


@Composable
private fun PluginPagePresentationWindowEffect(
    mode: PluginPagePresentationMode,
    restoreStatusBarHidden: Boolean
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }

    if (activity != null && mode != PluginPagePresentationMode.NORMAL) {
        SideEffect {
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView)?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(activity, mode, restoreStatusBarHidden) {
        if (activity == null || mode == PluginPagePresentationMode.NORMAL) {
            onDispose { }
        } else {
            val originalOrientation = activity.requestedOrientation
            val targetOrientation = when (mode) {
                PluginPagePresentationMode.FULLSCREEN_PORTRAIT ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                PluginPagePresentationMode.FULLSCREEN_LANDSCAPE ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                PluginPagePresentationMode.NORMAL -> originalOrientation
            }
            activity.requestedOrientation = targetOrientation

            onDispose {
                if (activity.requestedOrientation == targetOrientation) {
                    activity.requestedOrientation = originalOrientation
                }
                val window = activity.window
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, window.decorView)?.let { controller ->
                    controller.show(WindowInsetsCompat.Type.navigationBars())
                    if (restoreStatusBarHidden) {
                        controller.hide(WindowInsetsCompat.Type.statusBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.statusBars())
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
