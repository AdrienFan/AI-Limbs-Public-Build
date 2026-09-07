package com.ai.assistance.operit.core.tools.defaultTool.standard

/**
 * Base-side Linux filesystem contract.
 *
 * It deliberately contains no concrete environment, rootfs, SSH or transport implementation.
 * The active System Environment provider owns concrete filesystem/provider selection.
 */
interface LinuxFileSystemProvider {
    data class FileInfo(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val permissions: String,
        val lastModified: String
    )

    data class OperationResult(
        val success: Boolean,
        val message: String = ""
    )

    suspend fun readFile(path: String): String?
    suspend fun readFileWithLimit(path: String, maxBytes: Int): String?
    suspend fun readFileLines(path: String, startLine: Int, endLine: Int): String?
    suspend fun readFileSample(path: String, sampleSize: Int = 512): ByteArray?
    suspend fun readFileBytes(path: String): ByteArray?
    suspend fun writeFile(path: String, content: String, append: Boolean = false): OperationResult
    suspend fun writeFileBytes(path: String, bytes: ByteArray): OperationResult
    suspend fun listDirectory(path: String): List<FileInfo>?
    suspend fun exists(path: String): Boolean
    suspend fun isDirectory(path: String): Boolean
    suspend fun isFile(path: String): Boolean
    suspend fun getFileSize(path: String): Long
    suspend fun getLineCount(path: String): Int
    suspend fun createDirectory(path: String, createParents: Boolean = false): OperationResult
    suspend fun delete(path: String, recursive: Boolean = false): OperationResult
    suspend fun move(sourcePath: String, destPath: String): OperationResult
    suspend fun copy(sourcePath: String, destPath: String, recursive: Boolean = true): OperationResult
    suspend fun zip(sourcePath: String, destPath: String, includeRootDirectory: Boolean = true): OperationResult
    suspend fun unzip(sourcePath: String, destPath: String): OperationResult
    suspend fun findFiles(
        basePath: String,
        pattern: String,
        maxDepth: Int = -1,
        caseInsensitive: Boolean = false
    ): List<String>
    suspend fun getFileInfo(path: String): FileInfo?
}
