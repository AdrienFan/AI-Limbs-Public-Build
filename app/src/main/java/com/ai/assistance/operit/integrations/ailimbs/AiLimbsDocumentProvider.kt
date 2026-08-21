package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.os.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stores AI Limbs documents in the Android application sandbox.
 *
 * Ubuntu is an optional execution environment. Reading bridge instructions must never create a
 * terminal manager or start PRoot, so Linux paths are used only as one-time migration sources.
 */
class AiLimbsDocumentProvider(context: Context) {
    private val appContext = context.applicationContext
    private val documentsDirectory = File(appContext.filesDir, DOCUMENTS_DIRECTORY)
    private val accessPromptFile = File(documentsDirectory, ACCESS_PROMPT_FILE_NAME)
    private val workManualFile = File(documentsDirectory, WORK_MANUAL_FILE_NAME)
    private val migrationMutex = Mutex()

    @Volatile
    private var migrationChecked = false

    val accessPromptPath: String
        get() = accessPromptFile.absolutePath

    val workManualPath: String
        get() = workManualFile.absolutePath

    suspend fun readAccessPrompt(): String = readDocument(accessPromptFile)

    suspend fun readWorkManual(): String = readDocument(workManualFile)

    suspend fun writeAccessPrompt(content: String) = writeDocument(accessPromptFile, content)

    suspend fun writeWorkManual(content: String) = writeDocument(workManualFile, content)

    private suspend fun readDocument(file: File): String =
        withContext(Dispatchers.IO) {
            ensureLegacyDocumentsMigrated()
            if (file.isFile) file.readText() else ""
        }

    private suspend fun writeDocument(file: File, content: String) =
        withContext(Dispatchers.IO) {
            ensureLegacyDocumentsMigrated()
            ensureDocumentsDirectory()
            file.writeText(content)
        }

    /**
     * V0.5.1 and V0.5.2 stored these files in the Ubuntu rootfs. Importing them here is a data
     * migration. After this check every read and write uses only the application-owned
     * destination.
     */
    private suspend fun ensureLegacyDocumentsMigrated() {
        if (migrationChecked) return
        migrationMutex.withLock {
            if (migrationChecked) return
            ensureDocumentsDirectory()

            val ubuntuRoot =
                File(
                    appContext.filesDir,
                    "usr/var/lib/proot-distro/installed-rootfs/ubuntu"
                )
            migrateDocument(
                destination = accessPromptFile,
                sources =
                    listOf(
                        File(ubuntuRoot, "root/laner/docs/$ACCESS_PROMPT_FILE_NAME"),
                        File(legacyDocumentsDirectory(), ACCESS_PROMPT_FILE_NAME)
                    )
            )
            migrateDocument(
                destination = workManualFile,
                sources =
                    listOf(
                        File(ubuntuRoot, "root/$WORK_MANUAL_FILE_NAME"),
                        File(legacyDocumentsDirectory(), WORK_MANUAL_FILE_NAME)
                    )
            )
            migrationChecked = true
        }
    }

    private fun migrateDocument(destination: File, sources: List<File>) {
        if (destination.exists()) return
        val source = sources.firstOrNull { it.isFile } ?: return
        source.copyTo(destination, overwrite = false)
    }

    private fun ensureDocumentsDirectory() {
        if (documentsDirectory.isDirectory) return
        if (!documentsDirectory.mkdirs() && !documentsDirectory.isDirectory) {
            throw IllegalStateException(
                "Unable to create AI Limbs documents directory: ${documentsDirectory.absolutePath}"
            )
        }
    }

    private fun legacyDocumentsDirectory(): File =
        File(Environment.getExternalStorageDirectory(), "Laner/docs")

    companion object {
        private const val DOCUMENTS_DIRECTORY = "ai_limbs/docs"
        private const val ACCESS_PROMPT_FILE_NAME = "LANER_ACCESS_PROMPT.md"
        private const val WORK_MANUAL_FILE_NAME = "LANER_WORK_MANUAL.md"
    }
}
