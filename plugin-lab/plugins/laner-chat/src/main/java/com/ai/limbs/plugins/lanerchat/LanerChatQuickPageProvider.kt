package com.ai.limbs.plugins.lanerchat

import android.content.Context
import android.view.View
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessOverlayPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The content and unread state belong to Laner Chat; Android window mechanics belong to Host. */
internal class LanerChatQuickPageProvider(
    private val host: InProcessPluginUiHost,
    private val chatMode: LanerChatModeProvider,
    private val invokeBusiness: suspend (String, JSONObject) -> JSONObject
) : InProcessOverlayPageProvider {
    private val openSequence = MutableStateFlow(0)

    override fun open() {
        openSequence.value += 1
    }

    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    QuickChat()
                }
            }
        }

    private suspend fun quickSnapshot(): JSONObject =
        invokeBusiness("quick.snapshot", JSONObject()).getJSONObject("quick")

    private suspend fun acknowledge(chatId: String, timestamp: Long) {
        if (chatId.isNotEmpty() && timestamp > 0L) {
            invokeBusiness(
                "quick.ack",
                JSONObject().put("chat_id", chatId).put("through", timestamp)
            )
        }
    }

    private suspend fun setExpanded(expanded: Boolean) {
        host.invokeHostCapability(
            "host.window.overlay@1",
            JSONObject()
                .put("operation", "update")
                .put("overlay_id", LANER_CHAT_OVERLAY_ID)
                .put("expanded", expanded)
                .toString()
        )
    }

    private suspend fun closeOverlay() {
        host.invokeHostCapability(
            "host.window.overlay@1",
            JSONObject()
                .put("operation", "remove")
                .put("overlay_id", LANER_CHAT_OVERLAY_ID)
                .toString()
        )
    }

    private suspend fun moveBy(dx: Int, dy: Int) {
        host.invokeHostCapability(
            "host.window.overlay@1",
            JSONObject()
                .put("operation", "update")
                .put("overlay_id", LANER_CHAT_OVERLAY_ID)
                .put("delta_x", dx)
                .put("delta_y", dy)
                .toString()
        )
    }

    private data class Bubble(val timestamp: Long, val content: String)

    @Composable
    private fun QuickChat() {
        var expanded by remember { mutableStateOf(false) }
        var chatId by remember { mutableStateOf("") }
        var unread by remember { mutableIntStateOf(0) }
        var lastUserTimestamp by remember { mutableStateOf<Long?>(null) }
        var draft by remember { mutableStateOf("") }
        var priority by remember { mutableStateOf(LanerChatPriority.NORMAL) }
        var error by remember { mutableStateOf<String?>(null) }
        val bubbles = remember { mutableStateListOf<Bubble>() }
        val scope = rememberCoroutineScope()
        val dragMoves = remember { Channel<Pair<Int, Int>>(Channel.UNLIMITED) }

        // The plugin decides whether the bubble was tapped or dragged; Host moves its window.
        LaunchedEffect(dragMoves) {
            for ((dx, dy) in dragMoves) {
                try {
                    moveBy(dx, dy)
                } catch (failure: Exception) {
                    error = failure.message ?: "移动快捷聊天失败"
                }
            }
        }

        LaunchedEffect(Unit) {
            openSequence.collect { sequence ->
                if (sequence == 0) return@collect
                try {
                    val snapshot = quickSnapshot()
                    chatId = snapshot.optString("chat_id")
                    val messages = snapshot.getJSONArray("messages")
                    bubbles.clear()
                    lastUserTimestamp = snapshot.getLong("last_user_timestamp")
                    for (index in 0 until messages.length()) {
                        val item = messages.getJSONObject(index)
                        bubbles.add(Bubble(item.getLong("timestamp"), item.getString("content")))
                    }
                    setExpanded(true)
                    expanded = true
                    if (messages.length() > 0) {
                        acknowledge(chatId, messages.getJSONObject(messages.length() - 1).getLong("timestamp"))
                    }
                    unread = 0
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "打开快捷聊天失败"
                }
            }
        }

        LaunchedEffect(Unit) {
            while (isActive) {
                try {
                    val snapshot = quickSnapshot()
                    chatId = snapshot.optString("chat_id")
                    val messages = snapshot.getJSONArray("messages")
                    val currentUserTimestamp = snapshot.getLong("last_user_timestamp")
                    if (lastUserTimestamp?.let { currentUserTimestamp > it } == true) {
                        bubbles.clear()
                    }
                    lastUserTimestamp = currentUserTimestamp
                    unread = messages.length()
                    if (expanded && messages.length() > 0) {
                        for (index in 0 until messages.length()) {
                            val message = messages.getJSONObject(index)
                            val timestamp = message.getLong("timestamp")
                            if (bubbles.none { it.timestamp == timestamp }) {
                                bubbles.add(Bubble(timestamp, message.getString("content")))
                            }
                        }
                        acknowledge(chatId, messages.getJSONObject(messages.length() - 1).getLong("timestamp"))
                        unread = 0
                    }
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "读取快捷消息失败"
                }
                delay(1_500L)
            }
        }

        if (!expanded) {
            Button(
                onClick = ::open,
                modifier = Modifier.size(56.dp).pointerInput(dragMoves) {
                    var remainderX = 0f
                    var remainderY = 0f
                    detectDragGestures(
                        onDragStart = {
                            remainderX = 0f
                            remainderY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            remainderX += amount.x
                            remainderY += amount.y
                            val dx = remainderX.toInt()
                            val dy = remainderY.toInt()
                            remainderX -= dx
                            remainderY -= dy
                            if (dx != 0 || dy != 0) dragMoves.trySend(dx to dy)
                        }
                    )
                }
            ) { Text(if (unread > 0) "$unread" else "聊") }
            return
        }

        Surface(tonalElevation = 5.dp) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("兰儿快捷聊天", style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                setExpanded(false)
                                expanded = false
                                bubbles.clear()
                            }
                        }) { Text("收起") }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    closeOverlay()
                                } catch (failure: Exception) {
                                    error = failure.message ?: "关闭快捷聊天失败"
                                }
                            }
                        }) { Text("关闭") }
                    }
                }
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    bubbles.forEach { bubble ->
                        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Text(bubble.content, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
                if (chatId.isEmpty()) Text("请先在正式聊天室打开兰儿会话")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        LanerChatPriority.HIGH to "🔴 紧急",
                        LanerChatPriority.NORMAL to "🔵 普通",
                        LanerChatPriority.LOW to "🟢 稍后"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = priority == value,
                            onClick = { priority = value },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("输入消息") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = chatId.isNotEmpty() && draft.isNotBlank(),
                    onClick = {
                        scope.launch {
                            try {
                                val result = JSONObject(chatMode.submit(
                                    JSONObject()
                                        .put("chat_id", chatId)
                                        .put("content", draft)
                                        .put("priority", priority.name)
                                        .toString()
                                ))
                                check(result.getBoolean("success") && result.getBoolean("host_message_published")) {
                                    "消息未写入正式聊天室"
                                }
                                draft = ""
                                priority = LanerChatPriority.NORMAL
                                bubbles.clear()
                                error = null
                            } catch (failure: Exception) {
                                error = failure.message ?: "发送失败"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("发送") }
            }
        }
    }
}
