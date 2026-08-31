package com.ai.limbs.extensions.triggercmd

import com.ai.assistance.operit.integrations.ailimbs.TriggerPluginHostBridge
import com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd.TriggerCmdBridgeProvider
import com.ai.limbs.plugin.runtime.ChildExtensionEntry
import com.ai.limbs.plugin.runtime.ChildExtensionHandle
import com.ai.limbs.plugin.runtime.ChildExtensionHost

class TriggerCmdExtensionEntry : ChildExtensionEntry {
    override suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle {
        TriggerPluginHostBridge.host = host
        host.publish(
            TriggerCmdBridgeProvider.Factory(),
            mapOf("provider_id" to TriggerCmdBridgeProvider.PROFILE_ID, "provider_type" to TriggerCmdBridgeProvider.PROFILE_TYPE, "source" to "AI-Limbs-V0.6.4.7.8")
        )
        return ChildExtensionHandle { if (TriggerPluginHostBridge.host === host) TriggerPluginHostBridge.host = null }
    }
}
