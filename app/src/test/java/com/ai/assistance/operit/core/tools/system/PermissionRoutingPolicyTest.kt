package com.ai.assistance.operit.core.tools.system

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRoutingPolicyTest {
    @Test
    fun legacyShellKeepsSinglePreferredProvider() {
        assertEquals(
            listOf(AndroidPermissionLevel.ACCESSIBILITY),
            PermissionRoutingPolicy.shellCandidates(
                coexistEnabled = false,
                legacyPreferred = AndroidPermissionLevel.ACCESSIBILITY
            )
        )
    }

    @Test
    fun coexistShellUsesCapabilityOrderWithoutAccessibilityOrAdmin() {
        assertEquals(
            listOf(
                AndroidPermissionLevel.DEBUGGER,
                AndroidPermissionLevel.ROOT,
                AndroidPermissionLevel.STANDARD
            ),
            PermissionRoutingPolicy.shellCandidates(
                coexistEnabled = true,
                legacyPreferred = AndroidPermissionLevel.ACCESSIBILITY
            )
        )
    }

    @Test
    fun coexistUiPrefersAccessibilityThenDebuggerThenRoot() {
        assertEquals(
            listOf(
                AndroidPermissionLevel.ACCESSIBILITY,
                AndroidPermissionLevel.DEBUGGER,
                AndroidPermissionLevel.ROOT
            ),
            PermissionRoutingPolicy.uiCandidates(
                coexistEnabled = true,
                legacyPreferred = AndroidPermissionLevel.ROOT,
                allowAccessibility = true
            )
        )
    }

    @Test
    fun coexistUiSkipsAccessibilityForExplicitDisplay() {
        assertEquals(
            listOf(
                AndroidPermissionLevel.DEBUGGER,
                AndroidPermissionLevel.ROOT
            ),
            PermissionRoutingPolicy.uiCandidates(
                coexistEnabled = true,
                legacyPreferred = AndroidPermissionLevel.ACCESSIBILITY,
                allowAccessibility = false
            )
        )
    }
}
