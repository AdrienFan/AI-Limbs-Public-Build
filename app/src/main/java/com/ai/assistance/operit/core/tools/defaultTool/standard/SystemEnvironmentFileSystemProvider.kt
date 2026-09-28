package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.util.Base64
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import org.json.JSONArray
import org.json.JSONObject

internal class SystemEnvironmentFileSystemProvider : LinuxFileSystemProvider {
    override suspend fun readFile(path: String): String? =
        nullableText(call("read_file") { put("path", path) })

    override suspend fun readFileWithLimit(path: String, maxBytes: Int): String? =
        nullableText(call("read_file_with_limit") {
            put("path", path)
            put("max_bytes", maxBytes)
        })

    override suspend fun readFileLines(path: String, startLine: Int, endLine: Int): String? =
        nullableText(call("read_file_lines") {
            put("path", path)
            put("start_line", startLine)
            put("end_line", endLine)
        })

    override suspend fun readFileSample(path: String, sampleSize: Int): ByteArray? =
        nullableBytes(call("read_file_sample") {
            put("path", path)
            put("sample_size", sampleSize)
        })
    override suspend fun readFileBytes(path: String): ByteArray? =
        nullableBytes(call("read_file_bytes") { put("path", path) })

    override suspend fun writeFile(path: String, content: String, append: Boolean): LinuxFileSystemProvider.OperationResult =
        operationResult(call("write_file") {
            put("path", path)
            put("content", content)
            put("append", append)
        })

    override suspend fun writeFileBytes(path: String, bytes: ByteArray): LinuxFileSystemProvider.OperationResult =
        operationResult(call("write_file_bytes") {
            put("path", path)
            put("bytes_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        })

    override suspend fun listDirectory(path: String): List<LinuxFileSystemProvider.FileInfo>? {
        val result = call("list_directory") { put("path", path) }
        if (result.isNull("entries")) return null
        val entries = result.getJSONArray("entries")
        return buildList(entries.length()) {
            for (index in 0 until entries.length()) add(fileInfo(entries.getJSONObject(index)))
        }
    }
    override suspend fun exists(path: String): Boolean =
        call("exists") { put("path", path) }.getBoolean("value")

    override suspend fun isDirectory(path: String): Boolean =
        call("is_directory") { put("path", path) }.getBoolean("value")

    override suspend fun isFile(path: String): Boolean =
        call("is_file") { put("path", path) }.getBoolean("value")

    override suspend fun getFileSize(path: String): Long =
        call("get_file_size") { put("path", path) }.getLong("value")

    override suspend fun getLineCount(path: String): Int =
        call("get_line_count") { put("path", path) }.getInt("value")

    override suspend fun createDirectory(path: String, createParents: Boolean): LinuxFileSystemProvider.OperationResult =
        operationResult(call("create_directory") {
            put("path", path)
            put("create_parents", createParents)
        })

    override suspend fun delete(path: String, recursive: Boolean): LinuxFileSystemProvider.OperationResult =
        operationResult(call("delete") {
            put("path", path)
            put("recursive", recursive)
        })
    override suspend fun move(sourcePath: String, destPath: String): LinuxFileSystemProvider.OperationResult =
        operationResult(call("move") {
            put("source_path", sourcePath)
            put("dest_path", destPath)
        })

    override suspend fun copy(sourcePath: String, destPath: String, recursive: Boolean): LinuxFileSystemProvider.OperationResult =
        operationResult(call("copy") {
            put("source_path", sourcePath)
            put("dest_path", destPath)
            put("recursive", recursive)
        })

    override suspend fun zip(
        sourcePath: String,
        destPath: String,
        includeRootDirectory: Boolean
    ): LinuxFileSystemProvider.OperationResult =
        operationResult(call("zip") {
            put("source_path", sourcePath)
            put("dest_path", destPath)
            put("include_root_directory", includeRootDirectory)
        })

    override suspend fun unzip(
        sourcePath: String,
        destPath: String
    ): LinuxFileSystemProvider.OperationResult =
        operationResult(call("unzip") {
            put("source_path", sourcePath)
            put("dest_path", destPath)
        })

    override suspend fun findFiles(
        basePath: String,
        pattern: String,
        maxDepth: Int,
        caseInsensitive: Boolean
    ): List<String> {
        val result = call("find_files") {
            put("path", basePath)
            put("pattern", pattern)
            put("max_depth", maxDepth)
            put("case_insensitive", caseInsensitive)
        }
        return result.getJSONArray("paths").strings()
    }
    override suspend fun getFileInfo(path: String): LinuxFileSystemProvider.FileInfo? {
        val result = call("get_file_info") { put("path", path) }
        if (result.isNull("file_info")) return null
        return fileInfo(result.getJSONObject("file_info"))
    }

    private suspend fun call(operation: String, block: JSONObject.() -> Unit): JSONObject {
        check(PluginPlatformKernel.isInitialized) { "Plugin platform is not initialized" }
        val request = JSONObject().put("operation", operation).apply(block)
        val result = PluginPlatformKernel.capabilities.invokePlugin(CAPABILITY_ID, request)
        if (!result.optBoolean("success", false)) {
            throw IllegalStateException(result.optString("error").ifBlank {
                result.optString("message").ifBlank { "System environment filesystem operation failed: $operation" }
            })
        }
        return result
    }

    private fun nullableText(result: JSONObject): String? =
        if (result.isNull("value")) null else result.getString("value")

    private fun nullableBytes(result: JSONObject): ByteArray? =
        if (result.isNull("bytes_base64")) null
        else Base64.decode(result.getString("bytes_base64"), Base64.DEFAULT)
    private fun operationResult(result: JSONObject) =
        LinuxFileSystemProvider.OperationResult(
            success = result.optBoolean("operation_success", false),
            message = result.optString("message")
        )

    private fun fileInfo(value: JSONObject) =
        LinuxFileSystemProvider.FileInfo(
            name = value.getString("name"),
            isDirectory = value.getBoolean("is_directory"),
            size = value.getLong("size"),
            permissions = value.optString("permissions"),
            lastModified = value.optString("last_modified")
        )

    private fun JSONArray.strings(): List<String> =
        buildList(length()) {
            for (index in 0 until length()) add(getString(index))
        }

    private companion object {
        const val CAPABILITY_ID = "plugin.system_environment.filesystem"
    }
}
