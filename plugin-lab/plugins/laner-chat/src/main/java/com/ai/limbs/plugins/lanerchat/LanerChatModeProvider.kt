package com.ai.limbs.plugins.lanerchat

import android.content.Context
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessChatModeBehaviorKeys
import com.ai.limbs.plugin.runtime.InProcessChatModeExtensionProvider
import com.ai.limbs.plugin.runtime.InProcessChatModeSlotIds
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class LanerChatModeProvider(
    private val uiHost: InProcessPluginUiHost,
    private val invokeBusiness: suspend (String, JSONObject) -> JSONObject
) : InProcessChatModeExtensionProvider {
    private val mutableState = MutableStateFlow<String?>(null)
    override val stateJson: StateFlow<String?> = mutableState

    private val priority = MutableStateFlow(LanerChatPriority.NORMAL)
    private val activeContext = AtomicReference(JSONObject())
    private val syncedPresentationKeys = ConcurrentHashMap.newKeySet<String>()
    private val submitMutex = Mutex()
    private var pollingJob: Job? = null

    override fun matches(contextJson: String): Boolean {
        val context = jsonObject(contextJson)
        val providerTypeId = context.optString("api_provider_type_id").trim()
        val configId = context.optString("config_id").trim()
        return providerTypeId.equals(LanerChatContract.PROVIDER_TYPE_ID, ignoreCase = true) ||
            configId.equals(LanerChatContract.CONFIG_ID, ignoreCase = true)
    }

    override fun behavior(contextJson: String): String =
        JSONObject()
            .put(InProcessChatModeBehaviorKeys.PREFERRED_INPUT_STYLE, "classic")
            .put(InProcessChatModeBehaviorKeys.SUPPRESS_LOCAL_AGENT_FEATURES, true)
            .put(InProcessChatModeBehaviorKeys.DIRECT_SEND_WHILE_PROCESSING, true)
            .put(InProcessChatModeBehaviorKeys.SUPPRESS_PENDING_QUEUE, true)
            .put(InProcessChatModeBehaviorKeys.HANDLES_CANCEL, true)
            .put(InProcessChatModeBehaviorKeys.HANDLES_RESUME, true)
            .toString()

    override fun configurationTemplate(): String =
        JSONObject()
            .put("config_id", LanerChatContract.CONFIG_ID)
            .put("name", "兰儿桥接聊天")
            .put("model_name", LanerChatContract.MODEL_ID)
            .put("api_provider_type_id", LanerChatContract.PROVIDER_TYPE_ID)
            .put("enable_tool_call", false)
            .put("enable_summary", false)
            .put("enable_summary_by_message_count", false)
            .put("enable_direct_image_processing", false)
            .put("enable_direct_audio_processing", false)
            .put("enable_direct_video_processing", false)
            .toString()

    override fun createSlotView(slotId: String, context: Context): View? {
        val pluginContext = uiHost.createPluginContext(context)
        return when (slotId) {
            InProcessChatModeSlotIds.CONFIGURATION_CARD ->
                composeView(pluginContext) { card ->
                    LanerConfigurationCard { card.performClick() }
                }
            InProcessChatModeSlotIds.STATUS_OVERLAY -> null
            InProcessChatModeSlotIds.COMPOSER_ACCESSORY ->
                composeView(pluginContext) { _ -> LanerComposerAccessory() }
            else -> null
        }
    }

    override suspend fun onChatContextChanged(contextJson: String): String {
        val context = jsonObject(contextJson)
        activeContext.set(JSONObject(context.toString()))
        if (!matches(context.toString())) {
            stopPolling()
            return JSONObject().put("active", false).toString()
        }

        context.optString("chat_id").trim().takeIf { it.isNotEmpty() }?.let { chatId ->
            val bound = invoke("ui.bind_chat", JSONObject().put("chat_id", chatId))
            syncHistoricalPresentations(
                chatId = chatId,
                presentations = bound.optJSONArray("message_presentations")
            )
            val quick = invoke("quick.snapshot").getJSONObject("quick")
            val messages = quick.getJSONArray("messages")
            if (messages.length() > 0) {
                invoke("quick.ack", JSONObject()
                    .put("chat_id", chatId)
                    .put("through", messages.getJSONObject(messages.length() - 1).getLong("timestamp")))
            }
        }
        refreshStatus()
        ensurePolling()
        return JSONObject().put("active", true).toString()
    }

    override suspend fun submit(requestJson: String): String =
        submitMutex.withLock {
            val request = jsonObject(requestJson)
            val chatId = request.optString("chat_id").trim()
            require(chatId.isNotEmpty()) { "chat_id is required for Laner Chat submit" }
            val content = request.optString("content")
            val originalText = request.optString("original_text", content)
            val attachments = request.optJSONArray("attachments") ?: JSONArray()
            require(content.isNotBlank() || attachments.length() > 0) {
                "Laner Chat submit requires text or attachments"
            }

            val selectedPriority =
                if (request.has("priority")) {
                    LanerChatPriority.valueOf(request.getString("priority").uppercase())
                } else {
                    priority.value
                }
            val result = invoke(
                "mailbox.enqueue",
                JSONObject()
                    .put("chat_id", chatId)
                    .put("text", content)
                    .put("sender", LanerChatContract.DEFAULT_SENDER)
                    .put("priority", selectedPriority.name)
                    .put("attachments", JSONArray(attachments.toString()))
            )
            val queued = result.getJSONObject("request")
            val messageTimestamp = queued.getLong("chat_message_timestamp")
            val semanticTone = semanticTone(selectedPriority)
            val hostMessagePublished =
                try {
                    val published =
                        JSONObject(
                            uiHost.invokeHostCapability(
                                "host.chat@1",
                                JSONObject()
                                    .put("operation", "publish_user")
                                    .put("chat_id", chatId)
                                    .put("content", content)
                                    .put("message_timestamp", messageTimestamp)
                                    .put(
                                        "presentation",
                                        JSONObject().put("semantic_tone", semanticTone)
                                    )
                                    .toString()
                            )
                        )
                    (published.optBoolean("success") && published.optBoolean("persisted")).also {
                        if (it) {
                            syncedPresentationKeys.add(
                                presentationKey(chatId, messageTimestamp, semanticTone)
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            val attachmentNames =
                (0 until attachments.length()).mapNotNull { index ->
                    attachments.optJSONObject(index)
                        ?.optString("file_name")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            if (!request.has("priority")) priority.value = LanerChatPriority.NORMAL
            refreshStatus()
            JSONObject()
                .put("success", true)
                .put("request_id", queued.getString("request_id"))
                .put("message_timestamp", messageTimestamp)
                .put("host_message_published", hostMessagePublished)
                .put(
                    "presentation_json",
                    JSONObject().put("semantic_tone", semanticTone).toString()
                )
                .put(
                    "title",
                    LanerChatContract.localConversationTitle(originalText, attachmentNames)
                )
                .put(
                    "mode_metadata_json",
                    JSONObject().put("priority", selectedPriority.name).toString()
                )
                .toString()
        }

    override suspend fun cancel(contextJson: String): String {
        val result = invoke("turn.cancel", sessionParameters(contextJson))
        refreshStatus()
        return result.toString()
    }

    override suspend fun resume(contextJson: String): String {
        val result = invoke("turn.resume", sessionParameters(contextJson))
        refreshStatus()
        return result.toString()
    }

    suspend fun stop() {
        stopPolling()
    }

    private suspend fun syncHistoricalPresentations(
        chatId: String,
        presentations: JSONArray?
    ) {
        if (presentations == null) return
        for (index in 0 until presentations.length()) {
            val item = presentations.optJSONObject(index) ?: continue
            val timestamp = item.optLong("message_timestamp", 0L)
            if (timestamp <= 0L) continue
            val priorityValue =
                runCatching {
                    LanerChatPriority.valueOf(item.optString("priority").trim().uppercase())
                }.getOrNull() ?: continue
            val tone = semanticTone(priorityValue)
            val key = presentationKey(chatId, timestamp, tone)
            if (key in syncedPresentationKeys) continue
            try {
                val updated =
                    JSONObject(
                        uiHost.invokeHostCapability(
                            "host.chat@1",
                            JSONObject()
                                .put("operation", "set_presentation")
                                .put("chat_id", chatId)
                                .put("message_timestamp", timestamp)
                                .put(
                                    "presentation",
                                    JSONObject().put("semantic_tone", tone)
                                )
                                .toString()
                        )
                    )
                if (updated.optBoolean("success") && updated.optBoolean("persisted")) {
                    syncedPresentationKeys.add(key)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort: a later chat-context refresh will retry this timestamp.
            }
        }
    }

    private fun semanticTone(priority: LanerChatPriority): String =
        when (priority) {
            LanerChatPriority.HIGH -> "danger"
            LanerChatPriority.NORMAL -> "info"
            LanerChatPriority.LOW -> "success"
        }

    private fun presentationKey(chatId: String, timestamp: Long, tone: String): String =
        "$chatId:$timestamp:$tone"

    private suspend fun refreshStatus() {
        runCatching { invoke("status") }
            .onSuccess { mutableState.value = it.toString() }
            .onFailure { error ->
                mutableState.value =
                    JSONObject()
                        .put("success", false)
                        .put("error", error.message ?: error.javaClass.simpleName)
                        .toString()
            }
    }

    private fun ensurePolling() {
        if (pollingJob?.isActive == true) return
        pollingJob =
            uiHost.scope.launch {
                while (isActive && matches(activeContext.get().toString())) {
                    refreshStatus()
                    delay(LanerChatContract.PRESENCE_UI_TICK_MS)
                }
            }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun invoke(name: String, parameters: JSONObject = JSONObject()): JSONObject =
        invokeBusiness(name, JSONObject(parameters.toString()))

    private fun sessionParameters(rawContext: String): JSONObject {
        val context = jsonObject(rawContext)
        return JSONObject().apply {
            context.optString("session_id").trim().takeIf { it.isNotEmpty() }?.let {
                put("session_id", it)
            }
        }
    }

    private fun jsonObject(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse { JSONObject() }

    private fun composeView(
        context: Context,
        content: @Composable (ComposeView) -> Unit
    ): View =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    content(this@apply)
                }
            }
        }

    @Composable
    private fun LanerConfigurationCard(onSelect: () -> Unit) {
        // The card owns its touch behavior; performClick notifies the Host's
        // generic chat-mode selection listener on the root View.
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Laner Chat", style = MaterialTheme.typography.titleMedium)
                Text(
                    "兰儿聊天桥 · Durable Mailbox / Session / Priority / Assistant Turn",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "点按此卡片进入兰儿聊天模式",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    @Composable
    private fun LanerStatusBadge(status: JSONObject) {
        val mailbox = status.optJSONObject("mailbox") ?: JSONObject()
        val queue = status.optJSONObject("queue") ?: JSONObject()
        val activeSessionId =
            mailbox.optString("active_session_id")
                .takeUnless { mailbox.isNull("active_session_id") || it.isBlank() }
        val lastSeen =
            if (mailbox.has("last_agent_seen_at_ms") && !mailbox.isNull("last_agent_seen_at_ms")) {
                mailbox.optLong("last_agent_seen_at_ms")
            } else null
        val hasActiveTurn =
            mailbox.has("active_turn_id") &&
                !mailbox.isNull("active_turn_id") &&
                mailbox.optString("active_turn_id").isNotBlank()
        val presence =
            LanerChatContract.presenceState(
                activeSessionId = activeSessionId,
                lastAgentSeenAtMs = lastSeen,
                hasActiveTurn = hasActiveTurn
            )
        val unresolved = queue.optInt("unresolved_count", 0)
        val label =
            when (presence) {
                LanerChatPresenceState.ACTIVE -> "在线"
                LanerChatPresenceState.RECENT -> "最近在线"
                LanerChatPresenceState.WAITING -> "等待 Agent"
            }
        Surface {
            Text(
                text = "兰儿 · $label · 待处理 $unresolved",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }

    @Composable
    private fun LanerComposerAccessory() {
        val selected by priority.collectAsState()
        val raw by stateJson.collectAsState()
        val status = raw?.let(::jsonObject) ?: JSONObject()
        val mailbox = status.optJSONObject("mailbox") ?: JSONObject()
        val activeTurn =
            mailbox.has("active_turn_id") && !mailbox.isNull("active_turn_id") &&
                mailbox.optString("active_turn_id").isNotBlank()
        val schedulerPaused = mailbox.optBoolean("scheduler_paused", false)
        val scope = rememberCoroutineScope()

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PriorityChip("🔴 紧急", LanerChatPriority.HIGH, selected)
                PriorityChip("🔵 普通", LanerChatPriority.NORMAL, selected)
                PriorityChip("🟢 稍后", LanerChatPriority.LOW, selected)
                Spacer(modifier = Modifier.weight(1f))
                LanerStatusBadge(status)
            }
            if (activeTurn || schedulerPaused) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (activeTurn) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    cancel(activeContext.get().toString())
                                }
                            }
                        ) { Text("停止") }
                    } else if (schedulerPaused) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    resume(activeContext.get().toString())
                                }
                            }
                        ) { Text("继续") }
                    }
                }
            }
        }
    }

    @Composable
    private fun PriorityChip(
        label: String,
        value: LanerChatPriority,
        selected: LanerChatPriority
    ) {
        FilterChip(
            selected = selected == value,
            onClick = { priority.value = value },
            label = { Text(label) }
        )
    }
}
