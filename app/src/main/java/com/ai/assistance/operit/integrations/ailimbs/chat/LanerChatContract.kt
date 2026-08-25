package com.ai.assistance.operit.integrations.ailimbs.chat

import com.ai.assistance.operit.data.model.ModelConfigData

enum class LanerChatPresenceState(val wireValue: String) {
    ACTIVE("active"),
    RECENT("recent"),
    WAITING("waiting")
}

object LanerChatContract {
    const val PROVIDER_TYPE_ID = "ai_limbs_laner_bridge"
    const val CONFIG_ID = "ai_limbs_laner_bridge"
    const val MODEL_ID = "laner-chat"
    const val PROVIDER_MODEL = "AI_LIMBS:LANER_BRIDGE"
    const val DEFAULT_SENDER = "阿伟"
    const val DEFAULT_AGENT_NAME = "兰儿"
    const val AGENT_ACTIVE_WINDOW_MS = 20_000L
    const val AGENT_RECENT_WINDOW_MS = 45_000L
    const val PRESENCE_UI_TICK_MS = 2_000L

    // RDC/ChatGPT does not expose a true connection lifecycle. Presence therefore describes
    // verified Laner activity: fresh activity is ACTIVE, then RECENT, then WAITING.
    fun presenceState(
        activeSessionId: String?,
        lastAgentSeenAtMs: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): LanerChatPresenceState {
        if (activeSessionId.isNullOrBlank() || lastAgentSeenAtMs == null) {
            return LanerChatPresenceState.WAITING
        }
        val ageMs = (nowMs - lastAgentSeenAtMs).coerceAtLeast(0L)
        return when {
            ageMs <= AGENT_ACTIVE_WINDOW_MS -> LanerChatPresenceState.ACTIVE
            ageMs <= AGENT_RECENT_WINDOW_MS -> LanerChatPresenceState.RECENT
            else -> LanerChatPresenceState.WAITING
        }
    }

    fun isBridgeProvider(providerTypeId: String?): Boolean =
        providerTypeId?.trim()?.equals(PROVIDER_TYPE_ID, ignoreCase = true) == true

    fun isBridgeConfig(config: ModelConfigData?): Boolean =
        isBridgeProvider(config?.apiProviderTypeId)

    fun localConversationTitle(text: String, attachmentNames: List<String> = emptyList()): String {
        val source =
            text.trim().takeIf { it.isNotBlank() }
                ?: attachmentNames.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: "兰儿桥接对话"
        val firstLine = source.lineSequence().firstOrNull().orEmpty().trim()
        return if (firstLine.length <= 24) firstLine else firstLine.take(24) + "…"
    }
}
