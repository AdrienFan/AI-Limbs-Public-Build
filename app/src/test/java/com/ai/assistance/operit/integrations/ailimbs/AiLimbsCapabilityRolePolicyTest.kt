package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Test

class AiLimbsCapabilityRolePolicyTest {
    @Test
    fun genericLifecycleActionsAreExcludedWithoutPluginSpecificIds() {
        assertEquals(
            AiLimbsCapabilityRole.LIFECYCLE,
            AiLimbsCapabilityRolePolicy.forPlugin(
                capabilityId = "plugin.test.unknown_runtime.start",
                effect = AiLimbsEffect.STATE_CHANGE
            )
        )
        assertEquals(
            AiLimbsCapabilityRole.LIFECYCLE,
            AiLimbsCapabilityRolePolicy.forPlugin(
                capabilityId = "plugin.vendor.database.restart",
                effect = AiLimbsEffect.STATE_CHANGE
            )
        )
    }

    @Test
    fun businessActionsRemainBusiness() {
        assertEquals(
            AiLimbsCapabilityRole.BUSINESS,
            AiLimbsCapabilityRolePolicy.forPlugin(
                capabilityId = "plugin.test.unknown_runtime.command",
                effect = AiLimbsEffect.PROCESS_EXECUTION
            )
        )
        assertEquals(
            AiLimbsCapabilityRole.BUSINESS,
            AiLimbsCapabilityRolePolicy.forPlugin(
                capabilityId = "plugin.test.recording.start_recording",
                effect = AiLimbsEffect.STATE_CHANGE
            )
        )
    }
}
