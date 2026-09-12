package com.ai.limbs.extensions.rdc

import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderContribution
import com.ai.limbs.extensions.rdc.runtime.RdcBridgeProvider
import com.ai.limbs.plugin.runtime.ChildExtensionEntry
import com.ai.limbs.plugin.runtime.ChildExtensionHandle
import com.ai.limbs.plugin.runtime.ChildExtensionHost

class RdcExtensionEntry : ChildExtensionEntry {
    override suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle {
        RdcLogger.bind(host.logger)
        host.publish(
            BridgeProviderContribution(
                factory = RdcBridgeProvider.Factory(),
                panel = RdcBridgeProviderPanel,
                notification = RdcBridgeProviderNotification
            ),
            mapOf("provider_id" to RdcBridgeProvider.PROFILE_ID, "provider_type" to RdcBridgeProvider.PROFILE_TYPE, "source" to "AI-Limbs-V0.8")
        )
        return ChildExtensionHandle { RdcLogger.bind(null) }
    }
}
