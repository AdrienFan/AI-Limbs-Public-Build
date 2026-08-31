// Source: AI Limbs V0.6.4.7.8 @ 70438d99bb40c147cadc0a4a085deb90d15b347c; visibility-only ABI adaptation.
package com.ai.assistance.operit.integrations.ailimbs

/**
 * Provider-neutral bridge state consumed by the Android service layer.
 *
 * Transport implementations publish state only. Android notification ownership
 * stays in AIForegroundService so a bridge can never leave its own ongoing
 * notification behind after the service is stopped.
 */
enum class AiLimbsBridgePhase {
    STOPPED,
    STARTING,
    CONNECTING,
    PAIRING,
    ONLINE,
    RECONNECTING,
    RECOVERING,
    RECOVERY_FAILED,
    ERROR
}

data class AiLimbsBridgeState(
    val providerId: String = "",
    val providerLabel: String = "Bridge",
    val phase: AiLimbsBridgePhase = AiLimbsBridgePhase.STOPPED,
    val detail: String = "",
    val userCode: String? = null,
    val verificationUri: String? = null,
    val deviceId: String? = null,
    val lastHeartbeatAtMs: Long? = null,
    val reconnectAttempt: Int = 0
)
