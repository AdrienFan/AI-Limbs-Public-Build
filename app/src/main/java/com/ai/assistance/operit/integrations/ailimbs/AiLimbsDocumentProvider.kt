package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AiLimbsDocumentId(
    val stableId: String,
    internal val fileName: String
) {
    SYSTEM_ACCESS_PROMPT("system_access_prompt", "AI_LIMBS_SYSTEM_ACCESS_PROMPT.md"),
    CUSTOM_ACCESS_PROMPT("custom_access_prompt", "AI_LIMBS_CUSTOM_ACCESS_PROMPT.md"),
    WORK_MANUAL("work_manual", "LANER_WORK_MANUAL.md"),
    TOOL_MANUAL("tool_manual", "LANER_TOOL_MANUAL.md")
}

data class AiLimbsDocumentReference(
    val documentId: String,
    val path: String,
    val version: String,
    val isEmpty: Boolean
)

data class AiLimbsDocumentSnapshot(
    val id: String,
    val createdAtEpochMillis: Long,
    val sha256: String
)

/**
 * Owns AI Limbs documents and their history inside the Android application sandbox.
 *
 * Ubuntu is an optional execution environment. Document reads, writes, history, and recovery must
 * never create a terminal manager or start PRoot. Linux paths are read only during the explicit
 * pre-V0.5.4 migration.
 */
class AiLimbsDocumentProvider(context: Context) {
    private val appContext = context.applicationContext
    private val documentsDirectory = File(appContext.filesDir, DOCUMENTS_DIRECTORY)
    private val backupsDirectory = File(documentsDirectory, BACKUPS_DIRECTORY)
    private val legacyArchiveDirectory = File(documentsDirectory, LEGACY_ARCHIVE_DIRECTORY)
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var documentsReady = false

    private val workManualPath: String
        get() = documentFile(AiLimbsDocumentId.WORK_MANUAL).absolutePath

    private val toolManualPath: String
        get() = documentFile(AiLimbsDocumentId.TOOL_MANUAL).absolutePath

    suspend fun readSystemAccessPrompt(): String =
        readEditableDocument(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT)

    suspend fun readCustomAccessPrompt(): String =
        readEditableDocument(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)

    // Compatibility alias for callers that still use the pre-V0.6.3.2 name.
    suspend fun readAccessPrompt(): String = readCustomAccessPrompt()

    suspend fun readWorkManual(): String =
        readEditableDocument(AiLimbsDocumentId.WORK_MANUAL)

    suspend fun readToolManual(): String =
        readEditableDocument(AiLimbsDocumentId.TOOL_MANUAL)

    suspend fun readWorkManualForAgent(): String {
        val body = readWorkManual()
        return buildString {
            append(protectedWorkManualHeader())
            appendLine()
            appendLine(PROTECTED_HEADER_END_MARKER)
            if (body.isNotBlank()) {
                appendLine()
                append(body)
            }
        }
    }

    suspend fun writeSystemAccessPrompt(content: String): Boolean {
        require(content.isNotBlank()) { "AI Limbs system access prompt must not be empty" }
        return writeEditableDocument(AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT, content)
    }

    suspend fun writeCustomAccessPrompt(content: String): Boolean =
        writeEditableDocument(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT, content)

    // Compatibility alias for callers that still use the pre-V0.6.3.2 name.
    suspend fun writeAccessPrompt(content: String): Boolean = writeCustomAccessPrompt(content)

    suspend fun writeWorkManual(content: String): Boolean =
        writeEditableDocument(
            AiLimbsDocumentId.WORK_MANUAL,
            extractEditableWorkManualBody(content)
        )

    suspend fun writeToolManual(content: String): Boolean =
        writeEditableDocument(AiLimbsDocumentId.TOOL_MANUAL, content)

    fun normalizeWorkManualImport(content: String): String =
        extractEditableWorkManualBody(content)

    suspend fun readEditableDocument(documentId: AiLimbsDocumentId): String =
        withContext(Dispatchers.IO) {
            documentMutex.withLock {
                ensureDocumentsReadyLocked()
                documentFile(documentId).takeIf { it.isFile }?.readText().orEmpty()
            }
        }

    suspend fun documentReference(documentId: AiLimbsDocumentId): AiLimbsDocumentReference =
        withContext(Dispatchers.IO) {
            documentMutex.withLock {
                ensureDocumentsReadyLocked()
                val file = documentFile(documentId)
                val content = file.takeIf { it.isFile }?.readText().orEmpty()
                AiLimbsDocumentReference(
                    documentId = documentId.stableId,
                    path = file.absolutePath,
                    version = "sha256:${sha256(content).take(16)}",
                    isEmpty = content.isBlank()
                )
            }
        }

