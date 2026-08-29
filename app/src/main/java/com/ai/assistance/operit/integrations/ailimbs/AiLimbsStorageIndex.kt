package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class AiLimbsStoredArtifact(
    val artifactId: String,
    val projectId: String,
    val logicalOwner: String,
    val absolutePath: String,
    val purpose: String,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("artifact_id", artifactId)
            .put("project_id", projectId)
            .put("logical_owner", logicalOwner)
            .put("absolute_path", absolutePath)
            .put("purpose", purpose)
            .put("created_at", createdAt)
            .put("updated_at", updatedAt)
            .put("source", source)
}

class AiLimbsStorageIndex(context: Context) {
    private val appContext = context.applicationContext
    private val indexDirectory = File(appContext.filesDir, INDEX_DIRECTORY)
    private val indexFile = File(indexDirectory, INDEX_FILE)

    suspend fun executePersistentHostOperation(
        hostToolName: String,
        parameters: JSONObject,
        source: String,
        operation: suspend () -> JSONObject
    ): JSONObject {
        persistentWriteMutex.lock()
        try {
            val records = withContext(Dispatchers.IO) { loadRecordsLocked() }
            val candidate = resolveCandidate(hostToolName, parameters, source, records)
            val sourceCanonicalPath =
                if (hostToolName == "move_file") {
                    sourcePath(parameters)?.let { File(it).canonicalPath }
                } else {
                    null
                }
            val recordsForVerification =
                if (sourceCanonicalPath == null) {
                    records
                } else {
                    records.filterNot { it.absolutePath == sourceCanonicalPath }
                }
            verifyCanonicalOwnership(candidate, recordsForVerification)
            val result = operation()
            if (!result.optBoolean("success", false)) return result

            val updated =
                updateRecordsAfterSuccess(
                    hostToolName = hostToolName,
                    parameters = parameters,
                    candidate = candidate,
                    records = records
                )
            withContext(Dispatchers.IO) { writeRecordsLocked(updated) }
            return result.put("storage_artifact", candidate.toJson())
        } finally {
            persistentWriteMutex.unlock()
        }
    }

    suspend fun search(
        query: String,
        projectId: String?,
        requestedLimit: Int
    ): JSONObject =
        withContext(Dispatchers.IO) {
            persistentWriteMutex.withLock {
                val normalizedQuery = query.trim().lowercase()
                val normalizedProject = projectId?.trim()?.takeIf { it.isNotEmpty() }
                val limit = requestedLimit.coerceIn(1, 100)
                val matches =
                    loadRecordsLocked()
                        .asSequence()
                        .filter { artifact ->
                            normalizedProject == null || artifact.projectId == normalizedProject
                        }
                        .filter { artifact ->
                            normalizedQuery.isEmpty() ||
                                searchableText(artifact).contains(normalizedQuery)
                        }
                        .sortedByDescending { it.updatedAt }
                        .take(limit)
                        .toList()
                JSONObject()
                    .put("success", true)
                    .put("artifacts", JSONArray(matches.map { it.toJson() }))
                    .put("count", matches.size)
                    .put("index_path", indexFile.absolutePath)
            }
        }

    suspend fun describe(artifactId: String): JSONObject =
        withContext(Dispatchers.IO) {
            persistentWriteMutex.withLock {
                val normalized = artifactId.trim()
                require(normalized.isNotEmpty()) { "artifact_id is required" }
                val artifact =
                    loadRecordsLocked().firstOrNull { it.artifactId == normalized }
                        ?: throw IllegalArgumentException("Unknown artifact_id: " + normalized)
                JSONObject()
                    .put("success", true)
                    .put("artifact", artifact.toJson())
                    .put("index_path", indexFile.absolutePath)
            }
        }

