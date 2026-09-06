package com.ai.assistance.operit.integrations.ailimbs

import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Host-issued authorization carried only inside one coroutine transaction.
 *
 * It never replaces receipts, plugin scope checks, runtime availability, or FORBID. It may satisfy
 * only an otherwise-ASK permission when the Host already has stronger proof: an explicit foreground
 * UI gesture, or a narrowly whitelisted passive read backed by an already-approved Host scope.
 */
internal class AiLimbsExecutionAuthorizationContext private constructor(
    val ownerPluginId: String,
    private val allowedTargets: Set<String>?,
    val source: Source,
    val detail: String,
    private val seal: Any
) : AbstractCoroutineContextElement(Key) {
    enum class Source { EXPLICIT_UI_ACTION, APPROVED_SCOPE_READ }

    companion object Key : CoroutineContext.Key<AiLimbsExecutionAuthorizationContext> {
        private val authority = Any()

        fun explicitUiAction(
            ownerPluginId: String,
            detail: String
        ): AiLimbsExecutionAuthorizationContext =
            AiLimbsExecutionAuthorizationContext(
                ownerPluginId = ownerPluginId.trim(),
                allowedTargets = null,
                source = Source.EXPLICIT_UI_ACTION,
                detail = detail,
                seal = authority
            )

        fun approvedScopeRead(
            ownerPluginId: String,
            targetName: String,
            detail: String
        ): AiLimbsExecutionAuthorizationContext =
            AiLimbsExecutionAuthorizationContext(
                ownerPluginId = ownerPluginId.trim(),
                allowedTargets = setOf(targetName.trim().lowercase()),
                source = Source.APPROVED_SCOPE_READ,
                detail = detail,
                seal = authority
            )

        fun trusted(context: CoroutineContext): AiLimbsExecutionAuthorizationContext? =
            context[Key]?.takeIf { it.seal === authority }
    }

    fun allows(ownerPluginId: String, targetName: String): Boolean {
        if (this.ownerPluginId != ownerPluginId.trim()) return false
        val allowed = allowedTargets ?: return true
        return targetName.trim().lowercase() in allowed
    }
}

internal object AiLimbsExecutionAuthorization {
    suspend fun <T> withExplicitUiAction(
        ownerPluginId: String,
        screenId: String,
        capabilityId: String,
        block: suspend () -> T
    ): T = withContext(
        AiLimbsExecutionAuthorizationContext.explicitUiAction(
            ownerPluginId = ownerPluginId,
            detail = "screen=$screenId capability=$capabilityId"
        )
    ) { block() }

    suspend fun <T> withApprovedScopeRead(
        ownerPluginId: String,
        primitiveId: String,
        operation: String,
        targetName: String,
        block: suspend () -> T
    ): T = withContext(
        AiLimbsExecutionAuthorizationContext.approvedScopeRead(
            ownerPluginId = ownerPluginId,
            targetName = targetName,
            detail = "primitive=$primitiveId operation=$operation"
        )
    ) { block() }

    fun allows(
        context: CoroutineContext,
        session: AiLimbsExecutionSession,
        targetName: String
    ): Boolean {
        val authorization = AiLimbsExecutionAuthorizationContext.trusted(context) ?: return false
        val ownerPluginId = session.pluginOwnerId() ?: return false
        return authorization.allows(ownerPluginId, targetName)
    }

    private fun AiLimbsExecutionSession.pluginOwnerId(): String? {
        if (transport != AiLimbsExecutionTransport.PLUGIN_RUNTIME) return null
        val normalized = scopeId.trim()
        return when {
            normalized.startsWith("plugin:") -> normalized.removePrefix("plugin:")
            normalized.startsWith("system:") -> normalized.removePrefix("system:")
            normalized.startsWith("plugin-ui:") -> normalized.removePrefix("plugin-ui:")
            else -> null
        }.trim().takeIf { it.isNotEmpty() }
    }
}