    /**
     * A changed save archives the previous active body before replacing it. The protected work
     * manual header is generated by code and therefore never enters the writable document body.
     */
    suspend fun writeEditableDocument(
        documentId: AiLimbsDocumentId,
        content: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            documentMutex.withLock {
                ensureDocumentsReadyLocked()
                val destination = documentFile(documentId)
                val current = destination.takeIf { it.isFile }?.readText().orEmpty()
                if (current == content) {
                    return@withLock false
                }
                if (destination.isFile) {
                    createSnapshotLocked(documentId, current)
                }
                writeAtomically(destination, content)
                true
            }
        }

    suspend fun listSnapshots(documentId: AiLimbsDocumentId): List<AiLimbsDocumentSnapshot> =
        withContext(Dispatchers.IO) {
            documentMutex.withLock {
                ensureDocumentsReadyLocked()
                listSnapshotsLocked(documentId)
            }
        }

    suspend fun restoreSnapshot(
        documentId: AiLimbsDocumentId,
        snapshotId: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            documentMutex.withLock {
                ensureDocumentsReadyLocked()
                val snapshot =
                    snapshotDirectory(documentId)
                        .listFiles()
                        ?.firstOrNull { it.isFile && it.name == snapshotId }
                        ?: throw IllegalArgumentException("Unknown AI Limbs document snapshot")
                val restoredContent = snapshot.readText()
                val destination = documentFile(documentId)
                val current = destination.takeIf { it.isFile }?.readText().orEmpty()
                if (current == restoredContent) {
                    return@withLock false
                }
                if (destination.isFile) {
                    createSnapshotLocked(documentId, current)
                }
                writeAtomically(destination, restoredContent)
                true
            }
        }

    private fun protectedWorkManualHeader(): String =
        """
        # LANER WORK MANUAL
        # 兰儿手机端工作手册

        ## 1. 手册定位

        本文件是兰儿在 AI Limbs 中的唯一正式工作手册。

        唯一正式路径：

        $workManualPath

        正式读取工具：ai_limbs.work_manual.read
        正式保存工具：ai_limbs.work_manual.write（只提交 editable_content 正文）

        本节由 AI Limbs 生成，不能通过编辑器或文档写入接口修改。
        不得另外建立同类工作手册。

        ## 2. 工具手册

        唯一正式工具手册：

        $toolManualPath

        正式读取工具：ai_limbs.tool_manual.read
        正式保存工具：ai_limbs.tool_manual.write

        本节由 AI Limbs 生成，不能通过编辑器或文档写入接口修改。
        所有开发工具、系统工具、便携工具和重要开发环境必须在工具手册中登记。
        """.trimIndent()

    private fun extractEditableWorkManualBody(content: String): String {
        val markerIndex = content.indexOf(PROTECTED_HEADER_END_MARKER)
        if (markerIndex >= 0) {
            return content
                .substring(markerIndex + PROTECTED_HEADER_END_MARKER.length)
                .trimStart('\r', '\n')
        }
        return extractLegacyWorkManualBody(content)
    }

    private fun ensureDocumentsReadyLocked() {
        if (documentsReady) return
        ensureDirectory(documentsDirectory)
        migrateLegacyDocumentsLocked()
        migrateDocumentSchemaLocked()
        ensureDocumentFilesLocked()
        documentsReady = true
    }

    private fun ensureDocumentFilesLocked() {
        AiLimbsDocumentId.entries.forEach { documentId ->
            val destination = documentFile(documentId)
            if (!destination.exists()) {
                val initialContent =
                    if (documentId == AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT) {
                        appContext.assets.open(SYSTEM_ACCESS_PROMPT_ASSET).bufferedReader().use { it.readText() }
                    } else {
                        ""
                    }
                writeAtomically(destination, initialContent)
            }
        }
    }

    private fun migrateLegacyDocumentsLocked() {
        // Custom Access Prompt and Work Manual are app-owned documents. New installs must never
        // silently import them from Ubuntu or shared storage; users can explicitly import text
        // through Access Manager instead. Tool Manual migration remains until its redesign.
        val ubuntuRoot =
            File(
                appContext.filesDir,
                "usr/var/lib/proot-distro/installed-rootfs/ubuntu"
            )
        migrateDocument(
            destination = documentFile(AiLimbsDocumentId.TOOL_MANUAL),
            sources =
                listOf(
                    File(ubuntuRoot, "root/laner/docs/${AiLimbsDocumentId.TOOL_MANUAL.fileName}"),
                    File(ubuntuRoot, "root/${AiLimbsDocumentId.TOOL_MANUAL.fileName}"),
                    File(legacyDocumentsDirectory(), AiLimbsDocumentId.TOOL_MANUAL.fileName)
                )
        )
    }

