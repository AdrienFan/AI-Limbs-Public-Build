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
                    displayName = "启动 AI Limbs-ChatGPT",
                    description = "启动 Android/Kotlin OpenAI Tunnel listener，并把 MCP tools/call 动态路由到 AI Limbs live capability resolver / dispatcher。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "OpenAI", "listener", "gateway"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.EXTERNAL_COMMUNICATION,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        val started = engine.start()
                        JSONObject()
                            .put("started", started)
                            .put("status", engine.statusJson())
                            .toString()
                    }
                )
            )

            handles += host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$CAPABILITY_PREFIX.status",
                    displayName = "读取 AI Limbs-ChatGPT 状态",
                    description = "读取真实网络心跳、能力目录观察、执行凭据与结果交付状态；不会泄露 Runtime API Key。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "状态", "statistics"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.READ_ONLY,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        engine.statusJson().toString()
                    }
                )
            )

            handles += host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$CAPABILITY_PREFIX.stop",
                    displayName = "停止 AI Limbs-ChatGPT",
                    description = "停止 Gateway，取消当前传输与执行协程，并保留加密执行凭据和待送结果；取消不撤销已发生的业务效果。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "停止", "stop"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.STATE_CHANGE,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        val stopped = engine.stop()
                        JSONObject()
                            .put("stopped", stopped)
                            .put("status", engine.statusJson())
                            .toString()
                    }
                )
            )

            host.publish(
                BridgeProviderContribution(
                    factory = ChatGptNativeProbeBridgeProvider.Factory(engine),
                    panel = ChatGptNativeProbePanel(host.applicationContext, engine),
                    notification = ChatGptNativeProbeNotification(host.applicationContext, engine)
                ),
                mapOf(
                    "provider_id" to ChatGptNativeProbeBridgeProvider.PROFILE_ID,
                    "provider_type" to ChatGptNativeProbeBridgeProvider.PROFILE_TYPE,
                    "source" to "AI-Limbs-ChatGPT-Dynamic-Gateway-v0.0.12",
                    "purpose" to "android_okhttp_dynamic_capability_gateway"
                )
            )
        } catch (error: Throwable) {
            engine.close()
            handles.asReversed().forEach { runCatching { it.close() } }
            throw error
        }

        return ChildExtensionHandle {
            engine.close()
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
