package com.ai.limbs.extensions.systemenvironment.ubuntu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalUiController
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type.TerminalType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.rememberTerminalEnv
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.SettingsScreen
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.TerminalSettingsController
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.StandardDevelopmentEnvironmentCard
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.TerminalHome
import java.io.File
import kotlinx.coroutines.launch

internal class UbuntuSubsystemPageProvider(
    private val host: InProcessPluginUiHost,
    private val terminal: TerminalUiController,
    private val nativeLibraryDir: File,
    private val settingsControllerFactory: ((Context) -> TerminalSettingsController)? = null
) : InProcessPageProvider {
    override fun createView(
        context: Context,
        @Suppress("UNUSED_PARAMETER") sharedUi: InProcessSharedUiHost
    ): View {
        val uiContext = createUiContext(context)
        return ComposeView(uiContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    UbuntuDisplay(terminal, settingsControllerFactory)
                }
            }
        }
    }

    fun createConfigurationView(
        context: Context,
        onRequestDisplay: () -> Unit
    ): View = ComposeView(createUiContext(context)).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                UbuntuConfigurationPanel(terminal, onRequestDisplay)
            }
        }
    }

    private fun createUiContext(context: Context): Context = UbuntuSubsystemRuntimeContext(
        base = host.createPluginContext(context),
        pluginId = host.pluginId,
        pluginDataDir = host.dataDir,
        pluginCacheDir = host.cacheDir,
        nativeLibraryDir = nativeLibraryDir
    )
}

private enum class UbuntuDisplayRoute { TERMINAL, SETTINGS }

@Composable
private fun UbuntuConfigurationPanel(
    terminal: TerminalUiController,
    onRequestDisplay: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var launching by remember { mutableStateOf(false) }

    StandardDevelopmentEnvironmentCard(
        enabled = !launching,
        onOpenGuide = {
            launching = true
            coroutineScope.launch {
                val foreground = terminal.terminalState.value.sessions
                    .firstOrNull { !it.isBackground && it.terminalType == TerminalType.LOCAL }
                val prepared = terminal.prepareDevelopmentEnvironmentInstaller()
                if (foreground != null && prepared) {
                    terminal.switchToSession(foreground.id)
                    terminal.sendCommandToSession(
                        foreground.id,
                        "/usr/local/lib/ai-limbs/bootstrap.sh --force-prompt"
                    )
                    onRequestDisplay()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(
                            com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.standard_dev_env_open_failed
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                launching = false
            }
        }
    )
}

@Composable
private fun UbuntuDisplay(
    terminal: TerminalUiController,
    settingsControllerFactory: ((Context) -> TerminalSettingsController)?
) {
    val context = LocalContext.current
    val residentSettings = remember(context, settingsControllerFactory) {
        lazy(LazyThreadSafetyMode.NONE) { settingsControllerFactory?.invoke(context) }
    }
    val activity = remember(context) { context.findActivity() }
    val originalSoftInputMode = remember(activity) { activity?.manifestSoftInputMode() }
    var route by remember { mutableStateOf(UbuntuDisplayRoute.TERMINAL) }
    val env = rememberTerminalEnv(terminal)

    BackHandler(enabled = route != UbuntuDisplayRoute.TERMINAL) {
        route = UbuntuDisplayRoute.TERMINAL
    }
    LaunchedEffect(env.ubuntuRuntimeState.phase, env.sessions.size) {
        if (env.ubuntuRuntimeState.phase == UbuntuRuntimePhase.RUNNING && env.sessions.isEmpty()) {
            terminal.createNewSession()
        }
    }
    DisposableEffect(activity, originalSoftInputMode) {
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            if (activity != null && originalSoftInputMode != null) {
                activity.window.setSoftInputMode(originalSoftInputMode)
            }
        }
    }

    when (route) {
        UbuntuDisplayRoute.TERMINAL -> TerminalHome(
            env = env,
            useLocalImeHandling = true,
            onNavigateToSettings = { route = UbuntuDisplayRoute.SETTINGS }
        )
        UbuntuDisplayRoute.SETTINGS -> {
            when {
                terminal.localBusinessSettingsAvailable ->
                    SettingsScreen(onBack = { route = UbuntuDisplayRoute.TERMINAL })
                settingsControllerFactory != null ->
                    SettingsScreen(
                        onBack = { route = UbuntuDisplayRoute.TERMINAL },
                        controller = requireNotNull(residentSettings.value)
                    )
                else -> ResidentSafeSettingsScreen(onBack = { route = UbuntuDisplayRoute.TERMINAL })
            }
        }
    }
}

@Composable
private fun ResidentSafeSettingsScreen(onBack: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.OutlinedButton(onClick = onBack) {
            androidx.compose.material3.Text("返回")
        }
        androidx.compose.material3.Text(
            "Ubuntu 设置",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
        )
        androidx.compose.material3.Text(
            "Resident 模式下终端业务由 Core 独占。SSH、软件源、FTP 和 chroot 等业务设置不会在 Host 启动第二套 SettingsViewModel；这些项目需要通过 Core 代理修改。"
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
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
