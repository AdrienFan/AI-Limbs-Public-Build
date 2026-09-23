package com.ai.assistance.operit.core.tools.defaultTool

import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UiAutomationBackendPolicyTest {
    @Test
    fun debuggerStaysPreferredWhenAvailable() {
        val selection = selectUiAutomationBackend(
            preferredPermissionLevel = AndroidPermissionLevel.DEBUGGER,
            preferredAvailable = true,
            accessibilityAvailable = true,
            allowAccessibilityFallback = true
        )

        assertEquals(UiAutomationBackend.DEBUGGER, selection.backend)
        assertEquals(AndroidPermissionLevel.DEBUGGER, selection.shellPermissionLevel)
        assertNull(selection.fallbackReason)
    }

    @Test
    fun debuggerFallsBackToAccessibilityWhenUnavailable() {
        val selection = selectUiAutomationBackend(
            preferredPermissionLevel = AndroidPermissionLevel.DEBUGGER,
            preferredAvailable = false,
            accessibilityAvailable = true,
            allowAccessibilityFallback = true
        )

        assertEquals(UiAutomationBackend.ACCESSIBILITY, selection.backend)
        assertEquals(AndroidPermissionLevel.DEBUGGER, selection.preferredPermissionLevel)
        assertFalse(selection.preferredAvailable)
        assertEquals("debugger_unavailable", selection.fallbackReason)
    }

    @Test
    fun adminFallsBackToAccessibilityWhenUnavailable() {
        val selection = selectUiAutomationBackend(
            preferredPermissionLevel = AndroidPermissionLevel.ADMIN,
            preferredAvailable = false,
            accessibilityAvailable = true,
            allowAccessibilityFallback = true
        )

        assertEquals(UiAutomationBackend.ACCESSIBILITY, selection.backend)
        assertEquals("admin_unavailable", selection.fallbackReason)
    }

    @Test
    fun rootFallsBackToAccessibilityWhenUnavailable() {
        val selection = selectUiAutomationBackend(
            preferredPermissionLevel = AndroidPermissionLevel.ROOT,
            preferredAvailable = false,
            accessibilityAvailable = true,
            allowAccessibilityFallback = true
        )

        assertEquals(UiAutomationBackend.ACCESSIBILITY, selection.backend)
        assertEquals("root_unavailable", selection.fallbackReason)
    }

    @Test
    fun explicitDisplayDisablesAccessibilityFallback() {
        val selection = selectUiAutomationBackend(
            preferredPermissionLevel = AndroidPermissionLevel.DEBUGGER,
            preferredAvailable = false,
            accessibilityAvailable = true,
            allowAccessibilityFallback = false
        )

        assertEquals(UiAutomationBackend.DEBUGGER, selection.backend)
        assertEquals(AndroidPermissionLevel.DEBUGGER, selection.shellPermissionLevel)
        assertNull(selection.fallbackReason)
    }

    @Test
    fun accessibilityPreferenceRemainsStrictWhenUnavailable() {
        val selection = selectUiAutomationBackend(
            preferredPermissionLevel = AndroidPermissionLevel.ACCESSIBILITY,
            preferredAvailable = false,
            accessibilityAvailable = false,
            allowAccessibilityFallback = true
        )

        assertEquals(UiAutomationBackend.ACCESSIBILITY, selection.backend)
        assertFalse(selection.preferredAvailable)
        assertNull(selection.fallbackReason)
    }

    @Test
    fun standardPreferenceRemainsUnsupported() {
        val selection = selectUiAutomationBackend(
            preferredPermissionLevel = AndroidPermissionLevel.STANDARD,
            preferredAvailable = false,
            accessibilityAvailable = true,
            allowAccessibilityFallback = true
        )

        assertEquals(UiAutomationBackend.UNSUPPORTED, selection.backend)
    }
}
