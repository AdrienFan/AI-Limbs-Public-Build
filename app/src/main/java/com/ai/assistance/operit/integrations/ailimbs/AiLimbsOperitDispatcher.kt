package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.util.stream.StreamCollector
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

class AiLimbsOperitDispatcher(context: Context) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val documents = AiLimbsDocumentProvider(appContext)
    private val gson = Gson()

    suspend fun execute(tool: String, args: JSONObject): JSONObject = when (tool) {
        "ai_limbs.access_prompt.read", "laner.access_prompt.read" -> ok().put("path", AiLimbsDocumentProvider.ACCESS_PROMPT_PATH).put("content", documents.readAccessPrompt())
        "ai_limbs.access_prompt.write", "laner.access_prompt.write" -> {
            documents.writeAccessPrompt(args.optString("content"))
            ok().put("path", AiLimbsDocumentProvider.ACCESS_PROMPT_PATH)
        }
        "ai_limbs.work_manual.read", "laner.work_manual.read" -> ok().put("path", AiLimbsDocumentProvider.WORK_MANUAL_PATH).put("content", documents.readWorkManual())
        "ai_limbs.work_manual.write", "laner.work_manual.write" -> {
            documents.writeWorkManual(args.optString("content"))
            ok().put("path", AiLimbsDocumentProvider.WORK_MANUAL_PATH)
        }
        "operit.tools.list" -> {
            handler.registerDefaultTools()
            val names = JSONArray()
            handler.getAllToolNames().forEach { names.put(it) }
            ok().put("tools", names).put("count", names.length())
        }
        "operit.tool.execute" -> executeOperitTool(args)
        else -> error("Unknown AI Limbs tool: $tool")
    }

    private suspend fun executeOperitTool(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (name.isBlank()) return error("Missing Operit tool name")
        val paramsObject = args.optJSONObject("parameters") ?: JSONObject()
        val params = mutableListOf<ToolParameter>()
        val keys = paramsObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            params += ToolParameter(key, paramsObject.opt(key)?.toString() ?: "")
        }
        val aiTool = AITool(name = name, parameters = params)
        val invocation = ToolInvocation(aiTool, rawText = "<ai-limbs-direct-tool/>", responseLocation = 0..0)
        val emitted = mutableListOf<String>()
        val results = ToolExecutionManager.executeInvocations(
            invocations = listOf(invocation),
            context = appContext,
            toolHandler = handler,
            packageManager = handler.getOrCreatePackageManager(),
            callerName = "AI Limbs Bridge",
            collector = object : StreamCollector<String> {
                override suspend fun emit(value: String) { emitted += value }
            }
        )
        val result = results.firstOrNull() ?: return error("Operit tool returned no result")
        return JSONObject()
            .put("success", result.success)
            .put("tool", result.toolName)
            .put("result", parseJsonOrString(gson.toJson(result.result)))
            .put("error", result.error ?: JSONObject.NULL)
            .put("events", JSONArray(emitted))
    }

    private fun parseJsonOrString(raw: String): Any = try {
        when {
            raw.startsWith("{") -> JSONObject(raw)
            raw.startsWith("[") -> JSONArray(raw)
            else -> raw
        }
    } catch (_: Exception) { raw }

    private fun ok() = JSONObject().put("success", true)
    private fun error(message: String) = JSONObject().put("success", false).put("error", message)
}
