package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDomain
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsEffect
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsRequiredReceipt
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginCapabilityDomainMappingTest {
    @Test
    fun everyPluginDomainHasOnePolicyDomain() {
        val mapped = PluginCapabilityDomain.entries.map { it.toAiLimbsPolicyDomain() }
        assertEquals(PluginCapabilityDomain.entries.size, mapped.toSet().size)
        assertEquals(AiLimbsDomain.entries.toSet(), mapped.toSet())
        assertEquals(
            AiLimbsDomain.PLUGIN_CHAT_MODE,
            PluginCapabilityDomain.LANER_CHAT.toAiLimbsPolicyDomain()
        )
    }

    @Test
    fun everyPluginEffectAndReceiptHasAnExplicitPolicyTranslation() {
        assertEquals(
            AiLimbsEffect.entries.toSet(),
            PluginCapabilityEffect.entries.map { it.toAiLimbsPolicyEffect() }.toSet()
        )
        assertEquals(
            AiLimbsRequiredReceipt.WORK_MANUAL,
            PluginCapabilityReceipt.WORK_MANUAL.toAiLimbsPolicyReceipt()
        )
    }
}
