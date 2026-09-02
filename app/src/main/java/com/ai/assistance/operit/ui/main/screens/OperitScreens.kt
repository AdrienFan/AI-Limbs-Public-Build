package com.ai.assistance.operit.ui.main.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.assistant.screens.AssistantConfigScreen
import com.ai.assistance.operit.ui.features.chat.screens.AIChatScreen
import com.ai.assistance.operit.ui.features.demo.screens.ShizukuDemoScreen
import com.ai.assistance.operit.ui.features.memory.screens.MemoryScreen
import com.ai.assistance.operit.ui.features.packages.screens.MarketHomeTab
import com.ai.assistance.operit.ui.features.packages.screens.PackageManagerScreen
import com.ai.assistance.operit.ui.features.packages.screens.ArtifactPublishScreen
import com.ai.assistance.operit.ui.features.packages.screens.RepoMarketPublishScreen
import com.ai.assistance.operit.ui.features.packages.screens.UnifiedMarketManageScreen
import com.ai.assistance.operit.ui.features.packages.screens.UnifiedMarketCategoryScreen
import com.ai.assistance.operit.ui.features.packages.screens.UnifiedMarketAuthorScreen
import com.ai.assistance.operit.ui.features.packages.screens.UnifiedMarketDetailEntryScreen
import com.ai.assistance.operit.ui.features.packages.screens.UnifiedMarketNotificationsScreen
import com.ai.assistance.operit.ui.features.packages.screens.UnifiedMarketScreen
import com.ai.assistance.operit.ui.features.packages.screens.marketCategoryLabel
import com.ai.assistance.operit.ui.features.packages.screens.toArtifactPublishClusterContext
import com.ai.assistance.operit.ui.features.packages.market.ArtifactPublishClusterContext
import com.ai.assistance.operit.ui.features.packages.market.MarketStatsType
import com.ai.assistance.operit.ui.features.packages.market.PluginCreationIntent
import com.ai.assistance.operit.ui.features.settings.screens.ChatBackupSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ChatHistorySettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ContextSummarySettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ExternalHttpChatSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.FunctionalConfigScreen
import com.ai.assistance.operit.ui.features.settings.screens.GlobalDisplaySettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.GitHubAccountScreen
import com.ai.assistance.operit.ui.features.settings.screens.LanguageSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.LayoutAdjustmentSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ModelConfigScreen
import com.ai.assistance.operit.ui.features.settings.screens.ModelConfigEntryMode
import com.ai.assistance.operit.ui.features.settings.screens.ModelPromptsSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.TagMarketScreen
import com.ai.assistance.operit.ui.features.settings.screens.SettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.SpeechServicesSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ThemeSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ToolPermissionSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.MnnModelDownloadScreen
import com.ai.assistance.operit.ui.features.settings.screens.UserPreferencesSettingsScreen
import com.ai.assistance.operit.ui.features.tokenstats.TokenUsageStatisticsScreen
import com.ai.assistance.operit.ui.features.token.TokenConfigWebViewScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.ToolboxScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.DynamicNavigationScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.PluginDeclarativeScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.pluginbootstrap.PluginBootstrapScreen
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.plugins.system.SystemUiNavigatorV1
import com.ai.assistance.operit.ui.common.composedsl.ToolPkgComposeDslToolScreen
import com.ai.assistance.operit.ui.features.workflow.screens.WorkflowListScreen
import com.ai.assistance.operit.ui.features.workflow.screens.WorkflowDetailScreen
import com.ai.assistance.operit.ui.main.PendingChatDraftHandler
import com.ai.assistance.operit.ui.main.navigation.AppRouterGateway

// 路由配置类
typealias ScreenNavigationHandler = (Screen) -> Unit

