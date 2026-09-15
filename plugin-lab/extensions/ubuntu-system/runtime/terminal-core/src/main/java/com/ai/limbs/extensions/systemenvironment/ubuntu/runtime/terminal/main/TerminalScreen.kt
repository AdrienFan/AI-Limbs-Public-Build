package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalEnv
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.SetupScreen
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.TerminalHome
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.SettingsScreen
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.UpdateChecker
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalScreen(
    env: TerminalEnv,
    useLocalImeHandling: Boolean = true,
    checkUpdatesOnEnter: Boolean = true,
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findActivity() }
    val manifestSoftInputMode = remember(hostActivity) { hostActivity?.manifestSoftInputMode() }
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    var startDestination by remember { mutableStateOf<String?>(null) }

    val terminalController = env.terminalController

    // 更新检查器
    val updateChecker = remember { UpdateChecker(context) }

    DisposableEffect(hostActivity, manifestSoftInputMode, useLocalImeHandling) {
        if (useLocalImeHandling) {
            hostActivity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        onDispose {
            if (useLocalImeHandling) {
                val window = hostActivity?.window
                if (window != null && manifestSoftInputMode != null) {
                    window.setSoftInputMode(manifestSoftInputMode)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        startDestination =
            if (
                terminalController.localBusinessSettingsAvailable &&
                    env.forceShowSetup &&
                    env.ubuntuRuntimeState.phase == UbuntuRuntimePhase.RUNNING
            ) {
                TerminalRoutes.SETUP_ROUTE
            } else {
                TerminalRoutes.TERMINAL_HOME_ROUTE
            }

        if (checkUpdatesOnEnter) {
            coroutineScope.launch {
                updateChecker.checkForUpdates(showToast = true)
            }
        }
    }

    // 使用 NavHost 处理所有导航
    NavHost(
        navController = navController,
        startDestination = startDestination ?: TerminalRoutes.TERMINAL_HOME_ROUTE
    ) {

        composable(TerminalRoutes.TERMINAL_HOME_ROUTE) {
            TerminalHome(
                env = env,
                useLocalImeHandling = useLocalImeHandling,
                onNavigateToSettings = {
                    navController.navigate(TerminalRoutes.SETTINGS_ROUTE)
                }
            )
        }

        composable(TerminalRoutes.SETUP_ROUTE) {
            if (terminalController.localBusinessSettingsAvailable) {
                SetupScreen(
                    onBack = {
                        val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                        sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
                        navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
                            popUpTo(TerminalRoutes.SETUP_ROUTE) { inclusive = true }
                        }
                    },
                    onSetup = { commands ->
                        val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                        sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
                        env.onSetup(commands)
                        navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
                            popUpTo(TerminalRoutes.SETUP_ROUTE) { inclusive = true }
                        }
                    }
                )
            } else {
                ResidentBusinessSettingsUnavailable()
            }
        }

        composable(TerminalRoutes.SETTINGS_ROUTE) {
            if (terminalController.localBusinessSettingsAvailable) {
                SettingsScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            } else {
                ResidentBusinessSettingsUnavailable()
            }
        }
    }

    // 当确定了目标页面后，导航到相应页面
    LaunchedEffect(startDestination) {
        val destination = startDestination ?: return@LaunchedEffect
        if (navController.currentBackStackEntry?.destination?.route != destination) {
            navController.popBackStack(TerminalRoutes.TERMINAL_HOME_ROUTE, inclusive = true)
            navController.navigate(destination)
        }
    }

    LaunchedEffect(env.forceShowSetup, env.ubuntuRuntimeState.phase) {
        if (
            terminalController.localBusinessSettingsAvailable &&
                env.forceShowSetup &&
                env.ubuntuRuntimeState.phase == UbuntuRuntimePhase.RUNNING &&
                navController.currentBackStackEntry?.destination?.route != TerminalRoutes.SETUP_ROUTE
        ) {
            navController.navigate(TerminalRoutes.SETUP_ROUTE)
        }
    }
}


@Composable
private fun ResidentBusinessSettingsUnavailable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Resident 模式下 Ubuntu 业务设置由 Core 独占；Host 不启动本地 Setup/Settings 业务运行时。",
            modifier = Modifier.padding(24.dp)
        )
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.manifestSoftInputMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getActivityInfo(
            componentName,
            PackageManager.ComponentInfoFlags.of(0)
        ).softInputMode
    } else {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(componentName, 0).softInputMode
    }
