package com.ai.limbs.extensions.sentinelx

import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderContribution
import com.ai.limbs.extensions.sentinelx.runtime.SentinelXBridgeProvider
import com.ai.limbs.plugin.runtime.ChildExtensionEntry
import com.ai.limbs.plugin.runtime.ChildExtensionHandle
import com.ai.limbs.plugin.runtime.ChildExtensionHost

class SentinelXExtensionEntry : ChildExtensionEntry {
    override suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle {
        SentinelXLogger.bind(host.logger)
        host.publish(
            BridgeProviderContribution(
                factory = SentinelXBridgeProvider.Factory(),
                panel = SentinelXBridgeProviderPanel,
                notification = SentinelXBridgeProviderNotification
            ),
            mapOf(
                "provider_id" to SentinelXBridgeProvider.PROFILE_ID,
                "provider_type" to SentinelXBridgeProvider.PROFILE_TYPE,
                "source" to "AI-Limbs-SentinelX-v0.1.0"
            )
        )
        return ChildExtensionHandle { SentinelXLogger.bind(null) }
    }
}
