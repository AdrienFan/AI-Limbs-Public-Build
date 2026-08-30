package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.integrations.ailimbs.providers.rdc.RdcBridgeProvider
import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdBridgeProvider

internal object AiLimbsBridgeProviderCatalog {
    private val factories: List<BridgeProviderFactory> =
        listOf(
            RdcBridgeProvider.Factory(),
            TriggerCmdBridgeProvider.Factory()
        )

    val DEFAULT_PROFILE_ID: String =
        factories
            .flatMap { it.profiles }
            .singleOrNull { it.isDefault }
            ?.id
            ?: error("Exactly one default Bridge profile must be registered")

    fun createRegistry(): BridgeProviderRegistry =
        BridgeProviderRegistry().also { registry ->
            factories.forEach { factory -> registry.register(factory) }
        }
}
