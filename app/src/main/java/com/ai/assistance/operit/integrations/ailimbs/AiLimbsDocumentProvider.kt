package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.os.Environment
import com.ai.assistance.operit.core.tools.FileContentData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import java.io.File

/**
 * Canonical AI Limbs documents live inside Operit's Linux/PRoot environment.
 * The Android toolbox edits exactly the same files that Terminal sees under /root.
 */
class AiLimbsDocumentProvider(context: Context) {
    private val appContext = context.applicationContext
    private val fileSystemTools by lazy { ToolGetter.getFileSystemTools(appContext) }

    suspend fun readAccessPrompt(): String =
        readSharedText(ACCESS_PROMPT_PATH, LEGACY_ACCESS_PROMPT_FILE)

    suspend fun readWorkManual(): String =
        readSharedText(WORK_MANUAL_PATH, LEGACY_WORK_MANUAL_FILE)

    suspend fun writeAccessPrompt(content: String) =
        writeSharedText(ACCESS_PROMPT_PATH, content)

    suspend fun writeWorkManual(content: String) =
        writeSharedText(WORK_MANUAL_PATH, content)

    private suspend fun readSharedText(path: String, legacyFileName: String): String {
        val result = fileSystemTools.readFileFull(linuxTool("read_file_full", path))
        if (result.success) {
            return resultText(result)
        }

        val legacyFile = File(legacyDocsDir(), legacyFileName)
        if (legacyFile.isFile) {
            val content = legacyFile.readText()
            writeSharedText(path, content)
            return content
        }

        if (isMissingFileError(result.error)) {
            return ""
        }
        throw IllegalStateException(result.error ?: "Unable to read $path")
    }

    private suspend fun writeSharedText(path: String, content: String) {
        ensureLinuxDocsDirectory()
        val result =
            fileSystemTools.writeFile(
                AITool(
                    name = "write_file",
                    parameters =
                        listOf(
                            ToolParameter("path", path),
                            ToolParameter("content", content),
                            ToolParameter("append", "false"),
                            ToolParameter("environment", LINUX_ENVIRONMENT)
                        )
                )
            )
        if (!result.success) {
            throw IllegalStateException(result.error ?: "Unable to write $path")
        }
    }

    private suspend fun ensureLinuxDocsDirectory() {
        val result =
            fileSystemTools.makeDirectory(
                linuxTool("make_directory", ACCESS_PROMPT_DIRECTORY)
            )
        if (!result.success && !result.error.orEmpty().contains("exist", ignoreCase = true)) {
            throw IllegalStateException(
                result.error ?: "Unable to create $ACCESS_PROMPT_DIRECTORY"
            )
        }
    }

    private fun linuxTool(name: String, path: String): AITool =
        AITool(
            name = name,
            parameters =
                listOf(
                    ToolParameter("path", path),
                    ToolParameter("environment", LINUX_ENVIRONMENT),
                    ToolParameter("text_only", "true")
                )
        )

    private fun resultText(result: ToolResult): String =
        when (val data = result.result) {
            is FileContentData -> data.content
            is StringResultData -> data.value
            else -> data.toString()
        }

    private fun isMissingFileError(error: String?): Boolean {
        val message = error.orEmpty()
        return message.contains("does not exist", ignoreCase = true) ||
            message.contains("not found", ignoreCase = true) ||
            message.contains("no such file", ignoreCase = true)
    }

    private fun legacyDocsDir(): File =
        File(Environment.getExternalStorageDirectory(), "Laner/docs")

    companion object {
        const val ACCESS_PROMPT_DIRECTORY = "/root/laner/docs"
        const val ACCESS_PROMPT_PATH = "$ACCESS_PROMPT_DIRECTORY/LANER_ACCESS_PROMPT.md"
        const val WORK_MANUAL_PATH = "/root/LANER_WORK_MANUAL.md"

        private const val LINUX_ENVIRONMENT = "linux"
        private const val LEGACY_ACCESS_PROMPT_FILE = "LANER_ACCESS_PROMPT.md"
        private const val LEGACY_WORK_MANUAL_FILE = "LANER_WORK_MANUAL.md"
    }
}