    suspend fun projectFiles(projectId: String, logicalOwner: String?): JSONObject =
        withContext(Dispatchers.IO) {
            persistentWriteMutex.withLock {
                val normalizedProject = projectId.trim()
                require(normalizedProject.isNotEmpty()) { "project_id is required" }
                val normalizedOwner = logicalOwner?.trim()?.takeIf { it.isNotEmpty() }
                val artifacts =
                    loadRecordsLocked()
                        .filter { artifact ->
                            artifact.projectId == normalizedProject &&
                                (normalizedOwner == null || artifact.logicalOwner == normalizedOwner)
                        }
                        .sortedWith(
                            compareBy<AiLimbsStoredArtifact> { it.logicalOwner }
                                .thenBy { it.absolutePath }
                        )
                JSONObject()
                    .put("success", true)
                    .put("project_id", normalizedProject)
                    .put("logical_owner", normalizedOwner ?: JSONObject.NULL)
                    .put("artifacts", JSONArray(artifacts.map { it.toJson() }))
                    .put("count", artifacts.size)
                    .put("index_path", indexFile.absolutePath)
            }
        }

    fun isPersistentHostTool(hostToolName: String): Boolean =
        hostToolName in PERSISTENT_HOST_TOOLS

    private fun resolveCandidate(
        hostToolName: String,
        parameters: JSONObject,
        source: String,
        records: List<AiLimbsStoredArtifact>
    ): AiLimbsStoredArtifact {
        val rawPath = destinationPath(hostToolName, parameters)
        require(rawPath.isNotBlank()) {
            "Persistent host operation requires a canonical destination path"
        }
        val canonicalPath = File(rawPath).canonicalPath
        require(!isTemporaryPath(canonicalPath)) {
            "Temporary paths are not durable artifacts: " + canonicalPath
        }

        val sourceRecord =
            if (hostToolName == "move_file") {
                sourcePath(parameters)
                    ?.let { File(it).canonicalPath }
                    ?.let { canonical -> records.firstOrNull { it.absolutePath == canonical } }
            } else {
                null
            }
        val explicitPurpose =
            parameters.optString("artifact_purpose")
                .ifBlank { parameters.optString("purpose") }
                .trim()
                .takeIf { it.isNotEmpty() }
        val projectId =
            parameters.optString("project_id").trim().takeIf { it.isNotEmpty() }
                ?: sourceRecord?.projectId
                ?: inferProjectId(canonicalPath)
        val logicalOwner =
            parameters.optString("logical_owner").trim().takeIf { it.isNotEmpty() }
                ?: sourceRecord?.logicalOwner
                ?: projectId
        val purpose =
            explicitPurpose
                ?: sourceRecord?.purpose
                ?: File(canonicalPath).name.ifBlank { canonicalPath }
        val explicitArtifactId =
            parameters.optString("artifact_id").trim().takeIf { it.isNotEmpty() }
        val identity =
            explicitArtifactId
                ?: sourceRecord?.artifactId
                ?: stableArtifactId(
                    if (explicitPurpose == null) {
                        "path|" + canonicalPath
                    } else {
                        "owner|" + projectId + "|" + logicalOwner + "|" + purpose
                    }
                )
        val existing = records.firstOrNull { it.artifactId == identity }
        val now = System.currentTimeMillis()
        return AiLimbsStoredArtifact(
            artifactId = identity,
            projectId = projectId,
            logicalOwner = logicalOwner,
            absolutePath = canonicalPath,
            purpose = purpose,
            createdAt = sourceRecord?.createdAt ?: existing?.createdAt ?: now,
            updatedAt = now,
            source = source
        )
    }

    private fun verifyCanonicalOwnership(
        candidate: AiLimbsStoredArtifact,
        records: List<AiLimbsStoredArtifact>
    ) {
        val conflict =
            records.firstOrNull {
                it.artifactId == candidate.artifactId &&
                    it.absolutePath != candidate.absolutePath
            }
        require(conflict == null) {
            "Artifact " + candidate.artifactId +
                " already has canonical path " + conflict?.absolutePath
        }
        val pathConflict =
            records.firstOrNull {
                it.absolutePath == candidate.absolutePath &&
                    it.artifactId != candidate.artifactId
            }
        require(pathConflict == null) {
            "Canonical path already belongs to artifact " + pathConflict?.artifactId
        }
    }

