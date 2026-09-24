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

    @Test
    fun coexistPrefersAccessibilityWhenAvailable() {
        val selection = selectCoexistingUiAutomationBackend(
            accessibilityAvailable = true,
            debuggerAvailable = true,
            rootAvailable = true,
            allowAccessibility = true
        )

        assertEquals(UiAutomationBackend.ACCESSIBILITY, selection.backend)
        assertEquals("coexist_auto", selection.fallbackReason)
    }

    @Test
    fun coexistUsesDebuggerWhenAccessibilityUnavailable() {
        val selection = selectCoexistingUiAutomationBackend(
            accessibilityAvailable = false,
            debuggerAvailable = true,
            rootAvailable = true,
            allowAccessibility = true
        )

        assertEquals(UiAutomationBackend.DEBUGGER, selection.backend)
        assertEquals(AndroidPermissionLevel.DEBUGGER, selection.shellPermissionLevel)
    }

    @Test
    fun coexistExplicitDisplaySkipsAccessibility() {
        val selection = selectCoexistingUiAutomationBackend(
            accessibilityAvailable = true,
            debuggerAvailable = true,
            rootAvailable = true,
            allowAccessibility = false
        )

        assertEquals(UiAutomationBackend.DEBUGGER, selection.backend)
    }

    @Test
    fun coexistFallsBackToRootWhenNeeded() {
        val selection = selectCoexistingUiAutomationBackend(
            accessibilityAvailable = false,
            debuggerAvailable = false,
            rootAvailable = true,
            allowAccessibility = true
        )

        assertEquals(UiAutomationBackend.ROOT, selection.backend)
    }

    @Test
    fun coexistIsUnsupportedWhenNoProviderAvailable() {
        val selection = selectCoexistingUiAutomationBackend(
            accessibilityAvailable = false,
            debuggerAvailable = false,
            rootAvailable = false,
            allowAccessibility = true
        )

        assertEquals(UiAutomationBackend.UNSUPPORTED, selection.backend)
        assertEquals("coexist_no_provider", selection.fallbackReason)
    }
}
