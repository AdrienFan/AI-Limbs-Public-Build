package com.ai.limbs.extensions.chatgptprobe

import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderContribution
import com.ai.limbs.plugin.runtime.ChildExtensionEntry
import com.ai.limbs.plugin.runtime.ChildExtensionHandle
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import org.json.JSONObject

class ChatGptNativeProbeExtensionEntry : ChildExtensionEntry {
    override suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle {
        val engine = ChatGptNativeProbeEngine(host)
        val handles = mutableListOf<AutoCloseable>()

        try {
            handles += host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$CAPABILITY_PREFIX.run",
                    displayName = "运行 ChatGPT Native Probe",
                    description = "在 Android Host 中直接执行测试子插件内置的官方 tunnel-client --version；不使用 Ubuntu、不读取 Key、不建立 Tunnel。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "native probe", "兼容性测试"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.PROCESS_EXECUTION,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        engine.runProbe().toJson(host).toString()
                    }
                )
            )

            handles += host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$CAPABILITY_PREFIX.status",
                    displayName = "读取 ChatGPT Native Probe 结果",
                    description = "读取最近一次 Android Host tunnel-client 直接执行测试结果；不触发新进程。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "native probe", "状态"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.READ_ONLY,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        engine.statusJson().toString()
                    }
                )
            )

            host.publish(
                BridgeProviderContribution(
                    factory = ChatGptNativeProbeBridgeProvider.Factory(engine),
                    panel = ChatGptNativeProbePanel,
                    notification = null
                ),
                mapOf(
                    "provider_id" to ChatGptNativeProbeBridgeProvider.PROFILE_ID,
                    "provider_type" to ChatGptNativeProbeBridgeProvider.PROFILE_TYPE,
                    "source" to "AI-Limbs-ChatGPT-Native-Probe-v0.0.3",
                    "purpose" to "android_direct_exec_compatibility_probe"
                )
            )
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.close() } }
            throw error
        }

        return ChildExtensionHandle {
            handles.asReversed().forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val CAPABILITY_PREFIX = "plugin.chatgpt_native_probe"
        private val EMPTY_SCHEMA = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false)
            .toString()
    }
}
