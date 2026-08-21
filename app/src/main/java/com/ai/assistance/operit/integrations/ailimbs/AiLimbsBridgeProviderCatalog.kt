package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.integrations.ailimbs.providers.rdc.RdcBridgeProvider

internal object AiLimbsBridgeProviderCatalog {
    const val DEFAULT_PROFILE_ID = RdcBridgeProvider.PROFILE_ID

    fun createRegistry(): BridgeProviderRegistry =
        BridgeProviderRegistry()
            .register(RdcBridgeProvider.Factory())
}
