package com.ai.limbs.plugins.systemenvironment

import android.content.Context
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import kotlinx.coroutines.launch
import org.json.JSONObject

/** System Environment owns its top-bar button; Host supplies the slot and navigation. */
internal class SystemEnvironmentLauncherProvider(
    private val host: InProcessPluginUiHost
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { Launcher() }
        }

    @Composable
    private fun Launcher() {
        val scope = rememberCoroutineScope()
        IconButton(onClick = {
            scope.launch {
                runCatching {
                    host.invokeHostCapability(
                        "host.ui.surface@1",
                        JSONObject()
                            .put("operation", "open")
                            .put("screen_id", SystemEnvironmentContract.SCREEN_ID)
                            .toString()
                    )
                }.onFailure { host.logger.w("SystemEnvironmentLauncher", "Open failed", it) }
            }
        }) {
            Icon(Icons.Default.Terminal, contentDescription = "系统环境中心", tint = Color.White)
        }
    }
}
