package com.ai.assistance.operit.integrations.ailimbs

/**
 * Semantic role of a capability. This is intentionally orthogonal to effect/domain:
 * effect/domain govern execution policy, while role governs discovery presentation.
 */
internal enum class AiLimbsCapabilityRole {
    BUSINESS,
    LIFECYCLE,
    CONTROL_PLANE
}

internal object AiLimbsCapabilityRolePolicy {
    fun forPlugin(
        capabilityId: String,
        effect: AiLimbsEffect
    ): AiLimbsCapabilityRole =
        if (
            effect == AiLimbsEffect.STATE_CHANGE &&
                lifecycleAction(capabilityId) != null
        ) {
            AiLimbsCapabilityRole.LIFECYCLE
        } else {
            AiLimbsCapabilityRole.BUSINESS
        }

    fun forStandalone(invokeId: String): AiLimbsCapabilityRole =
        if (lifecycleAction(invokeId) != null) {
            AiLimbsCapabilityRole.LIFECYCLE
        } else {
            AiLimbsCapabilityRole.BUSINESS
        }

    private fun lifecycleAction(value: String): String? {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) return null
        val action =
            normalized
                .substringAfterLast(':')
                .substringAfterLast('.')
        return action.takeIf { it in LIFECYCLE_ACTIONS }
    }

    private val LIFECYCLE_ACTIONS =
        setOf(
            "start",
            "stop",
            "restart",
            "shutdown",
            "boot",
            "power_on",
            "power_off",
            "poweron",
            "poweroff"
        )
}
