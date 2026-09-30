package com.ai.limbs.plugins.artstudio

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Output preferences and saved-file identities are plugin business state, shared by both owners. */
internal class ArtSaveDirectories(private val privateRoot: File) {
    private val preferences = File(privateRoot, "save-directory.json")
    private val projectLocations = File(privateRoot, "document-locations.json")

    private fun configuredDirectory(): String =
        if (preferences.isFile) JSONObject(preferences.readText()).getString("directory") else ""

    fun directory(): File {
        val configured = configuredDirectory()
        return if (configured.isEmpty()) privateRoot else File(configured)
    }

    fun describe(): JSONObject {
        val configured = configuredDirectory()
        val selected = if (configured.isEmpty()) privateRoot else File(configured)
        return JSONObject().put("directory", selected.absolutePath)
            .put("configuredDirectory", configured).put("custom", configured.isNotEmpty())
            .put("projectsDirectory", File(selected, "documents").absolutePath)
            .put("imagesDirectory", File(selected, "exports").absolutePath)
            .put("backupsDirectory", File(selected, "backups").absolutePath)
    }

    /** Called under ArtStore's cross-process lock. A rejected location never changes preferences. */
    fun setDirectory(input: String): JSONObject {
        val path = input.trim()
        require(path.length <= 4096 && !path.contains('\u0000')) { "保存目录路径无效" }
        val selected = if (path.isEmpty()) privateRoot.canonicalFile else {
            val requested = File(path)
            require(requested.isAbsolute) { "请填写绝对目录路径，或留空恢复插件内默认目录" }
            requested.canonicalFile
        }
        prepare(selected)
        val probe = File.createTempFile(".art-studio-write-", ".tmp", selected)
        try {
            FileOutputStream(probe).use { stream -> stream.write(0); stream.fd.sync() }
        } finally {
            check(probe.delete()) { "无法清理目录验证文件：${probe.absolutePath}" }
        }
        val configured = if (selected == privateRoot.canonicalFile) "" else selected.absolutePath
        atomic(preferences, JSONObject().put("directory", configured).toString())
        return describe()
    }

    fun outputDirectory(kind: String): File {
        require(kind in setOf("documents", "exports", "backups"))
        return File(directory(), kind)
    }

    /** An existing document keeps its own location when the default for new output changes. */
    fun projectFile(id: String): File {
        val locations = readLocations()
        if (locations.has(id)) return File(locations.getString(id))
        val legacy = File(File(privateRoot, "documents"), "$id.ailart")
        val legacyMarker = File(File(privateRoot, "documents"), "$id.sha256")
        if (legacy.isFile || legacyMarker.isFile) return legacy
        return File(outputDirectory("documents"), "$id.ailart")
    }

    /** Persist each successful archive location before ArtStore marks the saved revision. */
    fun rememberProject(id: String, file: File) {
        val locations = readLocations()
        locations.put(id, file.absolutePath)
        atomic(projectLocations, locations.toString())
    }

    private fun readLocations(): JSONObject =
        if (projectLocations.isFile) JSONObject(projectLocations.readText()) else JSONObject()

    fun prepare(directory: File): File {
        require(directory.mkdirs() || directory.isDirectory) { "无法创建保存目录：${directory.absolutePath}" }
        require(directory.canWrite()) { "保存目录不可写，请检查文件访问权限：${directory.absolutePath}" }
        return directory
    }

    private fun atomic(file: File, text: String) {
        val temporary = File(privateRoot, ".save-directory-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "无法保存目录配置：${file.name}" }
        } finally {
            temporary.delete()
        }
    }
}
