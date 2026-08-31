package com.ai.assistance.operit.plugins.runtime

import android.content.Context
import android.os.Environment
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject

class PluginLabToolHost private constructor(context: Context) : PluginToolHost {
    private val appContext = context.applicationContext

    override fun executeTool(tool: AITool): ToolResult = when (tool.name.substringAfterLast(':')) {
        "list_files" -> listFiles(tool)
        "read_file_full" -> readFileFull(tool)
        else -> failure(tool.name, "Tool is not exposed by Plugin Lab: ${tool.name}")
    }

    override fun executeToolAndStream(tool: AITool): Flow<ToolResult> = flowOf(executeTool(tool))

    private fun listFiles(tool: AITool): ToolResult {
        val file = resolveReadablePath(tool.parameter("path"))
            ?: return failure(tool.name, "Path is outside Plugin Lab readable roots")
        if (!file.isDirectory) return failure(tool.name, "Not a directory: ${file.path}")
        val entries = JSONArray()
        file.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
            entries.put(
                JSONObject()
                    .put("name", child.name)
                    .put("path", child.absolutePath)
                    .put("isDirectory", child.isDirectory)
                    .put("size", if (child.isFile) child.length() else 0L)
                    .put("lastModified", DATE_FORMAT.format(Date(child.lastModified())))
            )
        }
        val payload = JSONObject().put("path", file.absolutePath).put("entries", entries)
        return success(tool.name, payload.toString())
    }

    private fun readFileFull(tool: AITool): ToolResult {
        val file = resolveReadablePath(tool.parameter("path"))
            ?: return failure(tool.name, "Path is outside Plugin Lab readable roots")
        if (!file.isFile) return failure(tool.name, "Not a file: ${file.path}")
        if (file.length() > MAX_TEXT_FILE_BYTES) return failure(tool.name, "File exceeds Plugin Lab read limit")
        val payload = JSONObject()
            .put("path", file.absolutePath)
            .put("content", file.readText())
            .put("size", file.length())
        return success(tool.name, payload.toString())
    }

    private fun resolveReadablePath(rawPath: String?): File? {
        val path = rawPath?.trim().orEmpty()
        if (path.isBlank()) return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { target -> READABLE_ROOTS.any { root -> target == root || target.path.startsWith(root.path + File.separator) } }
    }

    private fun AITool.parameter(name: String): String? =
        parameters.firstOrNull { it.name == name }?.value

    private fun success(toolName: String, payload: String) =
        ToolResult(toolName = toolName, success = true, result = StringResultData(payload))

    private fun failure(toolName: String, message: String) =
        ToolResult(toolName = toolName, success = false, result = StringResultData(""), error = message)

    companion object {
        private const val MAX_TEXT_FILE_BYTES = 8L * 1024L * 1024L
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val READABLE_ROOTS: List<File> by lazy {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            listOf(File(downloads, "AiLimbs"), File(downloads, "Operit")).map { it.canonicalFile }
        }
        @Volatile private var INSTANCE: PluginLabToolHost? = null
        fun getInstance(context: Context): PluginLabToolHost = INSTANCE ?: synchronized(this) {
            INSTANCE ?: PluginLabToolHost(context.applicationContext).also { INSTANCE = it }
        }
    }
}
