package com.ai.assistance.operit.plugins.system

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object SystemPluginTrustV1 {
    private data class TrustedSigner(
        val publicKeyX509DerBase64: String,
        val allowedRoles: Set<String>
    )

    private val trustedSigners = mapOf(
        "ai-limbs-plugin-center-dev-v1" to TrustedSigner(
            publicKeyX509DerBase64 =
                "MCowBQYDK2VwAyEAAp37oPipssz+0yW+8ceya8OC+QAW0YJBoZ0pgv5DIlE=",
            allowedRoles = setOf(SystemPluginProtocolV1.ROLE_PLUGIN_CENTER)
        )
    )

    fun verify(
        signerId: String,
        role: String,
        manifestBytes: ByteArray,
        signatureBytes: ByteArray
    ): SystemPluginTrustStatus {
        val signer = trustedSigners[signerId]
            ?: throw SystemPluginProtocolException(
                "SYSTEM_SIGNER_UNKNOWN",
                "System plugin signer is not trusted: $signerId"
            )
        if (role !in signer.allowedRoles) {
            throw SystemPluginProtocolException(
                "SYSTEM_SIGNER_ROLE_FORBIDDEN",
                "Signer $signerId is not admitted for system role $role"
            )
        }

        val publicKeyBytes = Base64.getDecoder().decode(signer.publicKeyX509DerBase64)
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        if (!verifier.verify(signatureBytes)) {
            throw SystemPluginProtocolException(
                "SYSTEM_SIGNATURE_INVALID",
                "System plugin Ed25519 signature verification failed"
            )
        }
        return SystemPluginTrustStatus.TRUSTED
    }
}
