package com.ai.assistance.operit.plugins.center

/** Feedback is a bounded sideband; ordinary Provider operations retain their business budget. */
internal object OperationFeedbackProviderPolicy {
    const val TIMEOUT_MS = 6_000
    fun isFeedback(metadata: Map<String, String>): Boolean =
        metadata["kind"] == "operation_feedback" && metadata["feedback_api"] == "1" &&
            metadata["event"] == "screen_interaction"
    fun isMessageContext(metadata: Map<String, String>): Boolean =
        metadata["kind"] == "message_context" && metadata["context_api"] == "1" && metadata["event"] == "user_message"
    fun timeoutMs(metadata: Map<String, String>, businessTimeoutMs: Int): Int = when {
        isFeedback(metadata) -> TIMEOUT_MS
        isMessageContext(metadata) -> com.ai.assistance.operit.integrations.ailimbs.AiLimbsMessageContext.TIMEOUT_MS
        else -> businessTimeoutMs
    }
}
