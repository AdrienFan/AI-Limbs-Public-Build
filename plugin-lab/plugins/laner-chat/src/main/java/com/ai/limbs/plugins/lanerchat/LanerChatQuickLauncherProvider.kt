package com.ai.limbs.plugins.lanerchat

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Laner Chat owns the launcher UI, unread badge and toggle; Host only supplies a page slot. */
internal class LanerChatQuickLauncherProvider(
    private val host: InProcessPluginUiHost,
    private val invokeBusiness: suspend (String, JSONObject) -> JSONObject
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { Launcher() }
        }

    private suspend fun isOpen(): Boolean {
        val result = JSONObject(host.invokeHostCapability(
            "host.window.overlay@1", JSONObject().put("operation", "list").toString()
        )).getJSONArray("windows")
        return (0 until result.length()).any { result.getString(it) == LANER_CHAT_OVERLAY_ID }
    }

    private suspend fun toggle() {
        val open = isOpen()
        host.invokeHostCapability(
            "host.window.overlay@1",
            JSONObject().put("operation", if (open) "remove" else "create")
                .put("overlay_id", LANER_CHAT_OVERLAY_ID)
                .put("provider_id", LANER_CHAT_QUICK_PROVIDER_ID)
                .toString()
        )
    }

    @Composable
    private fun Launcher() {
        var open by remember { mutableStateOf(false) }
        var unread by remember { mutableIntStateOf(0) }
        var pending by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            while (isActive) {
                try {
                    open = isOpen()
                    unread = invokeBusiness("quick.count", JSONObject()).optInt("unread_count")
                } catch (failure: Exception) {
                    host.logger.w("LanerChat", "Quick launcher status failed", failure)
                }
                delay(1_500L)
            }
        }
        IconButton(
            enabled = !pending,
            onClick = {
                pending = true
                scope.launch {
                    try {
                        toggle()
                        open = isOpen()
                    } catch (failure: Exception) {
                        host.logger.w("LanerChat", "Quick launcher toggle failed", failure)
                    } finally {
                        pending = false
                    }
                }
            }
        ) {
            Box {
                Icon(
                    Icons.Default.ChatBubble,
                    contentDescription = if (open) "关闭快捷聊天" else "开启快捷聊天",
                    tint = if (open) Color(0xff4caf50) else Color.White
                )
                if (unread > 0) {
                    Text(
                        unread.coerceAtMost(99).toString(),
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp)
                            .background(Color.Red, CircleShape),
                        color = Color.White
                    )
                }
            }
        }
    }
}
