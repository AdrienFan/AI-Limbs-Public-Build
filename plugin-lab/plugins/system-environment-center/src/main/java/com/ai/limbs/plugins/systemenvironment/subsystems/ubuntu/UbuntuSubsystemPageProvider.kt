package com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.rememberTerminalEnv
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.ui.SettingsScreen
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.ui.TerminalHome
import java.io.File
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class UbuntuSubsystemPageProvider(
    private val host: InProcessPluginHost,
    private val terminal: TerminalManager,
    private val nativeLibraryDir: File
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View {
        val uiContext = UbuntuSubsystemRuntimeContext(
            base = host.createPluginContext(context),
            pluginId = host.pluginId,
            pluginDataDir = host.dataDir,
            pluginCacheDir = host.cacheDir,
            nativeLibraryDir = nativeLibraryDir
        )
        return ComposeView(uiContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    UbuntuTerminalPluginPage(host, terminal, sharedUi)
                }
            }
        }
    }
}

private const val HOST_PRESENTATION_CAPABILITY = "host.ui.presentation@1"
private const val UBUNTU_SCREEN_ID = "plugin.system_environment_center.screen"

private enum class UbuntuPageRoute { TERMINAL, ENVIRONMENT_CONFIG, SETTINGS }

@Composable
private fun UbuntuTerminalPluginPage(
    host: InProcessPluginHost,
    terminal: TerminalManager,
    sharedUi: InProcessSharedUiHost
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val originalSoftInputMode = remember(activity) { activity?.manifestSoftInputMode() }
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(UbuntuPageRoute.TERMINAL) }

    fun requestPresentationMode(mode: String) {
        scope.launch {
            runCatching {
                host.invokeHostCapability(
                    HOST_PRESENTATION_CAPABILITY,
                    JSONObject()
                        .put("operation", "set_mode")
                        .put("screen_id", UBUNTU_SCREEN_ID)
                        .put("mode", mode)
                        .toString()
                )
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "全屏切换失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    val env = rememberTerminalEnv(terminal)

    BackHandler(enabled = route != UbuntuPageRoute.TERMINAL) {
        route = UbuntuPageRoute.TERMINAL
    }

    LaunchedEffect(env.ubuntuRuntimeState.phase, env.sessions.size) {
        if (env.ubuntuRuntimeState.phase == UbuntuRuntimePhase.RUNNING && env.sessions.isEmpty()) {
            runCatching { terminal.createNewSession() }
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
        UbuntuPageRoute.TERMINAL -> TerminalHome(
            env = env,
            useLocalImeHandling = true,
            onNavigateToSetup = { route = UbuntuPageRoute.ENVIRONMENT_CONFIG },
            onNavigateToSettings = { route = UbuntuPageRoute.SETTINGS },
            onRequestPortraitFullscreen = { requestPresentationMode("fullscreen_portrait") },
            onRequestLandscapeFullscreen = { requestPresentationMode("fullscreen_landscape") },
            onRequestExitFullscreen = { requestPresentationMode("normal") }
        )
        UbuntuPageRoute.ENVIRONMENT_CONFIG -> UbuntuEnvironmentConfigPage(
            sharedUi = sharedUi,
            onBack = { route = UbuntuPageRoute.TERMINAL }
        )
        UbuntuPageRoute.SETTINGS -> SettingsScreen(
            onBack = { route = UbuntuPageRoute.TERMINAL }
        )
    }
}

@Composable
private fun UbuntuEnvironmentConfigPage(
    @Suppress("UNUSED_PARAMETER") sharedUi: InProcessSharedUiHost,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier.height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                text = "环境配置",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(0.dp)
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
