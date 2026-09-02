package com.ai.assistance.operit.plugins.center

import java.io.File

enum class PluginTrustVerdict {
    TRUSTED,
    UNSIGNED,
    UNKNOWN_SIGNER,
    INVALID_SIGNATURE,
    UNSUPPORTED_SIGNATURE
}

data class PluginTrustDecision(
    val verdict: PluginTrustVerdict,
    val signerId: String? = null,
    val reason: String? = null
) {
    val isTrusted: Boolean get() = verdict == PluginTrustVerdict.TRUSTED
}

fun interface PluginTrustVerifier {
    fun verify(
        managedPackage: File,
        contentDir: File,
        manifest: PluginManifest,
        packageSha256: String
    ): PluginTrustDecision
}

object StrictPluginTrustVerifier : PluginTrustVerifier {
    override fun verify(
        managedPackage: File,
        contentDir: File,
        manifest: PluginManifest,
        packageSha256: String
    ): PluginTrustDecision {
        val signature = manifest.signature
            ?: return PluginTrustDecision(
                verdict = PluginTrustVerdict.UNSIGNED,
                reason = "No publisher signature is declared"
            )
        if (signature.algorithm != "Ed25519") {
            return PluginTrustDecision(
                verdict = PluginTrustVerdict.UNSUPPORTED_SIGNATURE,
                signerId = signature.signerId,
                reason = "Plugin V1 requires Ed25519 publisher signatures"
            )
        }
        val manifestFile = File(contentDir, PluginAbi.MANIFEST_ENTRY)
        val signatureFile = File(contentDir, signature.signatureEntry)
        if (!manifestFile.isFile || !signatureFile.isFile) {
            return PluginTrustDecision(
                verdict = PluginTrustVerdict.INVALID_SIGNATURE,
                signerId = signature.signerId,
                reason = "Publisher signature material is incomplete"
            )
        }
        return runCatching {
            PluginTrustKeyringV1.requireSigner(
                signature.signerId,
                PluginTrustKeyringV1.PURPOSE_PARENT_PLUGIN
            )
            if (!PluginTrustKeyringV1.verifyDetached(
                    signerId = signature.signerId,
                    purpose = PluginTrustKeyringV1.PURPOSE_PARENT_PLUGIN,
                    payload = manifestFile.readBytes(),
                    signatureBytes = signatureFile.readBytes()
                )) {
                PluginTrustDecision(
                    verdict = PluginTrustVerdict.INVALID_SIGNATURE,
                    signerId = signature.signerId,
                    reason = "Plugin Ed25519 publisher signature verification failed"
                )
            } else {
                PluginTrustDecision(
                    verdict = PluginTrustVerdict.TRUSTED,
                    signerId = signature.signerId
                )
            }
        }.getOrElse { error ->
            val unknown = error is PluginInstallException && error.code == "TRUST_SIGNER_UNKNOWN"
            PluginTrustDecision(
                verdict = if (unknown) PluginTrustVerdict.UNKNOWN_SIGNER else PluginTrustVerdict.INVALID_SIGNATURE,
                signerId = signature.signerId,
                reason = error.message ?: "Plugin publisher signature verification failed"
            )
        }
    }
}

data class PluginInstallOptions(
    val allowUntrustedForDevelopment: Boolean = false,
    val enableAfterInstall: Boolean = false,
    val approvedScopes: Set<String> = emptySet()
)

class PluginInstallException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
