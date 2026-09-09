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
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type.TerminalType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.rememberTerminalEnv
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.SettingsScreen
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.StandardDevelopmentEnvironmentCard
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.TerminalHome
import java.io.File
import kotlinx.coroutines.launch

internal class UbuntuSubsystemPageProvider(
    private val host: InProcessPluginHost,
    private val terminal: TerminalManager,
    private val nativeLibraryDir: File
) : InProcessPageProvider {
    override fun createView(
        context: Context,
        @Suppress("UNUSED_PARAMETER") sharedUi: InProcessSharedUiHost
    ): View {
        return ComposeView(createUiContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    UbuntuDisplay(terminal)
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
    terminal: TerminalManager,
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
private fun UbuntuDisplay(terminal: TerminalManager) {
    val context = LocalContext.current
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
        UbuntuDisplayRoute.SETTINGS -> SettingsScreen(
            onBack = { route = UbuntuDisplayRoute.TERMINAL }
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
