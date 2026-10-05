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
                    displayName = "运行 ChatGPT Control Plane Probe",
                    description = "直接从 Android 子插件通过 OkHttp long-poll 连接 OpenAI Tunnel Control Plane；不依赖 Ubuntu，不运行 native tunnel-client。",
                    keywords = listOf("ChatGPT", "MCP", "Tunnel", "OpenAI", "Control Plane", "long poll"),
                    suggestedParamsJson = "{}",
                    inputSchema = EMPTY_SCHEMA,
                    effect = InProcessCapabilityEffect.READ_ONLY,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor {
                        engine.runProbe().toJson().toString()
                    }
                )
            )

            handles += host.registerCapability(
                InProcessCapabilitySpec(
                    id = "$CAPABILITY_PREFIX.status",
                    displayName = "读取 ChatGPT Control Plane Probe 状态",
                    description = "读取本地配置状态与最近一次 OpenAI Tunnel Control Plane 测试结果；不会泄露 Runtime API Key。",
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
                    "source" to "AI-Limbs-ChatGPT-Control-Plane-Probe-v0.0.4",
                    "purpose" to "android_okhttp_control_plane_probe"
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
