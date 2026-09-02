package com.ai.assistance.operit.plugins.system

import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginTrustKeyringV1

internal object SystemPluginTrustV1 {
    fun verify(
        signerId: String,
        role: String,
        manifestBytes: ByteArray,
        signatureBytes: ByteArray
    ): SystemPluginTrustStatus {
        try {
            PluginTrustKeyringV1.requireSigner(
                signerId = signerId,
                purpose = PluginTrustKeyringV1.PURPOSE_SYSTEM_PLUGIN,
                role = role
            )
        } catch (error: PluginInstallException) {
            val code = when (error.code) {
                "TRUST_SIGNER_UNKNOWN" -> "SYSTEM_SIGNER_UNKNOWN"
                "TRUST_SIGNER_ROLE_FORBIDDEN", "TRUST_SIGNER_PURPOSE_FORBIDDEN" ->
                    "SYSTEM_SIGNER_ROLE_FORBIDDEN"
                else -> "SYSTEM_TRUST_KEYRING_INVALID"
            }
            throw SystemPluginProtocolException(code, error.message ?: "System plugin trust lookup failed")
        }
        val verified = try {
            PluginTrustKeyringV1.verifyDetached(
                signerId = signerId,
                purpose = PluginTrustKeyringV1.PURPOSE_SYSTEM_PLUGIN,
                role = role,
                payload = manifestBytes,
                signatureBytes = signatureBytes
            )
        } catch (error: PluginInstallException) {
            throw SystemPluginProtocolException(
                "SYSTEM_TRUST_KEYRING_INVALID",
                error.message ?: "System plugin trust verification failed"
            )
        }
        if (!verified) {
            throw SystemPluginProtocolException(
                "SYSTEM_SIGNATURE_INVALID",
                "System plugin Ed25519 signature verification failed"
            )
        }
        return SystemPluginTrustStatus.TRUSTED
    }
}
