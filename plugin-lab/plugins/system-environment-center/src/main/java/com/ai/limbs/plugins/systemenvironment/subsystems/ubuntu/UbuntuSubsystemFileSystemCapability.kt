package com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu

import android.util.Base64
import com.ai.limbs.plugin.runtime.InProcessCapabilityDomain
import com.ai.limbs.plugin.runtime.InProcessCapabilityEffect
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilityParameterSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilityReceipt
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.provider.filesystem.FileSystemProvider
import com.ai.limbs.plugins.systemenvironment.subsystems.ubuntu.runtime.terminal.provider.filesystem.LocalFileSystemProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object UbuntuSubsystemFileSystemCapability {
    const val ID = "plugin.system_environment.ubuntu.filesystem"

    fun register(host: InProcessPluginHost, terminal: TerminalManager) {
        host.registerCapability(
            InProcessCapabilitySpec(
                id = ID,
                displayName = "Ubuntu 文件系统",
                description = "访问当前 System Environment Provider 的文件系统；Ubuntu 本地模式使用插件 rootfs，SSH 模式使用当前 SSH/SFTP 文件系统。",
                keywords = listOf("Ubuntu", "Linux", "文件系统", "SSH", "SFTP", "zip", "unzip"),
                parameters = listOf(
                    param("operation", "string", "文件系统操作名"),
                    param("path", "string", "目标路径", false),
                    param("source_path", "string", "源路径", false),
                    param("dest_path", "string", "目标路径", false),
                    param("max_bytes", "integer", "最大读取字节数", false),
                    param("start_line", "integer", "起始行（1-based）", false),
                    param("end_line", "integer", "结束行（包含）", false),
                    param("sample_size", "integer", "样本字节数", false),
                    param("content", "string", "文本写入内容", false),
                    param("append", "boolean", "是否追加写入", false),
                    param("bytes_base64", "string", "二进制内容 Base64", false),
                    param("create_parents", "boolean", "是否递归创建父目录", false),
                    param("recursive", "boolean", "递归删除或复制", false),
                    param("pattern", "string", "文件查找 glob 模式", false),
                    param("max_depth", "integer", "最大查找深度，-1 为不限", false),
                    param("case_insensitive", "boolean", "查找时是否忽略大小写", false),
                    param("include_root_directory", "boolean", "压缩目录时是否包含根目录", false),
                    param("work_context", "boolean", "仅当本次文件操作属于开发/调试/环境管理或项目设备内容变更工作时设为 true", false)
                ),
                effect = InProcessCapabilityEffect.PERSISTENT_WRITE,
                domain = InProcessCapabilityDomain.SYSTEM_ENVIRONMENT,
                workContextRequiredReceipts = setOf(InProcessCapabilityReceipt.WORK_MANUAL),
                executor = InProcessCapabilityExecutor { raw ->
                    val request = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                    runCatching { execute(terminal, request) }
                        .getOrElse { error ->
                            JSONObject()
                                .put("success", false)
                                .put("error", error.message ?: error::class.java.simpleName)
                        }
                        .toString()
                }
            )
        )
    }

    private fun param(
        name: String,
        type: String,
        description: String,
        required: Boolean = true
    ) = InProcessCapabilityParameterSpec(name, type, description, required)

    private suspend fun execute(terminal: TerminalManager, request: JSONObject): JSONObject {
        val fs = terminal.getFileSystemProvider()
        val operationName = request.requiredText("operation")
        val trackingRegistered = terminal.registerHiddenAiOperation()
        val trackingId = if (trackingRegistered) {
            terminal.beginSharedHiddenOperation(filesystemOperationLabel(operationName, request))
        } else {
            null
        }

        return try {
            val result = when (operationName) {
                "read_file" -> nullableText(fs.readFile(request.requiredText("path")))
                "read_file_with_limit" -> nullableText(
                    fs.readFileWithLimit(request.requiredText("path"), request.requiredInt("max_bytes"))
                )
                "read_file_lines" -> nullableText(
                    fs.readFileLines(
                        request.requiredText("path"),
                        request.requiredInt("start_line"),
                        request.requiredInt("end_line")
                    )
                )
                "read_file_sample" -> nullableBytes(
                    fs.readFileSample(
                        request.requiredText("path"),
                        request.optInt("sample_size", 512)
                    )
                )
                "read_file_bytes" -> nullableBytes(fs.readFileBytes(request.requiredText("path")))
                "write_file" -> operation(
                    fs.writeFile(
                        request.requiredText("path"),
                        request.optString("content"),
                        request.optBoolean("append", false)
                    )
                )
                "write_file_bytes" -> operation(
                    fs.writeFileBytes(
                        request.requiredText("path"),
                        Base64.decode(request.requiredText("bytes_base64"), Base64.DEFAULT)
                    )
                )
                "list_directory" -> listDirectory(fs.listDirectory(request.requiredText("path")))
                "exists" -> ok().put("value", fs.exists(request.requiredText("path")))
                "is_directory" -> ok().put("value", fs.isDirectory(request.requiredText("path")))
                "is_file" -> ok().put("value", fs.isFile(request.requiredText("path")))
                "get_file_size" -> ok().put("value", fs.getFileSize(request.requiredText("path")))
                "get_line_count" -> ok().put("value", fs.getLineCount(request.requiredText("path")))
                "create_directory" -> operation(
                    fs.createDirectory(
                        request.requiredText("path"),
                        request.optBoolean("create_parents", false)
                    )
                )
                "delete" -> operation(
                    fs.delete(
                        request.requiredText("path"),
                        request.optBoolean("recursive", false)
                    )
                )
                "move" -> operation(
                    fs.move(request.requiredText("source_path"), request.requiredText("dest_path"))
                )
                "copy" -> operation(
                    fs.copy(
                        request.requiredText("source_path"),
                        request.requiredText("dest_path"),
                        request.optBoolean("recursive", true)
                    )
                )
                "find_files" -> ok().put(
                    "paths",
                    JSONArray(
                        fs.findFiles(
                            request.requiredText("path"),
                            request.requiredText("pattern"),
                            request.optInt("max_depth", -1),
                            request.optBoolean("case_insensitive", false)
                        )
                    )
                )
                "get_file_info" -> fileInfo(fs.getFileInfo(request.requiredText("path")))
                "get_permissions" -> ok().put("value", fs.getPermissions(request.requiredText("path")))
                "zip" -> operation(
                    zip(
                        fs = fs,
                        sourcePath = request.requiredText("source_path"),
                        destPath = request.requiredText("dest_path"),
                        includeRootDirectory = request.optBoolean("include_root_directory", true)
                    )
                )
                "unzip" -> operation(
                    unzip(
                        fs = fs,
                        sourcePath = request.requiredText("source_path"),
                        destPath = request.requiredText("dest_path")
                    )
                )
                else -> throw IllegalArgumentException("Unsupported filesystem operation: $operationName")
            }
            trackingId?.let { terminal.finishSharedHiddenOperation(it, result.toString(), null) }
            result
        } catch (error: Throwable) {
            trackingId?.let {
                terminal.finishSharedHiddenOperation(it, null, error.message ?: error::class.java.simpleName)
            }
            throw error
        } finally {
            if (trackingRegistered) terminal.unregisterHiddenAiOperation()
        }
    }

    private suspend fun zip(
        fs: FileSystemProvider,
        sourcePath: String,
        destPath: String,
        includeRootDirectory: Boolean
    ): FileSystemProvider.OperationResult =
        if (fs is LocalFileSystemProvider) {
            zipLocal(fs, sourcePath, destPath, includeRootDirectory)
        } else {
            zipProvider(fs, sourcePath, destPath, includeRootDirectory)
        }

    private suspend fun unzip(
        fs: FileSystemProvider,
        sourcePath: String,
        destPath: String
    ): FileSystemProvider.OperationResult =
        if (fs is LocalFileSystemProvider) {
            unzipLocal(fs, sourcePath, destPath)
        } else {
            unzipProvider(fs, sourcePath, destPath)
        }

    private suspend fun zipLocal(
        fs: LocalFileSystemProvider,
        sourcePath: String,
        destPath: String,
        includeRootDirectory: Boolean
    ): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val source = File(fs.resolveHostPathForPlugin(sourcePath))
            val destination = File(fs.resolveHostPathForPlugin(destPath))
            if (!source.exists()) {
                return@withContext failureOperation("Source file or directory does not exist: $sourcePath")
            }
            destination.parentFile?.mkdirs()
            val excludedPath = runCatching { destination.canonicalPath }.getOrNull()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->
                if (source.isDirectory) {
                    if (includeRootDirectory) {
                        addLocalPath(source, source.name.ifBlank { "root" }, output, excludedPath)
                    } else {
                        source.listFiles().orEmpty().forEach { child ->
                            addLocalPath(child, child.name, output, excludedPath)
                        }
                    }
                } else {
                    addLocalPath(source, source.name, output, excludedPath)
                }
            }
            FileSystemProvider.OperationResult(
                success = destination.isFile,
                message = if (destination.isFile) {
                    "Successfully compressed $sourcePath to $destPath"
                } else {
                    "Archive output was not created: $destPath"
                }
            )
        } catch (error: Throwable) {
            failureOperation("Error compressing $sourcePath: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun addLocalPath(
        file: File,
        entryName: String,
        output: ZipOutputStream,
        excludedCanonicalPath: String?
    ) {
        val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
        if (excludedCanonicalPath != null && canonicalPath == excludedCanonicalPath) return
        val normalizedEntry = entryName.replace(File.separatorChar, '/').trimStart('/')
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            if (children.isEmpty() && normalizedEntry.isNotBlank()) {
                output.putNextEntry(ZipEntry(normalizedEntry.trimEnd('/') + "/"))
                output.closeEntry()
            } else {
                children.forEach { child ->
                    val childEntry = if (normalizedEntry.isBlank()) child.name else "$normalizedEntry/${child.name}"
                    addLocalPath(child, childEntry, output, excludedCanonicalPath)
                }
            }
            return
        }
        output.putNextEntry(ZipEntry(normalizedEntry.ifBlank { file.name }))
        BufferedInputStream(FileInputStream(file)).use { input -> input.copyTo(output, ARCHIVE_BUFFER_SIZE) }
        output.closeEntry()
    }

    private suspend fun unzipLocal(
        fs: LocalFileSystemProvider,
        sourcePath: String,
        destPath: String
    ): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val source = File(fs.resolveHostPathForPlugin(sourcePath))
            val destination = File(fs.resolveHostPathForPlugin(destPath))
            if (!source.isFile) {
                return@withContext failureOperation("Zip file does not exist: $sourcePath")
            }
            destination.mkdirs()
            val destinationCanonical = destination.canonicalFile
            val prefix = destinationCanonical.path + File.separator
            ZipInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    val target = File(destination, entry.name).canonicalFile
                    require(target == destinationCanonical || target.path.startsWith(prefix)) {
                        "Unsafe zip entry path: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(target)).use { output ->
                            input.copyTo(output, ARCHIVE_BUFFER_SIZE)
                        }
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
            FileSystemProvider.OperationResult(true, "Successfully extracted $sourcePath to $destPath")
        } catch (error: Throwable) {
            failureOperation("Error extracting $sourcePath: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private suspend fun zipProvider(
        fs: FileSystemProvider,
        sourcePath: String,
        destPath: String,
        includeRootDirectory: Boolean
    ): FileSystemProvider.OperationResult {
        if (!fs.exists(sourcePath)) return failureOperation("Source file or directory does not exist: $sourcePath")
        return try {
            val bytes = ByteArrayOutputStream()
            val budget = ArchiveBudget()
            ZipOutputStream(bytes).use { output ->
                if (fs.isDirectory(sourcePath)) {
                    val entries = fs.listDirectory(sourcePath)
                        ?: throw IllegalStateException("Could not list source directory: $sourcePath")
                    if (includeRootDirectory) {
                        val rootName = logicalName(sourcePath).ifBlank { "root" }
                        if (entries.isEmpty()) {
                            output.putNextEntry(ZipEntry("$rootName/"))
                            output.closeEntry()
                        } else {
                            entries.forEach { item ->
                                addProviderPath(
                                    fs,
                                    joinLinuxPath(sourcePath, item.name),
                                    "$rootName/${item.name}",
                                    output,
                                    budget
                                )
                            }
                        }
                    } else {
                        entries.forEach { item ->
                            addProviderPath(
                                fs,
                                joinLinuxPath(sourcePath, item.name),
                                item.name,
                                output,
                                budget
                            )
                        }
                    }
                } else {
                    addProviderPath(fs, sourcePath, logicalName(sourcePath), output, budget)
                }
            }
            val archive = bytes.toByteArray()
            require(archive.size.toLong() <= MAX_PROVIDER_ARCHIVE_BYTES) {
                "Archive exceeds provider transfer limit of ${MAX_PROVIDER_ARCHIVE_BYTES / (1024 * 1024)} MiB"
            }
            val written = fs.writeFileBytes(destPath, archive)
            FileSystemProvider.OperationResult(
                written.success,
                if (written.success) "Successfully compressed $sourcePath to $destPath" else written.message
            )
        } catch (error: Throwable) {
            failureOperation("Error compressing $sourcePath: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private suspend fun addProviderPath(
        fs: FileSystemProvider,
        path: String,
        entryName: String,
        output: ZipOutputStream,
        budget: ArchiveBudget
    ) {
        if (fs.isDirectory(path)) {
            val children = fs.listDirectory(path)
                ?: throw IllegalStateException("Could not list directory: $path")
            if (children.isEmpty()) {
                output.putNextEntry(ZipEntry(entryName.trimEnd('/') + "/"))
                output.closeEntry()
            } else {
                children.forEach { child ->
                    addProviderPath(
                        fs,
                        joinLinuxPath(path, child.name),
                        "${entryName.trimEnd('/')}/${child.name}",
                        output,
                        budget
                    )
                }
            }
            return
        }
        val content = fs.readFileBytes(path)
            ?: throw IllegalStateException("Could not read file: $path")
        budget.add(content.size.toLong())
        output.putNextEntry(ZipEntry(entryName.trimStart('/')))
        output.write(content)
        output.closeEntry()
    }

    private suspend fun unzipProvider(
        fs: FileSystemProvider,
        sourcePath: String,
        destPath: String
    ): FileSystemProvider.OperationResult {
        val archive = fs.readFileBytes(sourcePath)
            ?: return failureOperation("Zip file does not exist or cannot be read: $sourcePath")
        if (archive.size.toLong() > MAX_PROVIDER_ARCHIVE_BYTES) {
            return failureOperation(
                "Archive exceeds provider transfer limit of ${MAX_PROVIDER_ARCHIVE_BYTES / (1024 * 1024)} MiB"
            )
        }
        return try {
            ensureProviderDirectory(fs, destPath)
            val budget = ArchiveBudget()
            ZipInputStream(ByteArrayInputStream(archive)).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    val relative = safeZipEntryName(entry.name)
                    if (relative.isNotBlank()) {
                        val target = joinLinuxPath(destPath, relative)
                        if (entry.isDirectory) {
                            ensureProviderDirectory(fs, target)
                        } else {
                            parentLinuxPath(target)?.let { ensureProviderDirectory(fs, it) }
                            val content = ByteArrayOutputStream()
                            val buffer = ByteArray(ARCHIVE_BUFFER_SIZE)
                            var read: Int
                            while (input.read(buffer).also { read = it } > 0) {
                                budget.add(read.toLong())
                                content.write(buffer, 0, read)
                            }
                            val written = fs.writeFileBytes(target, content.toByteArray())
                            require(written.success) { written.message.ifBlank { "Could not write $target" } }
                        }
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
            FileSystemProvider.OperationResult(true, "Successfully extracted $sourcePath to $destPath")
        } catch (error: Throwable) {
            failureOperation("Error extracting $sourcePath: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private suspend fun ensureProviderDirectory(fs: FileSystemProvider, path: String) {
        if (path.isBlank() || fs.exists(path)) return
        val result = fs.createDirectory(path, createParents = true)
        require(result.success) { result.message.ifBlank { "Could not create directory: $path" } }
    }

    private fun safeZipEntryName(rawName: String): String {
        require(!rawName.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.matches(rawName)) {
            "Unsafe absolute zip entry path: $rawName"
        }
        val parts = rawName.replace('\\', '/').split('/')
            .filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Unsafe zip entry path: $rawName" }
        return parts.joinToString("/")
    }

    private fun logicalName(path: String): String =
        path.trim().trimEnd('/').substringAfterLast('/').ifBlank { "root" }

    private fun joinLinuxPath(parent: String, child: String): String {
        val base = parent.trimEnd('/')
        return if (base.isBlank()) "/${child.trimStart('/')}" else "$base/${child.trimStart('/')}"
    }

    private fun parentLinuxPath(path: String): String? {
        val normalized = path.trimEnd('/')
        val index = normalized.lastIndexOf('/')
        return when {
            index < 0 -> null
            index == 0 -> "/"
            else -> normalized.substring(0, index)
        }
    }

    private fun filesystemOperationLabel(operation: String, request: JSONObject): String {
        val primary = sequenceOf("path", "source_path", "dest_path")
            .map { request.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return if (primary.isBlank()) operation else "$operation $primary"
    }

    private fun nullableText(value: String?): JSONObject =
        ok().put("value", value ?: JSONObject.NULL)

    private fun nullableBytes(value: ByteArray?): JSONObject =
        ok().put(
            "bytes_base64",
            value?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL
        )

    private fun operation(result: FileSystemProvider.OperationResult): JSONObject =
        ok()
            .put("operation_success", result.success)
            .put("message", result.message)

    private fun failureOperation(message: String) =
        FileSystemProvider.OperationResult(success = false, message = message)

    private fun listDirectory(items: List<FileSystemProvider.FileInfo>?): JSONObject {
        if (items == null) return ok().put("entries", JSONObject.NULL)
        val array = JSONArray()
        items.forEach { array.put(fileInfoJson(it)) }
        return ok().put("entries", array)
    }

    private fun fileInfo(info: FileSystemProvider.FileInfo?): JSONObject =
        ok().put("file_info", info?.let(::fileInfoJson) ?: JSONObject.NULL)

    private fun fileInfoJson(info: FileSystemProvider.FileInfo): JSONObject =
        JSONObject()
            .put("name", info.name)
            .put("is_directory", info.isDirectory)
            .put("size", info.size)
            .put("permissions", info.permissions)
            .put("last_modified", info.lastModified)

    private fun ok(): JSONObject = JSONObject().put("success", true)

    private fun JSONObject.requiredText(key: String): String =
        optString(key).trim().ifBlank { throw IllegalArgumentException("$key is required") }

    private fun JSONObject.requiredInt(key: String): Int {
        require(has(key)) { "$key is required" }
        return getInt(key)
    }

    private data class ArchiveBudget(var bytes: Long = 0L) {
        fun add(count: Long) {
            bytes += count
            require(bytes <= MAX_PROVIDER_ARCHIVE_BYTES) {
                "Archive transfer exceeds ${MAX_PROVIDER_ARCHIVE_BYTES / (1024 * 1024)} MiB provider limit"
            }
        }
    }

    private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
    private const val ARCHIVE_BUFFER_SIZE = 64 * 1024
    private const val MAX_PROVIDER_ARCHIVE_BYTES = 256L * 1024L * 1024L
}