    private fun migrateDocumentSchemaLocked() {
        val currentSchema = preferences.getInt(KEY_DOCUMENT_SCHEMA_VERSION, 1)
        if (currentSchema >= DOCUMENT_SCHEMA_VERSION) return

        val accessPrompt = documentFile(AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT)
        if (accessPrompt.isFile) {
            archivePreV054Document(accessPrompt)
            val content = accessPrompt.readText()
            if (isLegacySystemAccessPrompt(content)) {
                writeAtomically(accessPrompt, "")
            }
        }

        val workManual = documentFile(AiLimbsDocumentId.WORK_MANUAL)
        if (workManual.isFile) {
            archivePreV054Document(workManual)
            val content = workManual.readText()
            val editableBody = extractLegacyWorkManualBody(content)
            if (editableBody != content) {
                writeAtomically(workManual, editableBody)
            }
        }

        preferences.edit().putInt(KEY_DOCUMENT_SCHEMA_VERSION, DOCUMENT_SCHEMA_VERSION).apply()
    }

    private fun isLegacySystemAccessPrompt(content: String): Boolean =
        content.contains("ubuntu.status") &&
            content.contains("Ubuntu is stopped. Call ubuntu.start first.") &&
            content.contains("任务涉及开发") &&
            content.contains("工作手册")

    private fun extractLegacyWorkManualBody(content: String): String {
        if (!content.contains("## 1. 手册定位") || !content.contains("## 2. 工具手册")) {
            return content
        }
        val firstEditableSection = Regex("(?m)^## 3\\.").find(content) ?: return ""
        return content.substring(firstEditableSection.range.first).trimStart('\r', '\n')
    }

    private fun archivePreV054Document(source: File) {
        ensureDirectory(legacyArchiveDirectory)
        val destination =
            File(
                legacyArchiveDirectory,
                source.nameWithoutExtension + PRE_V054_ARCHIVE_SUFFIX
            )
        if (!destination.exists()) {
            source.copyTo(destination, overwrite = false)
        }
    }

    private fun migrateDocument(destination: File, sources: List<File>) {
        if (destination.exists()) return
        val source = sources.firstOrNull { it.isFile } ?: return
        source.copyTo(destination, overwrite = false)
    }

    private fun createSnapshotLocked(documentId: AiLimbsDocumentId, content: String) {
        val directory = snapshotDirectory(documentId)
        ensureDirectory(directory)
        val hash = sha256(content)
        val timestamp = backupTimestampFormatter().format(Date())
        val fileName =
            documentFile(documentId).nameWithoutExtension +
                "_BACKUP_${timestamp}_${hash.take(8)}.md"
        writeAtomically(File(directory, fileName), content)
        listSnapshotsLocked(documentId)
            .drop(MAX_SNAPSHOTS)
            .forEach { snapshot ->
                val obsolete = File(directory, snapshot.id)
                if (!obsolete.delete()) {
                    throw IllegalStateException("Unable to remove obsolete AI Limbs document snapshot")
                }
            }
    }

    private fun listSnapshotsLocked(
        documentId: AiLimbsDocumentId
    ): List<AiLimbsDocumentSnapshot> {
        val prefix = documentFile(documentId).nameWithoutExtension + "_BACKUP_"
        return snapshotDirectory(documentId)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(prefix) && it.extension == "md" }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val content = file.readText()
                AiLimbsDocumentSnapshot(
                    id = file.name,
                    createdAtEpochMillis = file.lastModified(),
                    sha256 = sha256(content)
                )
            }
            .toList()
    }

    private fun writeAtomically(destination: File, content: String) {
        val parent = requireNotNull(destination.parentFile)
        ensureDirectory(parent)
        val temporary = File(parent, ".${destination.name}.tmp")
        temporary.writeText(content)
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun backupTimestampFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    private fun documentFile(documentId: AiLimbsDocumentId): File =
        File(documentsDirectory, documentId.fileName)

    private fun snapshotDirectory(documentId: AiLimbsDocumentId): File =
        File(backupsDirectory, documentId.stableId)

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException(
                "Unable to create AI Limbs directory: ${directory.absolutePath}"
            )
        }
    }

    private fun legacyDocumentsDirectory(): File =
        File(Environment.getExternalStorageDirectory(), "Laner/docs")

    companion object {
        private const val DOCUMENTS_DIRECTORY = "ai_limbs/docs"
        private const val BACKUPS_DIRECTORY = "backups"
        private const val LEGACY_ARCHIVE_DIRECTORY = "legacy"
        private const val PREFERENCES_NAME = "ai_limbs_documents"
        private const val KEY_DOCUMENT_SCHEMA_VERSION = "document_schema_version"
        private const val DOCUMENT_SCHEMA_VERSION = 3
        private const val MAX_SNAPSHOTS = 3
        private const val PRE_V054_ARCHIVE_SUFFIX = "_PRE_V054.md"
        private const val SYSTEM_ACCESS_PROMPT_ASSET =
            "ai_limbs/AI_LIMBS_SYSTEM_ACCESS_PROMPT.md"
        private const val PROTECTED_HEADER_END_MARKER =
            "<!-- AI_LIMBS_PROTECTED_WORK_MANUAL_HEADER_END -->"
        private val documentMutex = Mutex()
    }
}
