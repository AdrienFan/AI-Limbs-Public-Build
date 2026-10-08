package com.ai.assistance.operit.core.tools.defaultTool

import com.ai.assistance.operit.data.model.AITool

/** Generic presentation policy, independent of the caller plugin and image-feedback policy. */
internal object UiAutomationTouchFeedback {
    fun enabled(tool: AITool): Boolean {
        val value = tool.parameters.find { it.name == "show_touch_feedback" }?.value ?: return true
        return requireNotNull(value.toBooleanStrictOrNull()) { "show_touch_feedback must be true or false" }
    }
}
