package com.ai.assistance.operit.core.tools.system

/**
 * Capability-oriented permission routing policy.
 *
 * Coexist mode deliberately has no primary permission. Providers remain independently available
 * and each capability chooses the least-privileged suitable route.
 */
object PermissionRoutingPolicy {
    private val coexistShellOrder =
        listOf(
            AndroidPermissionLevel.DEBUGGER,
            AndroidPermissionLevel.ROOT,
            AndroidPermissionLevel.STANDARD
        )

    private val coexistUiOrder =
        listOf(
            AndroidPermissionLevel.ACCESSIBILITY,
            AndroidPermissionLevel.DEBUGGER,
            AndroidPermissionLevel.ROOT
        )

    fun shellCandidates(
        coexistEnabled: Boolean,
        legacyPreferred: AndroidPermissionLevel?
    ): List<AndroidPermissionLevel> =
        if (coexistEnabled) {
            coexistShellOrder
        } else {
            listOf(legacyPreferred ?: AndroidPermissionLevel.STANDARD)
        }

    fun generalToolCandidates(
        coexistEnabled: Boolean,
        legacyPreferred: AndroidPermissionLevel?
    ): List<AndroidPermissionLevel> =
        shellCandidates(coexistEnabled, legacyPreferred)

    fun uiCandidates(
        coexistEnabled: Boolean,
        legacyPreferred: AndroidPermissionLevel?,
        allowAccessibility: Boolean
    ): List<AndroidPermissionLevel> {
        if (!coexistEnabled) {
            return listOf(legacyPreferred ?: AndroidPermissionLevel.STANDARD)
        }
        return if (allowAccessibility) {
            coexistUiOrder
        } else {
            coexistUiOrder.filterNot { it == AndroidPermissionLevel.ACCESSIBILITY }
        }
    }
}
