package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context

/**
 * Builds provider-neutral AI Limbs access context.
 *
 * Runtime invariants live in this compiled system section instead of the editable access prompt.
 * Bridge providers transport the completed context without owning its policy.
 */
class AiLimbsAccessContextService(context: Context) {
    private val documents = AiLimbsDocumentProvider(context.applicationContext)

    suspend fun readAccessContext(): String {
        val editablePrompt = documents.readAccessPrompt()
        return buildString {
            appendLine("[AI Limbs system access policy]")
            appendLine("- 模块：AI Limbs Capability Resolver。")
            appendLine("- Provider：ai_limbs_core；能力协议版本：1。")
            appendLine(
                "- 能力查找调用：" +
                    "{\"name\":\"capability.search\",\"parameters\":{\"query\":\"想完成的任务\",\"limit\":5}}。"
            )
            appendLine(
                "- 能力详情调用：" +
                    "{\"name\":\"capability.describe\",\"parameters\":{\"capability_id\":\"搜索返回的 ID\"}}。"
            )
            appendLine(
                "- 需要 AI Limbs 能力但不知道调用地址时，必须先调用 capability.search，" +
                    "不得猜测工具名；需要参数、权限或前置条件时再调用 capability.describe。"
            )
            appendLine("- Capability Resolver 只负责发现与描述；真实执行仍走原 ToolPermissionSystem 权限链。")
            appendLine(
                "- AI Limbs Core 状态：" +
                    "{\"name\":\"ai_limbs.core.status\",\"parameters\":{}}；" +
                    "Dispatcher 状态：" +
                    "{\"name\":\"ai_limbs.dispatcher.status\",\"parameters\":{}}。"
            )
            appendLine(
                "- 兰儿 Ubuntu 只读共享窗口状态：" +
                    "{\"name\":\"ai_limbs.ubuntu.share.status\",\"parameters\":{}}。"
            )
            appendLine("- 模块：AI Limbs Laner Chat Bridge；正式调用地址前缀：ai_limbs.chat.*。")
            appendLine(
                "- 所有 ai_limbs.chat.* 调用均使用 start_process 的 shell=operit 与结构化 JSON；" +
                    "例如 {\"name\":\"ai_limbs.chat.status\",\"parameters\":{}}。"
            )
            appendLine(
                "- 聊天桥状态：" +
                    "{\"name\":\"ai_limbs.chat.status\",\"parameters\":{}}；" +
                    "接入或恢复会话：" +
                    "{\"name\":\"ai_limbs.chat.session.open\",\"parameters\":{}}；" +
                    "完成接入时可调用 ai_limbs.chat.session.close。"
            )
            appendLine(
                "- 工作过程中在自然检查点调用 ai_limbs.chat.notification.check；空闲时可调用 " +
                    "ai_limbs.chat.notification.wait，单次等待最长 30 秒，不得无限等待。"
            )
            appendLine(
                "- notification.check/wait 只返回 unread_count、pending_reply_count 与 latest_seq，" +
                    "绝不包含正文；只有决定阅读时才调用 ai_limbs.chat.inbox.fetch。"
            )
            appendLine(
                "- inbox.fetch 返回的 DELIVERED 但未 ANSWERED 消息仍可再次读取；使用其 request_id " +
                    "调用 ai_limbs.chat.reply，重试时复用稳定 reply_id，禁止重复发送不同回复。"
            )
            appendLine(
                "- 需要主动联系阿伟且不依赖用户 request_id 时，调用 " +
                    "{\"name\":\"ai_limbs.chat.send\",\"parameters\":{" +
                    "\"message_id\":\"本次消息的稳定 ID\",\"content\":\"完整消息\"}}；" +
                    "重试必须复用相同 message_id 与 content。"
            )
            appendLine(
                "- Laner Chat 是当前兰儿任务旁路邮箱，不是模型 Provider：不得把消息转发给 OpenAI、" +
                    "DeepSeek、本地模型或 ToolPkg，不得猜测或使用 chat.exchange。"
            )
            appendLine(
                "- ubuntu.stop 带并发保护：若 Ubuntu 命令终端仍由用户打开，或另一个隐藏 Ubuntu " +
                    "操作仍在运行，关机会被拒绝；不得绕过该保护直接杀进程。"
            )
            appendLine(
                "- 当任务涉及开发、调试、开发环境管理或会改变项目/设备内容时，" +
                    "先调用 ai_limbs.work_manual.read。"
            )
            appendLine(
                "- 只做代码分析、云端构建状态处理以及非开发任务时，无需读取工作手册。"
            )
            appendLine(
                "- 工作手册读取调用：" +
                    "{\"name\":\"ai_limbs.work_manual.read\",\"parameters\":{}}。"
            )
            appendLine("- 工作手册的固定入口与工具手册入口由系统生成，不得另建同类手册。")
            appendLine(
                "- 更新 AI Limbs 文档必须调用对应的 ai_limbs.*.write，禁止绕过版本库直接覆盖文件。"
            )
            appendLine("- 需要 Ubuntu 时先调用 ubuntu.status；STOPPED 时调用 ubuntu.start。")
            appendLine(
                "- 普通 start_process 不会自动启动已停止的 Ubuntu；收到 \"Ubuntu is " +
                    "stopped. Call ubuntu.start first.\" 后应先显式启动。"
            )
            appendLine(
                "- 连续任务仍依赖 Ubuntu 时保持运行；任务结束且没有后续依赖时调用 " +
                    "ubuntu.stop。"
            )
            appendLine(
                "- 查询空闲策略调用 " +
                    "{\"name\":\"ubuntu.idle.get\",\"parameters\":{}}；修改时调用 " +
                    "ubuntu.idle.set，mode 可选 KEEP_RUNNING、MINUTES_10、MINUTES_15、" +
                    "MINUTES_30、MINUTES_60、CUSTOM。"
            )
            appendLine(
                "- ubuntu.idle.set 的 CUSTOM 模式必须提供 custom_minutes（1–1440）；" +
                    "例如 {\"name\":\"ubuntu.idle.set\",\"parameters\":" +
                    "{\"mode\":\"CUSTOM\",\"custom_minutes\":20}}。"
            )
            appendLine(
                "- ubuntu.status/start/stop/idle.get/idle.set 与 AI Limbs 文档工具通过 " +
                    "shell=operit 调用。"
            )
            appendLine(
                "- 操作手机界面前先调用 " +
                    "{\"name\":\"ai_limbs.ui.status\",\"parameters\":{}}；" +
                    "direct_ui_ready=false 时按 next_action 请求用户完成授权，不得假定页面结构与触控已经可用。"
            )
            appendLine(
                "- direct_ui_ready=true 后可使用 get_page_info、click_element、tap、swipe、" +
                    "set_input_text 与 press_key；ui_subagent_ready=true 后才调用视觉子代理。"
            )
            appendLine("- 所有 Operit 调用继续遵守 ALLOW / ASK / FORBID 权限语义。")
            appendLine()
            appendLine("[AI Limbs user access prompt]")
            append(editablePrompt.ifBlank { "(empty)" })
        }
    }
}
