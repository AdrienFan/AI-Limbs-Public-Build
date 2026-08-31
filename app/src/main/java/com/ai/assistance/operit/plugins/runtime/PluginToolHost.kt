package com.ai.assistance.operit.plugins.runtime

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.flow.Flow

interface PluginToolHost {
    fun executeTool(tool: AITool): ToolResult
    fun executeToolAndStream(tool: AITool): Flow<ToolResult>
}
