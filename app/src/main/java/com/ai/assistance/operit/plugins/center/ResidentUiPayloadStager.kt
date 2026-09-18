package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Materializes Android picker grants before UI events cross into ail_plugin_runtime.
 *
 * The Resident worker is launched through app_process, not AMS, so it must not dereference
 * ContentProvider URIs. Host resolves direct shared-storage paths when possible and stages
 * opaque providers into AI Limbs app-specific external cache otherwise.
 */
internal class ResidentUiPayloadStager(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun stagePickerPayload(payloadJson: String): String {
        if (payloadJson.isBlank() || CONTENT_SCHEME !in payloadJson) return payloadJson
        val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return payloadJson
        val session = StageSession()

        return try {
            rewriteObject(root, session)
            root.toString()
        } catch (error: Throwable) {
            session.eventRoot?.deleteRecursively()
            throw error
        }
    }

    private fun rewriteObject(value: JSONObject, session: StageSession) {
        value.keys().asSequence().toList().forEach { key ->
            when (key) {
                SELECTED_URI -> {
                    val uriText = value.optString(key).trim()
                    if (uriText.startsWith(CONTENT_SCHEME)) {
                        value.put(key, stageUri(uriText, session))
                    }
                }
                SELECTED_URIS -> {
                    val array = value.optJSONArray(key) ?: return@forEach
                    for (index in 0 until array.length()) {
                        val uriText = array.optString(index).trim()
                        if (uriText.startsWith(CONTENT_SCHEME)) {
                            array.put(index, stageUri(uriText, session))
                        }
                    }
                }

                else -> when (val child = value.opt(key)) {
                    is JSONObject -> rewriteObject(child, session)
                    is JSONArray -> rewriteArray(child, session)
                }
            }
        }
    }

    private fun rewriteArray(value: JSONArray, session: StageSession) {
        for (index in 0 until value.length()) {
            when (val child = value.opt(index)) {
                is JSONObject -> rewriteObject(child, session)
                is JSONArray -> rewriteArray(child, session)
            }
        }
    }

    private fun stageUri(uriText: String, session: StageSession): String {
        val uri = Uri.parse(uriText)
        resolveSharedStoragePath(uri)?.let { path ->
            AppLogger.d(TAG, "Resolved Resident picker URI to shared path: $path")
            return path
        }
        val staged = if (DocumentsContract.isTreeUri(uri)) {
            stageTree(uri, session)
        } else {
            stageDocument(uri, session)
        }

        AppLogger.d(TAG, "Staged Resident picker URI for worker access: " + staged.absolutePath)
        return staged.absolutePath
    }

    private fun resolveSharedStoragePath(uri: Uri): String? {
        val documentId = runCatching {
            when {
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.isDocumentUri(appContext, uri) -> DocumentsContract.getDocumentId(uri)
                else -> null
            }
        }.getOrNull()?.trim().orEmpty()
        if (documentId.isBlank()) return null

        val candidate = when {
            documentId.startsWith("raw:") -> File(documentId.removePrefix("raw:"))
            uri.authority == EXTERNAL_STORAGE_AUTHORITY && documentId.startsWith("primary:") -> {
                File(Environment.getExternalStorageDirectory(), documentId.substringAfter(':'))
            }
            else -> null
        } ?: return null

        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val sharedRoot = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
            ?: return null
        if (canonical.path != sharedRoot.path &&
            !canonical.path.startsWith(sharedRoot.path + File.separator)
        ) {
            return null
        }

        return canonical.takeIf { it.exists() }?.absolutePath
    }

    private fun stageDocument(uri: Uri, session: StageSession): File {
        val root = session.eventRoot()
        val name = safeName(queryDisplayName(uri).ifBlank { "selected-" + UUID.randomUUID() })
        val destination = uniqueChild(root, name)
        copyDocument(uri, destination, session)
        return destination
    }

    private fun stageTree(treeUri: Uri, session: StageSession): File {
        val root = session.eventRoot()
        val destination = uniqueChild(root, "tree-" + UUID.randomUUID())
        check(destination.mkdirs()) { "Could not create Resident picker staging directory" }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        try {
            copyTreeChildren(treeUri, rootId, destination, depth = 0, session = session)
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
        return destination
    }

    private fun copyTreeChildren(
        treeUri: Uri,
        parentId: String,
        destination: File,
        depth: Int,
        session: StageSession
    ) {

        require(depth <= MAX_TREE_DEPTH) {
            "Selected directory is too deep to bridge into Resident runtime"
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val children = mutableListOf<TreeChild>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex).orEmpty()
                val mime = cursor.getString(mimeIndex).orEmpty()
                children += TreeChild(id, name, mime)
            }
        } ?: error("Could not read selected directory")

        children.forEach { child ->
            session.entryCount += 1
            require(session.entryCount <= MAX_TREE_ENTRIES) {
                "Selected directory is too large to bridge into Resident runtime"
            }

            val target = uniqueChild(
                destination,
                safeName(child.name.ifBlank { "item-" + session.entryCount })
            )
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                check(target.mkdirs()) { "Could not create staged directory: " + target.name }
                copyTreeChildren(treeUri, child.id, target, depth + 1, session)
            } else {
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                copyDocument(documentUri, target, session)
            }
        }
    }

    private fun copyDocument(uri: Uri, destination: File, session: StageSession) {
        destination.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not read selected document" }
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    session.copiedBytes += read
                    require(session.copiedBytes <= MAX_STAGED_BYTES) {
                        "Selected content is too large to bridge into Resident runtime"
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")

    private fun safeName(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .take(160)
        return cleaned.ifBlank { "selected-" + UUID.randomUUID() }
    }

    private fun uniqueChild(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val base = candidate.nameWithoutExtension.ifBlank { "selected" }
        val extension = candidate.extension.takeIf { it.isNotBlank() }?.let { "." + it }.orEmpty()
        var index = 2
        while (candidate.exists()) {
            candidate = File(parent, base + "-" + index + extension)
            index += 1
        }
        return candidate
    }

    private inner class StageSession {
        var eventRoot: File? = null
        var copiedBytes: Long = 0L
        var entryCount: Int = 0

        fun eventRoot(): File {
            eventRoot?.let { return it }
            val base = File(requireNotNull(appContext.externalCacheDir), STAGING_DIR).apply {
                check(mkdirs() || isDirectory) {
                    "Could not create Resident picker staging root"
                }
            }
            return File(
                base,
                System.currentTimeMillis().toString() + "-" + UUID.randomUUID().toString()
            ).apply {
                check(mkdirs()) { "Could not create Resident picker event staging directory" }
                eventRoot = this
            }
        }
    }

    private data class TreeChild(
        val id: String,
        val name: String,
        val mime: String
    )

    private companion object {
        const val TAG = "ResidentUiPayloadStager"
        const val CONTENT_SCHEME = "content://"
        const val SELECTED_URI = "selected_uri"
        const val SELECTED_URIS = "selected_uris"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val STAGING_DIR = "resident-ui-picker"
        const val MAX_TREE_DEPTH = 12
        const val MAX_TREE_ENTRIES = 500
        const val MAX_STAGED_BYTES = 512L * 1024L * 1024L
    }
}
