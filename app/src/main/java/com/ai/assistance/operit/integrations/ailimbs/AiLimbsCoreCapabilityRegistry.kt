package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogSourceKind
import com.ai.assistance.operit.data.model.ToolParameterSchema

/**
 * Single registration source for capabilities implemented directly by AI Limbs.
 *
 * Discovery metadata, invoke aliases, provider identity, availability policy, and executable route
 * live in the same registration so first-party capabilities cannot drift across parallel tables.
 */
object AiLimbsCoreCapabilityRegistry {
    const val CORE_PROVIDER = "ai_limbs_core"
    const val BRIDGE_PROVIDER = "ai_limbs_bridge"
    const val UBUNTU_PROVIDER = "ubuntu"

    private val registrations: List<AiLimbsCoreCapabilityRegistration> = listOf(
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.CAPABILITY_SEARCH),
            catalogEntry =
            entry(
                        name = "capability.search",
                        displayName = "AI Limbs Capability Resolver · 能力搜索",
                        description = "Search the current AI Limbs capability catalog without executing a capability.",
                        parameters = listOf(
                            ToolParameterSchema("query", "string", "Capability intent or known tool or module name", true),
                            ToolParameterSchema("limit", "integer", "Maximum result count from 1 to 5", false, "5")
                        ),
                        keywords = listOf("能力", "查找工具", "resolver", "capability resolver", "AI Limbs Core")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.CAPABILITY_DESCRIBE),
            catalogEntry =
            entry(
                        name = "capability.describe",
                        displayName = "AI Limbs Capability Resolver · 能力详情",
                        description = "Describe one capability, including schema, permissions, prerequisites, and invocation address.",
                        parameters = listOf(
                            ToolParameterSchema("capability_id", "string", "ID returned by capability.search", true)
                        ),
                        keywords = listOf("能力详情", "参数", "schema", "调用地址", "AI Limbs Core")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.DEVELOPER_CATALOG_READ),
            capabilityId = "ai_limbs.developer.catalog.read",
            capabilityAliases = listOf("developer.catalog", "developer.catalog.read"),
            catalogEntry =
                entry(
                    name = "ai_limbs.developer.catalog.read",
                    displayName = "AI Limbs 开发接口目录",
                    description = "Read the current Host Primitive and extension\/plugin-bus development catalog when the Plugin Center eye switch is open.",
                    parameters = listOf(
                        ToolParameterSchema("query", "string", "Optional filter such as filesystem, Extension Point, 通知 or bridge", false)
                    ),
                    keywords = listOf("开发接口", "Host Primitive", "Extension Point", "Plugin Bus", "接口目录", "developer catalog")
                )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.CORE_STATUS),
            capabilityId = "ai_limbs.core.status",
            capabilityAliases = listOf("core.status"),
            catalogEntry =
            entry(
                        name = "ai_limbs.core.status",
                        displayName = "AI Limbs Core 状态",
                        description = "Read the AI Limbs core version and its registered first-party modules.",
                        keywords = listOf("AI Limbs Core", "核心", "core module", "核心模块")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.DISPATCHER_STATUS),
            capabilityId = "ai_limbs.dispatcher.status",
            capabilityAliases = listOf("dispatcher.status"),
            catalogEntry =
            entry(
                        name = "ai_limbs.dispatcher.status",
                        displayName = "AI Limbs Tool Dispatcher 状态",
                        description = "Describe the AI Limbs dispatcher route and its enforced Operit permission chain.",
                        keywords = listOf("dispatcher", "分发器", "调度器", "权限链", "AI Limbs Core")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.POLICY_DESCRIBE),
            capabilityId = "ai_limbs.policy.describe",
            catalogEntry =
                entry(
                    name = "ai_limbs.policy.describe",
                    displayName = "AI Limbs 统一执行政策",
                    description = "Read the generated, transport-neutral AI Limbs execution policy and Chinese explanation.",
                    keywords = listOf("执行政策", "policy engine", "ALLOW", "ASK", "FORBID")
                )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.POLICY_SESSION_RESET),
            capabilityId = "ai_limbs.policy.session.reset",
            catalogEntry =
                entry(
                    name = "ai_limbs.policy.session.reset",
                    displayName = "重置 AI Limbs 上下文收据",
                    description = "Clear managed-document receipts only at an explicit model-context boundary.",
                    keywords = listOf("上下文边界", "session receipt", "context reset")
                )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.ACCESS_CONTEXT_READ),
            catalogEntry =
            entry(
                        name = "ai_limbs.access_context.read",
                        displayName = "AI Limbs 接入 Bootstrap",
                        description = "Read the minimal AI Limbs access bootstrap with official managed-prompt references and current versions.",
                        keywords = listOf("接入", "bootstrap", "access context", "prompt reference")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.ManagedDocumentRead(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT),
            catalogEntry =
            entry(
                        name = "ai_limbs.system_access_prompt.read",
                        displayName = "读取系统接入提示",
                        description = "Read the current official AI Limbs system access prompt, including its content version and managed document path.",
                        keywords = listOf("系统接入提示", "system access prompt", "system prompt", "managed document")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.ManagedDocumentRead(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT),
            invokeAliases = listOf("ai_limbs.access_prompt.read", "laner.access_prompt.read"),
            catalogEntry =
            entry(
                        name = "ai_limbs.custom_access_prompt.read",
                        displayName = "读取自定义接入提示",
                        description = "Read the current user-editable AI Limbs custom access prompt and its content version.",
                        keywords = listOf("自定义接入提示", "custom access prompt", "user prompt")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.ManagedDocumentWrite(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT),
            invokeAliases = listOf("ai_limbs.access_prompt.write", "laner.access_prompt.write"),
            catalogEntry =
            entry(
                        name = "ai_limbs.custom_access_prompt.write",
                        displayName = "保存自定义接入提示",
                        description = "Save the user-editable AI Limbs custom access prompt through the managed-document history path.",
                        parameters = listOf(ToolParameterSchema("content", "string", "Complete custom access prompt body", true)),
                        keywords = listOf("自定义接入提示", "custom access prompt", "保存接入提示")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.ManagedDocumentRead(AiLimbsDocumentId.WORK_MANUAL),
            invokeAliases = listOf("laner.work_manual.read"),
            catalogEntry =
            entry("ai_limbs.work_manual.read", "读取工作手册", "Read the protected AI Limbs work manual.")
        ),
        registration(
            route = AiLimbsCoreRoute.ManagedDocumentWrite(AiLimbsDocumentId.WORK_MANUAL),
            invokeAliases = listOf("laner.work_manual.write"),
            catalogEntry =
            entry(
                        "ai_limbs.work_manual.write",
                        "保存工作手册",
                        "Save the editable body of the protected AI Limbs work manual.",
                        listOf(ToolParameterSchema("content", "string", "Complete editable manual body", true))
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.STORAGE_SEARCH),
            catalogEntry =
                entry(
                    name = "ai_limbs.storage.search",
                    displayName = "搜索 AI Limbs 持久产物",
                    description = "Search the durable artifact index by owner, project, purpose, source, or canonical path.",
                    parameters = listOf(
                        ToolParameterSchema("query", "string", "Optional text query", false),
                        ToolParameterSchema("project_id", "string", "Optional exact project filter", false),
                        ToolParameterSchema("limit", "integer", "Maximum results from 1 to 100", false, "20")
                    ),
                    keywords = listOf("持久产物", "文件归属", "恢复索引", "storage search")
                )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.STORAGE_DESCRIBE),
            catalogEntry =
                entry(
                    name = "ai_limbs.storage.describe",
                    displayName = "读取 AI Limbs 产物归属",
                    description = "Describe one durable artifact by its stable artifact_id.",
                    parameters = listOf(
                        ToolParameterSchema("artifact_id", "string", "Stable artifact identifier", true)
                    ),
                    keywords = listOf("artifact id", "文件地址", "logical owner")
                )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.STORAGE_PROJECT_FILES),
            catalogEntry =
                entry(
                    name = "ai_limbs.storage.project.files",
                    displayName = "列出项目持久文件",
                    description = "List canonical durable files owned by one project, optionally filtered by logical owner.",
                    parameters = listOf(
                        ToolParameterSchema("project_id", "string", "Stable project identifier", true),
                        ToolParameterSchema("logical_owner", "string", "Optional exact logical owner", false)
                    ),
                    keywords = listOf("项目文件", "canonical path", "恢复", "project files")
                )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.UI_STATUS),
            catalogEntry =
            entry("ai_limbs.ui.status", "AI Limbs 视觉与触觉状态", "Read live AI Limbs UI and visual-control readiness.")
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.SHARED_UBUNTU_STATUS),
            capabilityId = "ubuntu.share.status",
            capabilityAliases = listOf("ai.ubuntu.share.status"),
            catalogEntry =
            entry(
                        "ai_limbs.ubuntu.share.status",
                        "兰儿 Ubuntu 共享窗口状态",
                        "Read shared Ubuntu activity and participant counts without returning command or output content.",
                        keywords = listOf("共享窗口", "眼睛", "只读", "兰儿操作", "Ubuntu share")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.ForwardHostTool,
            capabilityId = "bridge.reconnect",
            capabilityAliases = listOf("rdc.reconnect", "ai.bridge.reconnect"),
            provider = AiLimbsCoreProvider.BRIDGE,
            availabilityPolicy = AiLimbsCoreAvailabilityPolicy.BRIDGE_RECONNECT,
            catalogEntry =
            bridgeEntry(
                        name = "ai_limbs.bridge.reconnect",
                        displayName = "请求 AI Limbs Bridge 重新连接",
                        description =
                            "Safely schedule the active Bridge provider's normal reconnect after returning the current tool response. Existing pairing and session credentials are preserved.",
                        keywords = listOf("Bridge", "RDC", "重连", "重新连接", "连接恢复", "reconnect")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.STATUS),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.status",
                        displayName = "AI Limbs Laner Chat Bridge 状态",
                        description =
                            "Read Laner Chat mailbox, active-session, priority, unread, pending-reply, and Bridge readiness metadata without returning message bodies.",
                        keywords = listOf("兰儿聊天", "Laner Chat", "聊天桥", "收件箱", "未读消息")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.SESSION_OPEN),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.session.open",
                        displayName = "打开或恢复兰儿聊天会话",
                        description =
                            "Open the active Laner Chat session, resume a specified session, or create one when none exists.",
                        parameters = listOf(
                            ToolParameterSchema(
                                "session_id",
                                "string",
                                "Optional existing Laner Chat session ID to resume",
                                false
                            ),
                            ToolParameterSchema(
                                "agent_session_id",
                                "string",
                                "Optional identifier for the current assistant task",
                                false
                            )
                        ),
                        keywords = listOf("兰儿聊天", "session", "会话恢复", "resume", "Laner Chat")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.SESSION_CLOSE),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.session.close",
                        displayName = "关闭兰儿聊天会话",
                        description =
                            "Close a specified Laner Chat session, or the currently active session when omitted.",
                        parameters = listOf(
                            ToolParameterSchema(
                                "session_id",
                                "string",
                                "Optional Laner Chat session ID; defaults to the active session",
                                false
                            )
                        ),
                        keywords = listOf("兰儿聊天", "session", "关闭会话", "Laner Chat")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.NOTIFICATION_CHECK),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.notification.check",
                        displayName = "检查兰儿聊天通知",
                        description =
                            "Check for unanswered Laner Chat messages after a cursor; returns sequence and HIGH/NORMAL/LOW priority counts only, never message bodies.",
                        parameters = listOf(
                            ToolParameterSchema(
                                "after_seq",
                                "integer",
                                "Return notification metadata for unanswered requests after this sequence; defaults to 0",
                                false,
                                "0"
                            ),
                            ToolParameterSchema(
                                "session_id",
                                "string",
                                "Optional session filter",
                                false
                            )
                        ),
                        keywords = listOf("兰儿聊天", "通知", "新消息", "unread", "notification")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.NOTIFICATION_WAIT),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.notification.wait",
                        displayName = "短时等待兰儿聊天通知",
                        description =
                            "Wait for Laner Chat notification metadata for at most 30 seconds; returns new_message or idle plus priority counts, never message bodies.",
                        parameters = listOf(
                            ToolParameterSchema(
                                "after_seq",
                                "integer",
                                "Return notification metadata for unanswered requests after this sequence; defaults to 0",
                                false,
                                "0"
                            ),
                            ToolParameterSchema(
                                "timeout_seconds",
                                "integer",
                                "Bounded wait duration in seconds; values above 30 are clamped",
                                false,
                                "25"
                            ),
                            ToolParameterSchema(
                                "session_id",
                                "string",
                                "Optional session filter",
                                false
                            )
                        ),
                        keywords = listOf("兰儿聊天", "等待消息", "bounded wait", "notification", "空闲")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.INBOX_FETCH),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.inbox.fetch",
                        displayName = "读取兰儿聊天收件箱",
                        description =
                            "Explicitly fetch unanswered Laner Chat message bodies and mark them delivered; delivered but unanswered messages remain fetchable.",
                        parameters = listOf(
                            ToolParameterSchema(
                                "session_id",
                                "string",
                                "Optional session filter; defaults to the active session",
                                false
                            ),
                            ToolParameterSchema(
                                "request_id",
                                "string",
                                "Optional exact unanswered request ID",
                                false
                            ),
                            ToolParameterSchema(
                                "after_seq",
                                "integer",
                                "Fetch unanswered requests after this sequence when request_id is omitted",
                                false,
                                "0"
                            ),
                            ToolParameterSchema(
                                "limit",
                                "integer",
                                "Maximum messages to fetch from 1 to 20",
                                false,
                                "10"
                            ),
                            ToolParameterSchema(
                                "priority",
                                "string",
                                "Optional HIGH, NORMAL, or LOW filter; omit to preserve sequence order across all priorities",
                                false
                            )
                        ),
                        keywords = listOf("兰儿聊天", "收件箱", "读取正文", "inbox", "fetch")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.ATTACHMENT_FETCH),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.attachment.fetch",
                        displayName = "读取兰儿聊天附件",
                        description =
                            "Fetch one attachment from a previously fetched Laner Chat request. Image responses include file_path for the RDC read_file multimodal handoff; text and documents use the native file reader.",
                        parameters = listOf(
                            ToolParameterSchema("request_id", "string", "Request ID returned by inbox.fetch", true),
                            ToolParameterSchema("attachment_id", "string", "Attachment ID returned in the message attachments array", true)
                        ),
                        keywords = listOf("兰儿聊天", "附件", "图片", "文件", "attachment", "multimodal")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.TURN_STATUS),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.turn.status",
                        displayName = "查询 Laner Chat Assistant Turn 状态",
                        description =
                            "Read the AI Limbs-managed Assistant Turn scheduler state without returning message bodies.",
                        parameters = listOf(
                            ToolParameterSchema("session_id", "string", "Optional Laner Chat session filter", false)
                        ),
                        keywords = listOf("Laner Chat", "Assistant Turn", "scheduler", "调度", "状态")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.TURN_CLAIM),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.turn.claim",
                        displayName = "领取下一批 Laner Chat 用户消息",
                        description =
                            "Atomically claim the current eligible Laner Chat message snapshot as one Assistant Turn. Existing active turns are returned idempotently; messages arriving after claim remain pending for the next turn.",
                        parameters = listOf(
                            ToolParameterSchema("session_id", "string", "Optional Laner Chat session; defaults to active", false),
                            ToolParameterSchema("limit", "integer", "Maximum messages to claim from 1 to 50", false, "50")
                        ),
                        keywords = listOf("Laner Chat", "Assistant Turn", "claim", "batch", "批量消息", "scheduler")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.TURN_REPLY),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.turn.reply",
                        displayName = "完成 Laner Chat Assistant Turn",
                        description =
                            "Atomically complete one Assistant Turn, mark all covered user requests answered, and deliver one idempotent user-visible assistant reply to the bound Bridge Chat.",
                        parameters = listOf(
                            ToolParameterSchema("turn_id", "string", "Active Assistant Turn ID returned by turn.claim", true),
                            ToolParameterSchema("reply_id", "string", "Optional stable reply ID for retry idempotence", false),
                            ToolParameterSchema("content", "string", "Complete assistant reply text for the whole turn", true)
                        ),
                        keywords = listOf("Laner Chat", "Assistant Turn", "reply", "covered requests", "批量回复", "幂等")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.TURN_RESOLVE),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.turn.resolve",
                        displayName = "无需回复地完成 Laner Chat Assistant Turn",
                        description =
                            "Complete one active Assistant Turn without sending a user-visible reply. All covered requests become resolved-no-reply and leave the unresolved attention set.",
                        parameters = listOf(
                            ToolParameterSchema("turn_id", "string", "Active Assistant Turn ID returned by turn.claim", true)
                        ),
                        keywords = listOf("Laner Chat", "Assistant Turn", "resolve", "no reply", "无需回复", "处理完成")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.TURN_CANCEL),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.turn.cancel",
                        displayName = "停止当前 Laner Chat Assistant Turn",
                        description =
                            "Cancel only the current Assistant Turn and pause the scheduler while preserving all covered user messages as unresolved.",
                        parameters = listOf(
                            ToolParameterSchema("session_id", "string", "Optional Laner Chat session; defaults to active", false)
                        ),
                        keywords = listOf("Laner Chat", "Assistant Turn", "cancel", "停止", "暂停调度", "保留消息")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.TURN_RESUME),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.turn.resume",
                        displayName = "恢复 Laner Chat Assistant Turn 调度",
                        description =
                            "Resume the AI Limbs-managed Laner Chat scheduler after a user stop without discarding unresolved messages.",
                        parameters = listOf(
                            ToolParameterSchema("session_id", "string", "Optional Laner Chat session; defaults to active", false)
                        ),
                        keywords = listOf("Laner Chat", "Assistant Turn", "resume", "继续处理", "scheduler")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.LEGACY_REPLY),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.reply",
                        displayName = "回复兰儿聊天消息",
                        description =
                            "Legacy single-request reply compatibility path. Assistant Turn clients should use ai_limbs.chat.turn.reply or ai_limbs.chat.turn.resolve.",
                        parameters = listOf(
                            ToolParameterSchema("request_id", "string", "Request ID returned by inbox.fetch", true),
                            ToolParameterSchema(
                                "reply_id",
                                "string",
                                "Optional stable reply ID used for retry idempotence",
                                false
                            ),
                            ToolParameterSchema("content", "string", "Complete reply text", true)
                        ),
                        keywords = listOf("兰儿聊天", "回复", "reply", "request_id", "幂等")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.SEND),
            catalogEntry =
            lanerChatEntry(
                        name = "ai_limbs.chat.send",
                        displayName = "主动发送兰儿聊天消息",
                        description =
                            "Send an AI-originated message without a user request ID; automatically open a Laner session and bootstrap a dedicated Bridge Chat when needed.",
                        parameters = listOf(
                            ToolParameterSchema(
                                "session_id",
                                "string",
                                "Optional Laner Chat session; defaults to or automatically creates the active session",
                                false
                            ),
                            ToolParameterSchema(
                                "message_id",
                                "string",
                                "Optional stable message ID used for retry idempotence",
                                false
                            ),
                            ToolParameterSchema("content", "string", "Complete proactive message text", true)
                        ),
                        keywords = listOf("兰儿聊天", "主动消息", "主动发送", "proactive", "send", "幂等")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.HOST_TOOLS_LIST),
            invokeAliases = listOf("operit.tools.list"),
            catalogEntry =
            entry("ai_limbs.host_tools.list", "AI Limbs Host Tools Registry", "List currently registered host tool names available through AI Limbs.", keywords = listOf("registry", "dispatcher", "host tools", "工具注册表"))
        ),
        registration(
            route = AiLimbsCoreRoute.Local(AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE),
            invokeAliases = listOf("operit.tool.execute"),
            catalogEntry =
                entry(
                    name = "ai_limbs.host_tool.execute",
                    displayName = "AI Limbs Host Tool Executor",
                    description = "Execute one registered host tool through the existing ToolPermissionSystem permission chain.",
                    parameters = listOf(
                        ToolParameterSchema("name", "string", "Registered host tool name", true),
                        ToolParameterSchema("parameters", "object", "Host tool parameter object", false)
                    ),
                    keywords = listOf("host tool", "dispatcher", "permission", "execute", "工具执行")
                )
        ),
        registration(
            route = AiLimbsCoreRoute.ForwardHostTool,
            capabilityId = "ubuntu.status",
            provider = AiLimbsCoreProvider.UBUNTU,
            availabilityPolicy = AiLimbsCoreAvailabilityPolicy.UBUNTU_STATUS,
            capabilityAliases = listOf("ubuntu.lifecycle.status"),
            catalogEntry =
            ubuntuEntry("ubuntu.status", "查询 Ubuntu 状态", "Read the lifecycle state of the AI Limbs Ubuntu sandbox.", listOf("Ubuntu", "Linux", "沙箱", "生命周期"))
        ),
        registration(
            route = AiLimbsCoreRoute.ForwardHostTool,
            capabilityId = "ubuntu.start",
            provider = AiLimbsCoreProvider.UBUNTU,
            availabilityPolicy = AiLimbsCoreAvailabilityPolicy.UBUNTU_START,
            capabilityAliases = listOf("ubuntu.lifecycle.start"),
            catalogEntry =
            ubuntuEntry("ubuntu.start", "启动 Ubuntu", "Start the AI Limbs Ubuntu sandbox.", listOf("Ubuntu", "Linux", "开机", "启动沙箱"))
        ),
        registration(
            route = AiLimbsCoreRoute.ForwardHostTool,
            capabilityId = "ubuntu.stop",
            provider = AiLimbsCoreProvider.UBUNTU,
            availabilityPolicy = AiLimbsCoreAvailabilityPolicy.UBUNTU_STOP,
            capabilityAliases = listOf("ubuntu.lifecycle.stop"),
            catalogEntry =
            ubuntuEntry(
                        "ubuntu.stop",
                        "停止 Ubuntu",
                        "Stop the AI Limbs Ubuntu sandbox unless another UI user or AI operation is active.",
                        listOf("Ubuntu", "Linux", "关机", "停止沙箱", "其他用户", "并发保护")
                    )
        ),
        registration(
            route = AiLimbsCoreRoute.ForwardHostTool,
            capabilityId = "ubuntu.idle.get",
            provider = AiLimbsCoreProvider.UBUNTU,
            availabilityPolicy = AiLimbsCoreAvailabilityPolicy.UBUNTU_IDLE_POLICY,
            catalogEntry =
            ubuntuIdleEntry("ubuntu.idle.get", "查询 Ubuntu 空闲策略", "Read the Ubuntu idle auto-stop policy.", listOf("Ubuntu", "自动关机", "空闲时间"))
        ),
        registration(
            route = AiLimbsCoreRoute.ForwardHostTool,
            capabilityId = "ubuntu.idle.set",
            provider = AiLimbsCoreProvider.UBUNTU,
            availabilityPolicy = AiLimbsCoreAvailabilityPolicy.UBUNTU_IDLE_POLICY,
            catalogEntry =
            ubuntuIdleEntry(
                        "ubuntu.idle.set",
                        "修改 Ubuntu 空闲策略",
                        "Change the Ubuntu idle auto-stop policy.",
                        listOf("Ubuntu", "自动关机", "保持开机", "自定义时间"),
                        listOf(
                            ToolParameterSchema("mode", "string", "KEEP_RUNNING, MINUTES_10, MINUTES_15, MINUTES_30, MINUTES_60, or CUSTOM", true),
                            ToolParameterSchema("custom_minutes", "integer", "Required for CUSTOM; allowed range is 1 to 1440 minutes", false)
                        )
                    )
        )
    )

    private val registrationsByInvokeName: Map<String, AiLimbsCoreCapabilityRegistration> =
        buildMap {
            registrations.forEach { registration ->
                val canonical = registration.catalogEntry.targetToolName
                check(put(canonical, registration) == null) {
                    "Duplicate AI Limbs core invoke name: $canonical"
                }
                registration.invokeAliases.forEach { alias ->
                    check(put(alias, registration) == null) {
                        "Duplicate AI Limbs core invoke alias: $alias"
                    }
                }
            }
        }

    init {
        check(registrations.map { it.catalogEntry.targetToolName }.distinct().size == registrations.size) {
            "Duplicate AI Limbs core capability registration"
        }
    }

    fun entries(): List<ToolCatalogEntry> = registrations.map { it.catalogEntry }

    fun registeredToolNames(): List<String> = registrations.map { it.catalogEntry.targetToolName }

    internal fun registrationSnapshot(): List<AiLimbsCoreCapabilityRegistration> = registrations.toList()

    internal fun registrationForInvokeName(name: String): AiLimbsCoreCapabilityRegistration? =
        registrationsByInvokeName[name]

    internal fun isRegisteredInvokeName(name: String): Boolean = name in registrationsByInvokeName

    internal fun invokeNameForLocalOperation(operation: AiLimbsCoreLocalOperation): String =
        checkNotNull(
            registrations.firstOrNull {
                it.route == AiLimbsCoreRoute.Local(operation)
            }?.catalogEntry?.targetToolName
        ) { "No AI Limbs core capability is registered for local operation: $operation" }

    internal fun managedDocumentInvokeNames(
        documentId: AiLimbsDocumentId,
        write: Boolean
    ): Set<String> {
        val registration = managedDocumentRegistration(documentId, write) ?: return emptySet()
        return buildSet {
            add(registration.catalogEntry.targetToolName)
            addAll(registration.invokeAliases)
        }
    }

    internal fun managedDocumentInvokeName(documentId: AiLimbsDocumentId, write: Boolean): String? =
        managedDocumentRegistration(documentId, write)?.catalogEntry?.targetToolName

    private fun managedDocumentRegistration(
        documentId: AiLimbsDocumentId,
        write: Boolean
    ): AiLimbsCoreCapabilityRegistration? =
        registrations.firstOrNull { registration ->
            when (val route = registration.route) {
                is AiLimbsCoreRoute.ManagedDocumentRead -> !write && route.documentId == documentId
                is AiLimbsCoreRoute.ManagedDocumentWrite -> write && route.documentId == documentId
                else -> false
            }
        }

    /** Merge registered metadata over runtime entries while keeping third-party catalog entries. */
    fun mergeInto(runtimeCatalog: List<ToolCatalogEntry>): List<ToolCatalogEntry> {
        val catalogEntries = entries()
        val registrationsByName = catalogEntries.associateBy { it.targetToolName }
        val mergedNames = linkedSetOf<String>()
        val merged = runtimeCatalog.map { runtime ->
            val registered = registrationsByName[runtime.targetToolName]
            if (registered == null) {
                runtime
            } else {
                mergedNames += registered.targetToolName
                registered.copy(
                    keywords = (runtime.keywords + registered.keywords).distinct(),
                    sourceEnabled = runtime.sourceEnabled,
                    inputSchema = runtime.inputSchema ?: registered.inputSchema
                )
            }
        }
        return merged + catalogEntries.filterNot { mergedNames.contains(it.targetToolName) }
    }

    private fun registration(
        route: AiLimbsCoreRoute,
        catalogEntry: ToolCatalogEntry,
        invokeAliases: List<String> = emptyList(),
        capabilityId: String? = null,
        capabilityAliases: List<String> = emptyList(),
        provider: AiLimbsCoreProvider = AiLimbsCoreProvider.CORE,
        availabilityPolicy: AiLimbsCoreAvailabilityPolicy = AiLimbsCoreAvailabilityPolicy.DEFAULT
    ) = AiLimbsCoreCapabilityRegistration(
        catalogEntry = catalogEntry,
        route = route,
        invokeAliases = invokeAliases,
        capabilityId = capabilityId,
        capabilityAliases = capabilityAliases,
        provider = provider,
        availabilityPolicy = availabilityPolicy
    )

    private fun ubuntuEntry(
        name: String,
        displayName: String,
        description: String,
        keywords: List<String>,
        parameters: List<ToolParameterSchema> = emptyList()
    ): ToolCatalogEntry = entry(
        name = name,
        displayName = displayName,
        description = description,
        parameters = parameters,
        keywords = keywords,
        sourceName = UBUNTU_PROVIDER,
        sourceLocator = "ubuntu://lifecycle/${name.substringAfterLast('.')}"
    )

    private fun ubuntuIdleEntry(
        name: String,
        displayName: String,
        description: String,
        keywords: List<String>,
        parameters: List<ToolParameterSchema> = emptyList()
    ): ToolCatalogEntry = entry(
        name = name,
        displayName = displayName,
        description = description,
        parameters = parameters,
        keywords = keywords,
        sourceName = UBUNTU_PROVIDER,
        sourceLocator = "ubuntu://idle/${name.substringAfterLast('.')}"
    )

    private fun bridgeEntry(
        name: String,
        displayName: String,
        description: String,
        keywords: List<String>
    ): ToolCatalogEntry = entry(
        name = name,
        displayName = displayName,
        description = description,
        keywords = keywords,
        sourceName = BRIDGE_PROVIDER,
        sourceLocator = "ai-limbs://bridge/${name.removePrefix("ai_limbs.bridge.")}"
    )

    private fun lanerChatEntry(
        name: String,
        displayName: String,
        description: String,
        parameters: List<ToolParameterSchema> = emptyList(),
        keywords: List<String>
    ): ToolCatalogEntry = entry(
        name = name,
        displayName = displayName,
        description = description,
        parameters = parameters,
        keywords = keywords,
        sourceName = "ai_limbs_laner_chat",
        sourceLocator = "ai-limbs://chat/${name.removePrefix("ai_limbs.chat.")}"
    )

    private fun entry(
        name: String,
        displayName: String,
        description: String,
        parameters: List<ToolParameterSchema> = emptyList(),
        keywords: List<String> = emptyList(),
        sourceName: String = CORE_PROVIDER,
        sourceLocator: String = "ai-limbs://core/$name"
    ) = ToolCatalogEntry(
        targetToolName = name,
        displayName = displayName,
        description = description,
        parameterHints = parameters.map { parameter ->
            "${parameter.name} [${parameter.type}, ${if (parameter.required) "required" else "optional"}]: ${parameter.description}"
        },
        sourceKind = ToolCatalogSourceKind.INTERNAL,
        keywords = keywords,
        parameters = parameters,
        sourceName = sourceName,
        sourceLocator = sourceLocator
    )
}
