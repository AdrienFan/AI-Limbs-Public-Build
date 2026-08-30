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
        return PluginTrustDecision(
            verdict = PluginTrustVerdict.UNKNOWN_SIGNER,
            signerId = signature.signerId,
            reason = "No trusted publisher keyring is configured yet"
        )
    }
}

data class PluginInstallOptions(
    val allowUntrustedForDevelopment: Boolean = false,
    val enableAfterInstall: Boolean = false
)

class PluginInstallException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
