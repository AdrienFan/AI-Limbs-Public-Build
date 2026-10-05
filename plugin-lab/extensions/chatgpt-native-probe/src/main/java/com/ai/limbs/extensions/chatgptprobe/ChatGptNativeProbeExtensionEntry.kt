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
                    displayName = "启动 ChatGPT MCP Echo Probe",
                    description = "启动 Android/Kotlin Secure MCP Tunnel responder，常驻 long-poll 并处理最小 MCP initialize/tools/list/tools/call 闭环。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "OpenAI", "responder", "long poll"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.EXTERNAL_COMMUNICATION,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        engine.startAndAwaitReady().toJson().toString()
                    }
                )
            )

            handles += host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$CAPABILITY_PREFIX.stop",
                    displayName = "停止 ChatGPT MCP Echo Probe",
                    description = "停止 Android/Kotlin Secure MCP Tunnel long-poll responder。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "停止"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.STATE_CHANGE,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        engine.stopLoop()
                        engine.statusJson().toString()
                    }
                )
            )

            handles += host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$CAPABILITY_PREFIX.status",
                    displayName = "读取 ChatGPT MCP Echo Probe 状态",
                    description = "读取 responder 运行状态、poll/command/response 计数；不会泄露 Runtime API Key。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "OpenAI", "状态"),
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
                    "source" to "AI-Limbs-ChatGPT-MCP-Echo-Probe-v0.0.5",
                    "purpose" to "android_kotlin_mcp_round_trip_probe"
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
