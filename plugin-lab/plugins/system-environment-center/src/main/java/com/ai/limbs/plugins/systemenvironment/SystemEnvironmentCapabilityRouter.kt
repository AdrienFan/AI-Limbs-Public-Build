package com.ai.limbs.plugins.systemenvironment

import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityParameterSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilityReceipt
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentCapabilityIds

internal class SystemEnvironmentCapabilityRouter(
    private val registry: SystemEnvironmentSubsystemRegistry
) {
    fun register(host: InProcessPluginHost) {
        SystemEnvironmentCapabilityIds.ALL.forEach { capabilityId ->
            host.registerCapability(
                InProcessCapabilitySpec(
                    id = capabilityId,
                    displayName = displayName(capabilityId),
                    description = "Routes the request to the active system environment; subsystem_id may explicitly override it.",
                    keywords = listOf("system environment", "subsystem"),
                    parameters = listOf(
                        InProcessCapabilityParameterSpec(
                            name = "subsystem_id",
                            description = "Optional active .ailx system environment extension ID override.",
                            required = false
                        )
                    ),
                    effect = effect(capabilityId),
                    domain = InProcessCapabilityDomain.SYSTEM_ENVIRONMENT,
                    workContextRequiredReceipts = receipts(capabilityId),
                    executor = { parametersJson ->
                        registry.invoke(capabilityId, parametersJson)
                    }
                )
            )
        }
    }

    private fun displayName(id: String): String = when (id) {
        SystemEnvironmentCapabilityIds.STATUS -> "查询系统环境状态"
        SystemEnvironmentCapabilityIds.START -> "启动系统环境"
        SystemEnvironmentCapabilityIds.STOP -> "停止系统环境"
        SystemEnvironmentCapabilityIds.IDLE_GET -> "查询空闲策略"
        SystemEnvironmentCapabilityIds.IDLE_SET -> "设置空闲策略"
        SystemEnvironmentCapabilityIds.SESSION_CREATE -> "创建系统环境会话"
        SystemEnvironmentCapabilityIds.SESSION_EXECUTE -> "执行会话命令"
        SystemEnvironmentCapabilityIds.COMMAND -> "执行系统环境命令"
        SystemEnvironmentCapabilityIds.FILESYSTEM -> "访问系统环境文件"
        SystemEnvironmentCapabilityIds.PROCESS -> "管理系统环境进程"
        SystemEnvironmentCapabilityIds.SESSION_INPUT -> "写入会话输入"
        SystemEnvironmentCapabilityIds.SESSION_INTERRUPT -> "中断会话"
        SystemEnvironmentCapabilityIds.SESSION_SCREEN -> "读取会话屏幕"
        SystemEnvironmentCapabilityIds.SESSION_CLOSE -> "关闭会话"
        else -> id
    }

    private fun effect(id: String): InProcessCapabilityEffect = when (id) {
        SystemEnvironmentCapabilityIds.STATUS,
        SystemEnvironmentCapabilityIds.IDLE_GET,
        SystemEnvironmentCapabilityIds.SESSION_SCREEN -> InProcessCapabilityEffect.READ_ONLY

        SystemEnvironmentCapabilityIds.FILESYSTEM -> InProcessCapabilityEffect.PERSISTENT_WRITE
        SystemEnvironmentCapabilityIds.START,
        SystemEnvironmentCapabilityIds.STOP,
        SystemEnvironmentCapabilityIds.IDLE_SET,
        SystemEnvironmentCapabilityIds.SESSION_CREATE,
        SystemEnvironmentCapabilityIds.SESSION_CLOSE -> InProcessCapabilityEffect.STATE_CHANGE

        else -> InProcessCapabilityEffect.PROCESS_EXECUTION
    }

    private fun receipts(id: String): Set<InProcessCapabilityReceipt> = when (effect(id)) {
        InProcessCapabilityEffect.READ_ONLY -> emptySet()
        else -> setOf(InProcessCapabilityReceipt.WORK_MANUAL)
    }
}