    private fun updateRecordsAfterSuccess(
        hostToolName: String,
        parameters: JSONObject,
        candidate: AiLimbsStoredArtifact,
        records: List<AiLimbsStoredArtifact>
    ): List<AiLimbsStoredArtifact> {
        val removedSource =
            if (hostToolName == "move_file") {
                sourcePath(parameters)?.let { File(it).canonicalPath }
            } else {
                null
            }
        return records
            .filterNot { artifact ->
                artifact.artifactId == candidate.artifactId ||
                    (removedSource != null && artifact.absolutePath == removedSource)
            } + candidate
    }

    private fun loadRecordsLocked(): List<AiLimbsStoredArtifact> {
        if (!indexFile.exists()) return emptyList()
        val root = JSONObject(indexFile.readText())
        require(root.optInt("schema_version") == SCHEMA_VERSION) {
            "Unsupported AI Limbs storage index schema"
        }
        val array = root.getJSONArray("artifacts")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    AiLimbsStoredArtifact(
                        artifactId = item.getString("artifact_id"),
                        projectId = item.getString("project_id"),
                        logicalOwner = item.getString("logical_owner"),
                        absolutePath = item.getString("absolute_path"),
                        purpose = item.getString("purpose"),
                        createdAt = item.getLong("created_at"),
                        updatedAt = item.getLong("updated_at"),
                        source = item.getString("source")
                    )
                )
            }
        }
    }

    private fun writeRecordsLocked(records: List<AiLimbsStoredArtifact>) {
        ensureDirectory(indexDirectory)
        val root =
            JSONObject()
                .put("schema_version", SCHEMA_VERSION)
                .put(
                    "artifacts",
                    JSONArray(
                        records
                            .sortedBy { it.artifactId }
                            .map { it.toJson() }
                    )
                )
        val temporary = File(indexDirectory, "." + INDEX_FILE + ".tmp")
        temporary.writeText(root.toString(2))
        Files.move(
            temporary.toPath(),
            indexFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun destinationPath(hostToolName: String, parameters: JSONObject): String =
        when (hostToolName) {
            "move_file" ->
                parameters.optString("destination_path")
                    .ifBlank { parameters.optString("destination") }
                    .ifBlank { parameters.optString("target") }
                    .ifBlank { parameters.optString("to") }
            else -> parameters.optString("path")
        }

    private fun sourcePath(parameters: JSONObject): String? =
        parameters.optString("source_path")
            .ifBlank { parameters.optString("source") }
            .ifBlank { parameters.optString("from") }
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun inferProjectId(canonicalPath: String): String {
        val normalized = canonicalPath.replace('\\', '/')
        val projectMatch =
            Regex("^/root/laner/projects/([^/]+)").find(normalized)
                ?: Regex("^/storage/emulated/0/Laner/([^/]+)").find(normalized)
        return projectMatch?.groupValues?.get(1) ?: "laner.general"
    }

    private fun searchableText(artifact: AiLimbsStoredArtifact): String =
        listOf(
            artifact.artifactId,
            artifact.projectId,
            artifact.logicalOwner,
            artifact.absolutePath,
            artifact.purpose,
            artifact.source
        ).joinToString("\n").lowercase()

    private fun isTemporaryPath(path: String): Boolean =
        path == "/tmp" ||
            path.startsWith("/tmp/") ||
            path.contains("/cache/") ||
            path.endsWith(".tmp")

    private fun stableArtifactId(identity: String): String =
        "artifact_" +
            MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .take(24)

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException(
                "Unable to create AI Limbs storage index directory: " + directory.absolutePath
            )
        }
    }

    private companion object {
        const val INDEX_DIRECTORY = "ai_limbs/storage"
        const val INDEX_FILE = "artifacts.json"
        const val SCHEMA_VERSION = 1
        val PERSISTENT_HOST_TOOLS = setOf("write_file", "move_file", "create_directory")
        val persistentWriteMutex = Mutex()
    }
}