// 重构的Screen类，添加了路由相关属性和内容渲染函数
sealed class Screen(
        // 对应的导航项，仅用于侧边栏高亮归属。
        // 它不再承担“这个 nav item 默认打开哪个页面”的职责。
        open val navItem: NavItem? = null,
        // 屏幕标题资源ID
        open val titleRes: Int? = null,
        // 是否参与 AppContent 的跨页淡入淡出。
        // 某些包含实时渲染视图的页面在转场中保留上一页会产生明显残影。
        open val participatesInCrossfadeTransition: Boolean = true,
        open val keepAlive: Boolean = false,
        /**
         * 是否使用路由级 ViewModelStore（AppContent 按 screenKey 通过
         * [com.ai.assistance.operit.ui.main.navigation.ScreenRouteViewModelStoreOwnerManager]
         * 管理）：配置变化保留实例，路由出栈/替换/清栈时 store.clear() 触发
         * onCleared（viewModelScope 取消）。
         * 默认 false 保持 Activity 级语义，防止影响其他页面。
         */
        open val usesRouteViewModelStore: Boolean = false
) {
    open fun stableScreenKey(): String? = null

    /**
     * AppContent 使用的屏幕键：keepAlive 屏幕用 [stableScreenKey]（同路由
     * 实例复用），否则用路由实例 id（每次进入独立）。
     */
    fun screenKey(routeInstanceId: String): String =
        if (keepAlive) stableScreenKey() ?: routeInstanceId else routeInstanceId

    // 屏幕内容渲染函数
    @Composable
    open fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
    ) {
        // 子类实现具体内容
    }

    // Main screens (primary)
    data object AiChat : Screen(navItem = NavItem.AiChat) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            AIChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = null,
                    isFloatingMode = false,
                    hasBackgroundImage = hasBackgroundImage,
                    onNavigateToTokenConfig = { navigateTo(TokenConfig) },
                    onNavigateToSettings = { navigateTo(Settings) },
                    onNavigateToMemoryBase = { navigateTo(MemoryBase) },
                    onNavigateToModelConfig = { navigateTo(ModelConfig) },
                    onNavigateToOnboardingModelConfig = { navigateTo(ModelConfigOnboarding) },
                    onNavigateToModelPrompts = { navigateTo(ModelPromptsSettings) },
                    onNavigateToPackageManager = { navigateTo(Packages) },
                    onLoading = onLoading,
                    onError = onError,
                    onGestureConsumed = onGestureConsumed
            )
        }
    }

    data object MemoryBase : Screen(navItem = NavItem.MemoryBase, titleRes = R.string.screen_title_memory_base) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            MemoryScreen()
        }
    }

    data object Packages : Screen(navItem = NavItem.Packages) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            val context = LocalContext.current
            PackageManagerScreen(
                onNavigateToMCPMarket = { navigateTo(Market(MarketHomeTab.ALL)) },
                onNavigateToSkillMarket = { navigateTo(Market(MarketHomeTab.ALL)) },
                onNavigateToArtifactMarket = { navigateTo(Market(MarketHomeTab.ALL)) },
                onStartPluginCreation = { intent ->
                    PendingChatDraftHandler.setPendingDraft(intent.toPrompt(context))
                    navigateTo(AiChat)
                },
                onOpenToolPkgPluginConfig = { containerPackageName, uiModuleId, title, keepAlive ->
                    navigateTo(
                        ToolPkgPluginConfig(
                            containerPackageName = containerPackageName,
                            uiModuleId = uiModuleId,
                            title = title,
                            keepAlive = keepAlive
                        )
                    )
                }
            )
        }
    }

    data class Market(val initialTab: MarketHomeTab = MarketHomeTab.ALL) :
            Screen(navItem = NavItem.Packages, titleRes = R.string.screen_title_market) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            UnifiedMarketScreen(
                initialTab = initialTab,
                onNavigateToArtifactPublish = { navigateTo(ArtifactPublish) },
                onNavigateToRepoPublish = { type -> navigateTo(RepoPublish(type)) },
                onNavigateToMarketManage = { navigateTo(MarketManage) },
                onNavigateToDetail = { issue ->
                    navigateTo(MarketEntryDetail(issue))
                },
                onNavigateToCategory = { categoryId ->
                    navigateTo(MarketCategory(categoryId))
                },
                onNavigateToNotifications = {
                    navigateTo(MarketNotifications)
                }
            )
        }
    }

    data class MarketCategory(val categoryId: String) :
            Screen(navItem = NavItem.Packages) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            UnifiedMarketCategoryScreen(
                categoryId = categoryId,
                onNavigateToArtifactDetail = { issue ->
                    navigateTo(MarketEntryDetail(issue))
                },
                onNavigateToSkillDetail = { issue ->
                    navigateTo(MarketEntryDetail(issue))
                },
                onNavigateToMcpDetail = { issue ->
                    navigateTo(MarketEntryDetail(issue))
                }
            )
        }

        @Composable
        override fun getTitle(): String = marketCategoryLabel(categoryId)
    }

    data object MarketNotifications :
            Screen(navItem = NavItem.Packages, titleRes = R.string.market_notifications_title) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            UnifiedMarketNotificationsScreen()
        }
    }

    data class MarketEntryDetail(
        val entry: com.ai.assistance.operit.data.api.MarketV2Entry,
        val fromManage: Boolean = false
    ) : Screen(navItem = NavItem.Packages) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            UnifiedMarketDetailEntryScreen(
                initialEntry = entry,
                fromManage = fromManage,
                onNavigateBack = onGoBack,
                onPublishNewVersion = { target ->
                    when (target.type.lowercase()) {
                        "script", "package" -> navigateTo(ArtifactContinuePublish(target.toArtifactPublishClusterContext()))
                        "skill" -> navigateTo(RepoPublishVersion(MarketStatsType.SKILL, target))
                        "mcp" -> navigateTo(RepoPublishVersion(MarketStatsType.MCP, target))
                    }
                },
                onNavigateToAuthor = { authorId, authorLogin, authorAvatarUrl ->
                    navigateTo(
                        MarketAuthor(
                            authorId = authorId,
                            authorLogin = authorLogin,
                            authorAvatarUrl = authorAvatarUrl
                        )
                    )
                }
            )
        }
    }

    data class MarketAuthor(
        val authorId: String,
        val authorLogin: String,
        val authorAvatarUrl: String
    ) : Screen(navItem = NavItem.Packages) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            UnifiedMarketAuthorScreen(
                authorId = authorId,
                authorLogin = authorLogin,
                authorAvatarUrl = authorAvatarUrl,
                onNavigateToDetail = { issue ->
                    navigateTo(MarketEntryDetail(issue))
                }
            )
        }

        @Composable
        override fun getTitle(): String =
            authorLogin.ifBlank { authorId.removePrefix("gh_") }
    }

    data object MarketManage : Screen(navItem = NavItem.Packages, titleRes = R.string.market_section_manage) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            UnifiedMarketManageScreen(
                onNavigateToEditArtifact = { issue ->
                    navigateTo(ArtifactEdit(issue))
                },
                onNavigateToEditRepo = { type, issue ->
                    navigateTo(RepoEdit(type, issue))
                },
                onNavigateToPublishArtifactVersion = { issue, canEditEntry ->
                    navigateTo(ArtifactContinuePublish(issue.toArtifactPublishClusterContext(canEditEntry)))
                },
                onNavigateToPublishRepoVersion = { type, issue, canEditEntry ->
                    navigateTo(RepoPublishVersion(type, issue, canEditEntry))
                },
                onNavigateToPublishArtifact = { navigateTo(ArtifactPublish) },
                onNavigateToPublishRepo = { type -> navigateTo(RepoPublish(type)) },
                onNavigateToDetail = { issue ->
                    navigateTo(MarketEntryDetail(issue, fromManage = true))
                }
            )
        }
    }

    data object ArtifactPublish : Screen(navItem = NavItem.Packages, titleRes = R.string.screen_title_artifact_publish) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ArtifactPublishScreen(onNavigateBack = onGoBack)
        }
    }

    data class ArtifactContinuePublish(
        val publishContext: ArtifactPublishClusterContext
    ) : Screen(navItem = NavItem.Packages, titleRes = R.string.screen_title_artifact_publish) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ArtifactPublishScreen(
                onNavigateBack = onGoBack,
                publishContext = publishContext
            )
        }
    }

    data class ArtifactEdit(val editingEntry: com.ai.assistance.operit.data.api.MarketV2Entry) :
            Screen(navItem = NavItem.Packages, titleRes = R.string.screen_title_artifact_publish) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ArtifactPublishScreen(
                onNavigateBack = onGoBack,
                editingEntry = editingEntry
            )
        }
    }

    data class RepoPublish(val type: MarketStatsType) : Screen(navItem = NavItem.Packages) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            RepoMarketPublishScreen(
                type = type,
                onNavigateBack = onGoBack
            )
        }

        @Composable
        override fun getTitle(): String {
            return stringResource(
                if (type == MarketStatsType.SKILL) {
                    R.string.screen_title_skill_publish
                } else {
                    R.string.screen_title_mcp_publish
                }
            )
        }
    }

    data class RepoEdit(
        val type: MarketStatsType,
        val editingEntry: com.ai.assistance.operit.data.api.MarketV2Entry
    ) : Screen(navItem = NavItem.Packages) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            RepoMarketPublishScreen(
                type = type,
                onNavigateBack = onGoBack,
                editingEntry = editingEntry
            )
        }

        @Composable
        override fun getTitle(): String {
            return stringResource(
                if (type == MarketStatsType.SKILL) {
                    R.string.screen_title_skill_publish
                } else {
                    R.string.screen_title_mcp_publish
                }
            )
        }
    }

    data class RepoPublishVersion(
        val type: MarketStatsType,
        val editingEntry: com.ai.assistance.operit.data.api.MarketV2Entry,
        val canEditEntry: Boolean = false
    ) : Screen(navItem = NavItem.Packages) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            RepoMarketPublishScreen(
                type = type,
                onNavigateBack = onGoBack,
                editingEntry = editingEntry,
                publishVersionOnly = true,
                canEditEntry = canEditEntry
            )
        }

        @Composable
        override fun getTitle(): String {
            return stringResource(R.string.market_publish_new_version)
        }
    }

    data object PluginBootstrap : Screen(
        navItem = NavItem.Toolbox,
        titleRes = R.string.plugin_center_bootstrap_title
    ) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            PluginBootstrapScreen(onInstalled = { navigateTo(Toolbox) })
        }
    }

    data class SystemPluginPage(val entryId: String) : Screen(navItem = NavItem.Toolbox) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            val entry = PluginPlatformKernel.systemUiRegistry.entry(entryId)
            if (entry == null) {
                Text("System Plugin 页面已卸载，请返回工具箱")
                return
            }
            entry.page.Content(
                object : SystemUiNavigatorV1 {
                    override fun backToToolbox(message: String?) {
                        AppRouterGateway.resetTo(
                            routeId = ScreenRouteRegistry.routeIdOf(Toolbox),
                            args = emptyMap(),
                            source = com.ai.assistance.operit.ui.main.navigation.RouteEntrySource.DEFAULT
                        )
                    }
                }
            )
        }

        @Composable
        override fun getTitle(): String =
            PluginPlatformKernel.systemUiRegistry.entry(entryId)?.title ?: "System Plugin"
    }

    data class DynamicNavigationPage(val surfaceId: String) : Screen() {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            DynamicNavigationScreen(
                surfaceId = surfaceId,
                onOpenPluginScreen = { screenId -> navigateTo(PluginDeclarativePage(screenId)) }
            )
        }

        @Composable
        override fun getTitle(): String =
            PluginPlatformKernel.dynamicNavigationRegistry.find(surfaceId)?.title ?: "动态页面"
    }

    data class PluginDeclarativePage(val screenId: String) : Screen() {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            PluginDeclarativeScreen(screenId)
        }

        @Composable
        override fun getTitle(): String =
            PluginPlatformKernel.uiRegistry.screen(screenId)?.title ?: "插件页面"
    }

    data object Toolbox : Screen(navItem = NavItem.Toolbox) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ToolboxScreen(
                navController = navController,
                onNavigationEntrySelected = { entry ->
                    val nativeScreen = ScreenRouteRegistry.buildScreen(entry.routeId, entry.routeArgs)
                    if (nativeScreen != null) {
                        navigateTo(nativeScreen)
                    } else {
                        AppRouterGateway.navigate(
                            routeId = entry.routeId,
                            args = entry.routeArgs,
                            source = com.ai.assistance.operit.ui.main.navigation.RouteEntrySource.DEFAULT
                        )
                    }
                }
            )
        }
    }

    data object ShizukuCommands : Screen(navItem = NavItem.ShizukuCommands) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ShizukuDemoScreen(navigateTo = navigateTo)
        }
    }

    data object Settings : Screen(navItem = NavItem.Settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            SettingsScreen(
                    navigateToUserPreferences = { navigateTo(UserPreferencesSettings) },
                    navigateToToolPermissions = { navigateTo(ToolPermission) },
                    navigateToGitHubAccount = { navigateTo(GitHubAccount) },
                    navigateToModelConfig = { navigateTo(ModelConfig) },
                    navigateToThemeSettings = { navigateTo(ThemeSettings) },
                    navigateToGlobalDisplaySettings = { navigateTo(GlobalDisplaySettings) },
                    navigateToModelPrompts = { navigateTo(ModelPromptsSettings) },
                    navigateToFunctionalConfig = { navigateTo(FunctionalConfig) },
                    navigateToChatHistorySettings = { navigateTo(ChatHistorySettings) },
                    navigateToChatBackupSettings = { navigateTo(ChatBackupSettings) },
                    navigateToLanguageSettings = { navigateTo(LanguageSettings) },
                    navigateToSpeechServicesSettings = { navigateTo(SpeechServicesSettings) },
                    navigateToExternalHttpChatSettings = { navigateTo(ExternalHttpChatSettings) },
                    navigateToPersonaCardGeneration = { navigateTo(PersonaCardGeneration) },
                    navigateToWaifuModeSettings = { navigateTo(WaifuModeSettings) },
                    navigateToTokenUsageStatistics = { navigateTo(TokenUsageStatistics) },
                    navigateToContextSummarySettings = { navigateTo(ContextSummarySettings) },
                    navigateToLayoutAdjustmentSettings = { navigateTo(LayoutAdjustmentSettings) }
            )
        }
    }

    data object GitHubAccount : Screen(navItem = NavItem.Settings, titleRes = R.string.github_account) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            GitHubAccountScreen()
        }
    }

    data object Agreement : Screen(navItem = NavItem.Agreement) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            com.ai.assistance.operit.ui.features.agreement.screens.AgreementScreen(
                    onAgreementAccepted = onGoBack
            )
        }
    }

    data object AssistantConfig :
            Screen(
                    navItem = NavItem.AssistantConfig,
                    participatesInCrossfadeTransition = false
            ) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            AssistantConfigScreen()
        }
    }

    data object Workflow : Screen(navItem = NavItem.Workflow) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            WorkflowListScreen(
                onNavigateToDetail = { workflowId ->
                    navigateTo(WorkflowDetail(workflowId))
                }
            )
        }
    }

    data class WorkflowDetail(val workflowId: String) : Screen(navItem = NavItem.Workflow, titleRes = R.string.nav_workflow) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            WorkflowDetailScreen(
                workflowId = workflowId,
                onNavigateBack = onGoBack
            )
        }
    }

    data object TokenConfig : Screen(navItem = NavItem.TokenConfig) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            TokenConfigWebViewScreen(onNavigateBack = onGoBack)
        }
    }

    // Secondary screens - Settings
    data object UserPreferencesSettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.settings_user_preferences) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            UserPreferencesSettingsScreen(
                onNavigateBack = onGoBack,
                onNavigateToMemory = { navigateTo(MemoryBase) }
            )
        }
    }

    data object ToolPermission :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_tool_permissions) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ToolPermissionSettingsScreen(navigateBack = onGoBack)
        }
    }

    data object ModelConfig :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_model_config) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ModelConfigScreen(
                navigateToMnnModelDownload = { navigateTo(MnnModelDownload) }
            )
        }
    }

    data object ModelConfigOnboarding :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_model_config) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ModelConfigScreen(
                navigateToMnnModelDownload = { navigateTo(MnnModelDownload) },
                entryMode = ModelConfigEntryMode.CHAT_ONBOARDING
            )
        }
    }
    // 添加SpeechServicesSettings屏幕定义
    data object SpeechServicesSettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_speech_services_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            SpeechServicesSettingsScreen(onBackPressed = onGoBack)
        }
    }
    
    data object ExternalHttpChatSettings :
        Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_external_http_chat_settings) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            ExternalHttpChatSettingsScreen(onBackPressed = onGoBack)
        }
    }
    
    // MNN模型下载屏幕
    data object MnnModelDownload :
        Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_mnn_model_download) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            MnnModelDownloadScreen(onBackPressed = onGoBack)
        }
    }
    
    // 新增：人设卡生成页面
    data object PersonaCardGeneration :
        Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_persona_card_generation) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            com.ai.assistance.operit.ui.features.settings.screens.PersonaCardGenerationScreen(
                onNavigateToSettings = { navigateTo(Settings) },
                onNavigateToModelConfig = { navigateTo(ModelConfig) },
                onNavigateToModelPrompts = { navigateTo(ModelPromptsSettings) }
            )
        }
    }

    // 新增：Waifu模式设置页面
    data object WaifuModeSettings :
        Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_waifu_mode_settings) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            com.ai.assistance.operit.ui.features.settings.screens.WaifuModeSettingsScreen(
                onNavigateBack = onGoBack,
                onNavigateToCustomEmoji = { navigateTo(CustomEmojiManagement) }
            )
        }
    }
    
    // 自定义表情管理页面
    data object CustomEmojiManagement :
        Screen(navItem = NavItem.Settings, titleRes = R.string.manage_custom_emoji) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit
        ) {
            com.ai.assistance.operit.ui.features.settings.screens.CustomEmojiManagementScreen(
                onNavigateBack = onGoBack
            )
        }
    }
    
    data object TagMarket :
        Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_tag_market) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            TagMarketScreen(onBackPressed = onGoBack)
        }
    }

    data object ModelPromptsSettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_model_prompts_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ModelPromptsSettingsScreen(
                onBackPressed = onGoBack,
                onNavigateToMarket = { navigateTo(TagMarket) },
                onNavigateToPersonaGeneration = { navigateTo(PersonaCardGeneration) },
                onNavigateToChatManagement = { navigateTo(ChatHistorySettings) }
            )
        }
    }

    data object FunctionalConfig :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_functional_config) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            FunctionalConfigScreen(
                    onBackPressed = onGoBack,
                    onNavigateToModelConfig = { navigateTo(ModelConfig) }
            )
        }
    }

    data object ThemeSettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_theme_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ThemeSettingsScreen()
        }
    }

    data object GlobalDisplaySettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_global_display_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            GlobalDisplaySettingsScreen(onBackPressed = onGoBack)
        }
    }

    data object LayoutAdjustmentSettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_layout_adjustment) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            LayoutAdjustmentSettingsScreen(onNavigateBack = onGoBack)
        }
    }

    data object ChatHistorySettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_chat_history_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ChatHistorySettingsScreen()
        }
    }

    data object ChatBackupSettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_chat_backup_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ChatBackupSettingsScreen()
        }
    }

    data object LanguageSettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_language_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            LanguageSettingsScreen(onBackPressed = onGoBack)
        }
    }

    data object TokenUsageStatistics :
            Screen(
                    navItem = NavItem.Settings,
                    titleRes = R.string.settings_token_usage_stats,
                    usesRouteViewModelStore = true
            ) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            TokenUsageStatisticsScreen(
                    onBackPressed = onGoBack,
            )
        }
    }

    data object ContextSummarySettings :
            Screen(navItem = NavItem.Settings, titleRes = R.string.screen_title_context_summary_settings) {
        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ContextSummarySettingsScreen(onBackPressed = onGoBack)
        }
    }

    data class ToolPkgComposeDsl(
        val containerPackageName: String,
        val uiModuleId: String,
        val title: String,
        override val keepAlive: Boolean = false
    ) : Screen() {
        override fun stableScreenKey(): String? =
            "toolpkg_keepalive:$containerPackageName:$uiModuleId"

        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ToolPkgComposeDslToolScreen(
                navController = navController,
                routeInstanceId = "screen:$containerPackageName:$uiModuleId",
                containerPackageName = containerPackageName,
                uiModuleId = uiModuleId,
                fallbackTitle = title
            )
        }

        @Composable
        override fun getTitle(): String = title
    }

    data class ToolPkgPluginConfig(
        val containerPackageName: String,
        val uiModuleId: String,
        val title: String,
        override val keepAlive: Boolean = false
    ) : Screen(navItem = NavItem.Packages) {
        override fun stableScreenKey(): String? =
            "toolpkg_keepalive:$containerPackageName:$uiModuleId"

        @Composable
        override fun Content(
                navController: NavController,
                navigateTo: ScreenNavigationHandler,
                onGoBack: () -> Unit,
                hasBackgroundImage: Boolean,
                onLoading: (Boolean) -> Unit,
                onError: (String) -> Unit,
                onGestureConsumed: (Boolean) -> Unit
        ) {
            ToolPkgComposeDslToolScreen(
                navController = navController,
                routeInstanceId = "legacy:$containerPackageName:$uiModuleId",
                containerPackageName = containerPackageName,
                uiModuleId = uiModuleId,
                fallbackTitle = title
            )
        }

        @Composable
        override fun getTitle(): String = title
    }

    // v0.7: legacy Toolbox feature routes removed. Plugin Center will own extension UI.

    // 获取屏幕标题
    @Composable
    open fun getTitle(): String = titleRes?.let { stringResource(it) } ?: ""
}

// 全局的手势状态持有者，用于在不同组件间共享手势状态
object GestureStateHolder {
    // 聊天界面手势是否被消费的状态
    var isChatScreenGestureConsumed: Boolean = false
}
