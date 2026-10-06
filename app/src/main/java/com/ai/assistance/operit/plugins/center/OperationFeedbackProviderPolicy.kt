package com.ai.assistance.operit.plugins.center

/** Feedback is a bounded sideband; ordinary Provider operations retain their business budget. */
internal object OperationFeedbackProviderPolicy {
    const val TIMEOUT_MS = 6_000
    fun isFeedback(metadata: Map<String, String>): Boolean =
        metadata["kind"] == "operation_feedback" && metadata["feedback_api"] == "1" &&
            metadata["event"] == "screen_interaction"
    fun timeoutMs(metadata: Map<String, String>, businessTimeoutMs: Int): Int =
        if (isFeedback(metadata)) TIMEOUT_MS else businessTimeoutMs
}
