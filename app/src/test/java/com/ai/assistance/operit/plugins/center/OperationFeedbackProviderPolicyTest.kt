package com.ai.assistance.operit.plugins.center

import org.junit.Assert.*
import org.junit.Test

class OperationFeedbackProviderPolicyTest {
    @Test fun feedbackWireCannotKeepTheWholeOperationWaitingForTheBusinessTimeout() {
        val metadata = mapOf("kind" to "operation_feedback", "feedback_api" to "1", "event" to "screen_interaction")
        assertTrue(OperationFeedbackProviderPolicy.isFeedback(metadata))
        assertEquals(6_000, OperationFeedbackProviderPolicy.timeoutMs(metadata, 180_000))
    }
    @Test fun ordinaryAndUnknownVersionProvidersKeepTheirExistingBudget() {
        for (metadata in listOf(emptyMap(), mapOf("kind" to "ui_state"),
            mapOf("kind" to "operation_feedback", "feedback_api" to "2", "event" to "screen_interaction"))) {
            assertFalse(OperationFeedbackProviderPolicy.isFeedback(metadata))
            assertEquals(180_000, OperationFeedbackProviderPolicy.timeoutMs(metadata, 180_000))
        }
    }
}
