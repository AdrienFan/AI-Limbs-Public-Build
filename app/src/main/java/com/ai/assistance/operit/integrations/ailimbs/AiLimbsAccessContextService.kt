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
                "- ubuntu.status/start/stop 与 AI Limbs 文档工具通过 shell=operit 调用。"
            )
            appendLine("- 所有 Operit 调用继续遵守 ALLOW / ASK / FORBID 权限语义。")
            appendLine()
            appendLine("[AI Limbs user access prompt]")
            append(editablePrompt.ifBlank { "(empty)" })
        }
    }
}
