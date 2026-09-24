package com.ai.assistance.operit.core.tools.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyRuntimeDecisionTest {
    @Test
    fun residentCoreMainLooperUsesLocalBootstrapState() {
        assertTrue(
            PermissionPolicyRuntime.shouldUseLocalBootstrapState(
                isResidentCore = true,
                isMainLooper = true
            )
        )
    }

    @Test
    fun residentCoreBackgroundThreadMaySyncWithHost() {
        assertFalse(
            PermissionPolicyRuntime.shouldUseLocalBootstrapState(
                isResidentCore = true,
                isMainLooper = false
            )
        )
    }

    @Test
    fun androidHostDoesNotUseResidentBootstrapFallback() {
        assertFalse(
            PermissionPolicyRuntime.shouldUseLocalBootstrapState(
                isResidentCore = false,
                isMainLooper = true
            )
        )
    }
}
