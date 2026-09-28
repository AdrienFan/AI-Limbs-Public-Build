package com.ai.limbs.plugins.lanerchat

import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilityParameterSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessMetadataOnlyProvider
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException

internal const val LANER_CHAT_PLUGIN_ID = "plugin.chat.laner_bridge"
internal const val LANER_CHAT_PROVIDER_ID = "$LANER_CHAT_PLUGIN_ID.runtime"

class LanerChatEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        require(host.pluginId == LANER_CHAT_PLUGIN_ID) {
            "Unexpected Laner Chat identity: ${host.pluginId}"
        }
        val service = LanerChatBridgeService.create(host.dataDir, host.applicationContext)

        val controller = LanerChatController(service, host)
        val operations = linkedMapOf<String, suspend (JSONObject) -> JSONObject>()

        val attentionJob =
            host.scope.launch {
                try {
                    service.status
                        .map { service.attentionSummary() }
                        .distinctUntilChanged()
                        .collect { summary ->
                            try {
                                publishAttention(host, summary)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                host.logger.w(
                                    "LanerChat",
                                    "Unable to publish Attention sideband",
                                    error
                                )
                            }
                        }
                } finally {
                    withContext(NonCancellable) {
                        runCatching {
                            host.invokeHostCapability(
                                "host.attention@1",
                                JSONObject().put("operation", "clear").toString()
                            )
                        }
                    }
                }
            }

        host.registerProvider(
            LANER_CHAT_PROVIDER_ID,
            InProcessMetadataOnlyProvider,
            mapOf(
                "kind" to "chat_mode_runtime",
                "api" to "1",
                "config_id" to LanerChatContract.CONFIG_ID,
                "provider_type_id" to LanerChatContract.PROVIDER_TYPE_ID
            )

        )

        fun parameter(
            name: String,
            type: String = "string",
            description: String = "",
            required: Boolean = true,
            default: String? = null
        ) = InProcessCapabilityParameterSpec(
            name = name,
            type = type,
            description = description,
            required = required,
            default = default
        )

        fun capability(
            name: String,
            title: String,
            description: String,
            effect: InProcessCapabilityEffect,
            parameters: List<InProcessCapabilityParameterSpec> = emptyList(),
            block: suspend (JSONObject) -> JSONObject
        ) {
            check(operations.put(name, block) == null) { "Duplicate Laner Chat operation: $name" }
            val properties = JSONObject()
            val required = JSONArray()
            parameters.forEach { item ->
                val field = JSONObject()
                    .put("type", item.type)
                    .put("description", item.description)
                item.default?.let { field.put("default", it) }
                properties.put(item.name, field)
                if (item.required) required.put(item.name)
            }
            host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$LANER_CHAT_PLUGIN_ID.$name",
                    displayName = title,
                    description = description,
                    keywords = listOf("Laner Chat", "兰儿聊天", "邮箱", "Assistant Turn"),
                    parameters = parameters,
                    inputSchema = JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", required)
                        .put("additionalProperties", true)
                        .toString(),
                    effect = effect,
                    domain = InProcessCapabilityDomain.LANER_CHAT,
                    executor = InProcessCapabilityExecutor { raw ->
                        block(JSONObject(raw.ifBlank { "{}" })).toString()
                    }
                )
            )
        }

        val read = InProcessCapabilityEffect.READ_ONLY
        val write = InProcessCapabilityEffect.PERSISTENT_WRITE
        val state = InProcessCapabilityEffect.STATE_CHANGE

        capability(
            "status",
            "读取 Laner Chat 插件状态",
            "读取影子插件自己的会话、邮箱、优先级和 Assistant Turn 状态，不返回消息正文。",
            read
        ) { controller.status() }

        capability(
            "session.open",
            "打开 Laner Chat 会话",
            "恢复指定 Session，或在没有活动 Session 时创建新的 Laner Chat Session。",
            write,
            listOf(
                parameter("session_id", description = "可选的已有 Session ID", required = false),
                parameter("agent_session_id", description = "可选的当前 Agent Session ID", required = false)
            )
        ) { controller.sessionOpen(it) }

        capability(
            "session.close",
            "关闭 Laner Chat 会话",
            "关闭指定 Session；不传 session_id 时关闭当前活动 Session。",
            write,
            listOf(parameter("session_id", description = "可选 Session ID", required = false))
        ) { controller.sessionClose(it) }

        capability(
            "ui.bind_chat",
            "绑定 Laner Chat 到主聊天 ID",
            "影子插件内部迁移接口：把活动 Laner Session 绑定到一个 AI Limbs chat_id。",
            write,
            listOf(parameter("chat_id", description = "AI Limbs 主聊天 ID"))
        ) { controller.bindUiChat(it) }

        capability(
            "notification.check",
            "检查 Laner Chat 通知",
            "只返回未决消息的序号和优先级计数，不返回正文。",
            read,
            listOf(
                parameter("after_seq", "integer", "游标；默认 0", false, "0"),
                parameter("session_id", description = "可选 Session 过滤", required = false)
            )
        ) { controller.notificationCheck(it) }

        capability(
            "notification.wait",
            "短时等待 Laner Chat 通知",
            "最多等待 30 秒，只返回通知元数据，不返回正文。",
            read,
            listOf(
                parameter("after_seq", "integer", "游标；默认 0", false, "0"),
                parameter("timeout_seconds", "integer", "等待秒数，最大 30", false, "25"),
                parameter("session_id", description = "可选 Session 过滤", required = false)
            )
        ) { controller.notificationWait(it) }

        capability(
            "inbox.fetch",
            "读取 Laner Chat 收件箱",
            "显式读取未决消息正文；读取后 PENDING 会进入 DELIVERED，但仍保持待处理。",
            state,
            listOf(
                parameter("session_id", description = "可选 Session", required = false),
                parameter("request_id", description = "可选精确 request_id", required = false),
                parameter("after_seq", "integer", "序号游标", false, "0"),
                parameter("limit", "integer", "返回数量上限", false, "10"),
                parameter("priority", description = "HIGH/NORMAL/LOW，可选", required = false)
            )
        ) { controller.inboxFetch(it) }

        capability(
            "attachment.fetch",
            "读取 Laner Chat 附件元数据",
            "返回指定 request 附件的插件持有元数据。影子版不主动读取文件正文。",
            read,
            listOf(
                parameter("request_id", description = "请求 ID"),
                parameter("attachment_id", description = "附件 ID")
            )
        ) { controller.attachmentFetch(it) }

        capability(
            "turn.status",
            "读取 Laner Chat Assistant Turn",
            "读取当前 Assistant Turn 与可 claim 消息计数，不返回正文。",
            read,
            listOf(parameter("session_id", description = "可选 Session", required = false))
        ) { controller.turnStatus(it) }

        capability(
            "turn.claim",
            "Claim Laner Chat Assistant Turn",
            "按耐久 seq 顺序 claim 一批未决消息；同一 Turn 重试保持幂等。",
            write,
            listOf(
                parameter("session_id", description = "可选 Session", required = false),
                parameter("limit", "integer", "最多 claim 数", false, "50")
            )
        ) { controller.turnClaim(it) }

        capability(
            "turn.reply",
            "完成 Laner Chat Assistant Turn",
            "完成一个 Turn、持久化统一回复，并通过通用 Host 聊天接口发布。",
            write,
            listOf(
                parameter("turn_id", description = "turn.claim 返回的 Turn ID"),
                parameter("reply_id", description = "可选幂等 reply ID", required = false),
                parameter("content", description = "完整回复正文")
            )
        ) { controller.turnReply(it) }

        capability(
            "turn.resolve",
            "无需回复地完成 Laner Chat Turn",
            "把一个活动 Turn 处理为 COMPLETED_NO_REPLY，所覆盖消息离开未决集合。",
            write,
            listOf(parameter("turn_id", description = "活动 Turn ID"))
        ) { controller.turnResolve(it) }

        capability(
            "turn.cancel",
            "停止当前 Laner Chat Turn",
            "取消当前 Turn、保留消息为未决，并暂停调度器。",
            write,
            listOf(parameter("session_id", description = "可选 Session", required = false))
        ) { controller.turnCancel(it) }

        capability(
            "turn.resume",
            "恢复 Laner Chat Turn 调度",
            "恢复暂停的 Assistant Turn Scheduler，不丢弃未决消息。",
            write,
            listOf(parameter("session_id", description = "可选 Session", required = false))
        ) { controller.turnResume(it) }

        capability(
            "reply",
            "旧版单消息 Laner Chat 回复",
            "兼容旧 request_id 单消息 reply；新流程优先使用 Assistant Turn。",
            write,
            listOf(
                parameter("request_id", description = "request ID"),
                parameter("reply_id", description = "可选幂等 reply ID", required = false),
                parameter("content", description = "回复正文")
            )
        ) { controller.legacyReply(it) }

        capability(
            "mailbox.enqueue",
            "向影子 Laner Chat 邮箱写入测试消息",
            "迁移/验收接口。把用户消息写入插件自己的 durable mailbox，不影响当前基座 LanerChat。",
            write,
            listOf(
                parameter(name = "chat_id", description = "关联 chat_id"),
                parameter(name = "text", description = "消息正文", required = false),
                parameter(
                    name = "sender",
                    description = "发送者",
                    required = false,
                    default = LanerChatContract.DEFAULT_SENDER
                ),
                parameter(
                    name = "priority",
                    description = "HIGH/NORMAL/LOW",
                    required = false,
                    default = "NORMAL"
                ),
                parameter(
                    name = "attachments",
                    type = "array",
                    description = "附件元数据数组",
                    required = false
                )

            )
        ) { controller.enqueue(it) }

        capability(
            "mailbox.cancel",
            "取消影子 Laner Chat 请求",
            "取消一个未决 request，保留耐久历史。",
            write,
            listOf(
                parameter("request_id", description = "request ID"),
                parameter("reason", description = "取消原因", required = false)
            )
        ) { controller.cancelRequest(it) }

        capability(
            "proactive.prepare",
            "准备 Laner Chat 主动消息",
            "创建耐久 proactive message。影子版只准备并持久化，不注入主聊天记录。",
            write,
            listOf(
                parameter("session_id", description = "可选 Session", required = false),
                parameter("agent_session_id", description = "可选 Agent Session", required = false),
                parameter("message_id", description = "可选幂等 message ID", required = false),
                parameter("content", description = "主动消息正文")
            )
        ) { controller.proactivePrepare(it) }

        capability(
            "proactive.delivered",
            "标记 Laner Chat 主动消息已投递",
            "迁移适配层在真正写入主聊天记录后调用。",
            write,
            listOf(parameter("message_id", description = "proactive message ID"))
        ) { controller.proactiveDelivered(it) }

        host.registerProvider(
            LANER_CHAT_MODE_PROVIDER_ID,
            LanerChatModeProvider(host) { name, parameters ->
                val operation = checkNotNull(operations[name]) {
                    "Unknown Laner Chat operation: $name"
                }
                operation(JSONObject(parameters.toString()))
            },
            mapOf(
                "kind" to "chat_mode_extension",
                "api" to "1",
                "provider_type_id" to LanerChatContract.PROVIDER_TYPE_ID
            )
        )

        host.logger.i("LanerChat", "Laner Chat shadow plugin mounted")
        return InProcessPluginHandle {
            attentionJob.cancel()
            host.logger.i("LanerChat", "Laner Chat shadow plugin stopped")
        }
    }

    private suspend fun publishAttention(
        host: InProcessPluginHost,
        summary: LanerChatAttentionSummary
    ) {
        if (summary.isEmpty) {
            val cleared =
                JSONObject(
                    host.invokeHostCapability(
                        "host.attention@1",
                        JSONObject().put("operation", "clear").toString()
                    )
                )
            check(cleared.optBoolean("success")) { "Host Attention clear failed: $cleared" }
            return
        }

        val groups = JSONArray()
        addAttentionGroup(groups, "unread", "未读", summary.unread)
        addAttentionGroup(groups, "pending", "未处理", summary.pending)

        val published =
            JSONObject(
                host.invokeHostCapability(
                    "host.attention@1",
                    JSONObject()
                        .put("operation", "publish")
                        .put("label", "Laner Chat")
                        .put("groups", groups)
                        .toString()
                )
            )
        check(published.optBoolean("success")) { "Host Attention publish failed: $published" }
    }

    private fun addAttentionGroup(
        groups: JSONArray,
        id: String,
        label: String,
        counts: LanerChatPriorityCounts
    ) {
        val items = JSONArray()
        if (counts.high > 0) {
            items.put(attentionItem("urgent", "紧急", counts.high, "danger"))
        }
        if (counts.normal > 0) {
            items.put(attentionItem("normal", "普通", counts.normal, "info"))
        }
        if (counts.low > 0) {
            items.put(attentionItem("later", "稍后", counts.low, "success"))
        }
        if (items.length() == 0) return
        groups.put(
            JSONObject()
                .put("id", id)
                .put("label", label)
                .put("items", items)
        )
    }

    private fun attentionItem(
        id: String,
        label: String,
        count: Int,
        semanticTone: String
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("label", label)
            .put("count", count)
            .put("semantic_tone", semanticTone)
}
