package com.ai.assistance.operit.integrations.ailimbs.chat

import java.util.concurrent.ConcurrentHashMap

/** One-shot UI priority handoff for the next Laner Chat request in each chat. */
object LanerChatDraftPriorityStore {
    private val priorities = ConcurrentHashMap<String, LanerChatPriority>()

    fun set(chatId: String, priority: LanerChatPriority) {
        val normalized = chatId.trim()
        if (normalized.isNotEmpty()) priorities[normalized] = priority
    }

    fun peek(chatId: String): LanerChatPriority {
        val normalized = chatId.trim()
        if (normalized.isEmpty()) return LanerChatPriority.NORMAL
        return priorities[normalized] ?: LanerChatPriority.NORMAL
    }

    fun consume(chatId: String): LanerChatPriority {
        val normalized = chatId.trim()
        if (normalized.isEmpty()) return LanerChatPriority.NORMAL
        return priorities.remove(normalized) ?: LanerChatPriority.NORMAL
    }
}
