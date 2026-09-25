package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Region
import android.graphics.Color
import java.io.ByteArrayOutputStream
import android.graphics.Matrix
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.nio.channels.FileLock
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/** Both the Host presentation and Resident business runtime use the same plugin data directory. */
internal class ArtStore(private val root: File) {
    private val drafts = File(root, "drafts")
    private val documents = File(root, "documents")
    private val assets = File(root, "assets")
    private val pointer = File(root, "current.txt")
    private val recentIndex = File(root, "recent.json")
    private val sessionIndex = File(root, "sessions.json")
    private val externalLinks = File(root, "external-links.json")
    private val editClipboard = File(root, "edit-clipboard.json")
    private val templates = File(root, "templates")
    private val backups = File(root, "backups")
    private val lockFile = File(root, "art-studio.lock")

    init {
        require(drafts.mkdirs() || drafts.isDirectory)
        require(documents.mkdirs() || documents.isDirectory)
        require(assets.mkdirs() || assets.isDirectory)
        require(templates.mkdirs() || templates.isDirectory)
        require(backups.mkdirs() || backups.isDirectory)
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(processLock) {
        FileOutputStream(lockFile, true).channel.use { channel ->
            val lock: FileLock = channel.lock()
            try { block() } finally { lock.release() }
        }
    }

    fun create(width: Int, height: Int, background: String = "#FFFFFFFF",
               name: String = "未命名工程", actor: String = "AWEI"): JSONObject = locked {
        require(width in 64..4096 && height in 64..4096) { "画布边长需要在 64–4096 像素之间" }
        requireColor(background)
        require(name.trim().isNotBlank()) { "工程名称不能为空" }
        val id = UUID.randomUUID().toString()
        val firstLayer = UUID.randomUUID().toString()
        val base = JSONObject().put("width", width).put("height", height).put("name", name.trim().take(100))
            .put("background", background).put("layers", JSONArray().put(newLayer(firstLayer, "paint", "绘画图层", "", "")))
            .put("selectedLayerId", firstLayer)
            .put("selection", JSONObject.NULL)
        val doc = JSONObject().put("format", 1).put("id", id).put("base", base)
            .put("createdBy", actor).put("operations", JSONArray())
        atomic(draft(id), doc.toString())
        atomic(pointer, id)
        markRecent(id)
        snapshot(doc)
    }

    fun current(): JSONObject = locked { snapshot(loadCurrent()) }

    fun revision(): String = locked {
        if (!pointer.isFile) ""
        else {
            val id = pointer.readText().trim()
            validateId(id)
            val marker = File(documents, id + ".sha256")
            "$id:${draft(id).lastModified()}:${draft(id).length()}:" +
                "${marker.lastModified()}:${marker.length()}:" +
                "${archive(id).lastModified()}:${externalLink(id)?.optBoolean("pending") == true}:" +
                editClipboard.lastModified().toString() + ":" +
                (if (editClipboard.isFile) JSONObject(editClipboard.readText()).optString("asset") else "")
        }
    }

    fun list(): JSONArray = locked {
        JSONArray().also { out ->
            drafts.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
                val doc = JSONObject(file.readText())
                val state = replay(doc)
                out.put(JSONObject().put("id", doc.getString("id"))
                    .put("name", state.optString("name", "未命名工程"))
                    .put("width", state.getInt("width")).put("height", state.getInt("height"))
                    .put("saved", archive(doc.getString("id")).exists())
                    .put("dirty", externalLink(doc.getString("id"))?.optBoolean("pending") == true ||
                        !archive(doc.getString("id")).exists() ||
                        File(documents, doc.getString("id") + ".sha256").let { marker ->
                            if (marker.isFile) marker.readText() != digest(doc.toString())
                            else file.lastModified() > archive(doc.getString("id")).lastModified()
                        })
                    .put("modified", file.lastModified()))
            }
        }
    }

    fun apply(actor: String, type: String, params: JSONObject): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val doc = loadCurrent()
        if (params.has("expectedRevision")) {
            require(params.getInt("expectedRevision") == doc.getJSONArray("operations").length()) { "工程已被另一端修改，请刷新后重试" }
        }
        val operationId = UUID.randomUUID().toString()
        val normalized = JSONObject(params.toString())
        if (type == "SELECTION_EDIT" && normalized.optString("action") == "COPY") {
            normalized.put("copyId", operationId)
        }
        val operation = JSONObject().put("id", operationId)
            .put("actor", actor).put("type", type).put("parameters", normalized)
            .put("timestamp", System.currentTimeMillis())
        doc.getJSONArray("operations").put(operation)
        // A failed replay must never overwrite the existing draft or its history.
        val result = snapshot(doc)
        atomic(draft(doc.getString("id")), doc.toString())
        result.put("lastOperationId", operation.getString("id"))
    }

    fun save(): JSONObject = locked { saveDocument(loadCurrent()) }

    // Save As changes the active document identity; the previous document stays available.
    fun saveAs(name: String, activate: Boolean = true, actor: String = "AWEI"): JSONObject = locked {
        val doc = copyCurrent(name, activate, actor)
        saveDocument(doc)
    }

    fun duplicate(name: String, actor: String = "AWEI"): JSONObject = locked {
        snapshot(copyCurrent(name, actor = actor))
    }

    fun linkExternal(id: String, uri: String): JSONObject = locked {
        validateId(id)
        require(draft(id).isFile && uri.startsWith("content://")) { "无效的外部工程地址" }
        val links = if (externalLinks.isFile) JSONObject(externalLinks.readText()) else JSONObject()
        links.put(id, JSONObject().put("uri", uri).put("pending", false))
        atomic(externalLinks, links.toString())
        JSONObject().put("id", id).put("uri", uri)
    }

    fun markExternalSynced(id: String): JSONObject = locked {
        validateId(id)
        val links = if (externalLinks.isFile) JSONObject(externalLinks.readText()) else JSONObject()
        val link = requireNotNull(links.optJSONObject(id)) { "工程没有外部保存位置" }
        link.put("pending", false)
        atomic(externalLinks, links.toString())
        JSONObject().put("id", id).put("uri", link.getString("uri"))
    }

    private fun externalLink(id: String): JSONObject? {
        if (!externalLinks.isFile) return null
        return JSONObject(externalLinks.readText()).optJSONObject(id)
    }
    fun recent(): JSONArray = locked {
        val ids = if (recentIndex.isFile) JSONArray(recentIndex.readText()) else JSONArray()
        val result = JSONArray()
        for (i in 0 until ids.length()) {
            val item = runCatching {
                val id = ids.getString(i)
                val file = draft(id)
                if (!file.isFile) null
                else {
                    val state = replay(JSONObject(file.readText()))
                    JSONObject().put("id", id)
                        .put("name", state.optString("name", "未命名工程"))
                        .put("width", state.getInt("width"))
                        .put("height", state.getInt("height"))
                }
            }.getOrNull()
            if (item != null) result.put(item)
        }
        result
    }


    fun sessions(): JSONArray = locked {
        if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
    }

    fun saveSession(name: String): JSONObject = locked {
        require(name.trim().isNotBlank()) { "会话名称不能为空" }
        val id = loadCurrent().getString("id")
        val entries = if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
        val out = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (item.getString("name") != name.trim()) out.put(item)
        }
        val entry = JSONObject().put("name", name.trim().take(100)).put("documentId", id)
        out.put(entry)
        atomic(sessionIndex, out.toString())
        entry
    }

    fun openSession(name: String): JSONObject = locked {
        val entries = if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
        val item = (0 until entries.length()).map { entries.getJSONObject(it) }
            .firstOrNull { it.getString("name") == name } ?: error("会话不存在")
        val id = item.getString("documentId")
        require(draft(id).isFile) { "会话中的工程已不存在" }
        val result = snapshot(JSONObject(draft(id).readText()))
        atomic(pointer, id)
        markRecent(id)
        result
    }

    fun deleteSession(name: String): JSONObject = locked {
        val entries = if (sessionIndex.isFile) JSONArray(sessionIndex.readText()) else JSONArray()
        val out = JSONArray()
        var removed = false
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (item.getString("name") == name) removed = true else out.put(item)
        }
        require(removed) { "会话不存在" }
        atomic(sessionIndex, out.toString())
        JSONObject().put("name", name).put("deleted", true)
    }

    fun close(): JSONObject = locked {
        val id = loadCurrent().getString("id")
        require(pointer.delete()) { "无法关闭工程" }
        JSONObject().put("closedId", id)
    }

    fun discardCurrent(): JSONObject = locked {
        val doc = loadCurrent()
        val id = doc.getString("id")
        require(externalLink(id)?.optBoolean("pending") != true) {
            "外部工程尚未同步，请先保存到原文件"
        }
        val saved = archive(id)
        if (saved.isFile) {
            ZipFile(saved).use { zip ->
                val entry = requireNotNull(zip.getEntry("project.json"))
                val restored = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                atomic(draft(id), restored)
                atomic(File(documents, id + ".sha256"), digest(restored))
            }
        } else {
            require(draft(id).delete()) { "无法舍弃未保存的工程" }
        }
        require(pointer.delete()) { "无法关闭工程" }
        JSONObject().put("closedId", id).put("discarded", true)
    }

    fun saveIncrementalVersion(actor: String = "AWEI"): JSONObject = locked {
        val doc = loadCurrent()
        val baseName = replay(doc).getString("name").replace(Regex("_v[0-9]{3,}$"), "")
        val names = drafts.listFiles()?.mapNotNull { file ->
            runCatching { replay(JSONObject(file.readText())).getString("name") }.getOrNull()
        }?.toSet() ?: emptySet()
        var number = 1
        while (baseName + "_v" + number.toString().padStart(3, '0') in names) number++
        saveDocument(copyCurrent(baseName + "_v" + number.toString().padStart(3, '0'),
            actor = actor))
    }

    fun saveIncrementalBackup(): JSONObject = locked {
        val doc = loadCurrent()
        val id = doc.getString("id")
        val saved = archive(id)
        var number = 1
        var backup = File(backups, id + "_b" + number.toString().padStart(3, '0') + ".ailart")
        while (backup.exists()) {
            number++
            backup = File(backups, id + "_b" + number.toString().padStart(3, '0') + ".ailart")
        }
        if (saved.isFile) atomicBytes(backup, saved.readBytes())
        val result = saveDocument(doc)
        result.put("backupPath", if (saved.isFile) backup.absolutePath else JSONObject.NULL)
    }

    fun createTemplate(name: String): JSONObject = locked {
        require(name.trim().isNotBlank()) { "模板名称不能为空" }
        val doc = JSONObject(loadCurrent().toString())
        val id = UUID.randomUUID().toString()
        doc.put("id", id)
        doc.put("base", replay(doc).put("name", name.trim().take(100)))
        doc.put("operations", JSONArray())
        val target = File(templates, id + ".ailart")
        writeArchive(doc, target)
        JSONObject().put("id", id).put("name", name.trim().take(100))
    }

    fun templates(): JSONArray = locked {
        val result = JSONArray()
        templates.listFiles()?.filter { it.extension == "ailart" }?.sortedBy { it.name }?.forEach { file ->
            ZipFile(file).use { zip ->
                val entry = requireNotNull(zip.getEntry("project.json"))
                val doc = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                result.put(JSONObject().put("id", doc.getString("id"))
                    .put("name", doc.getJSONObject("base").getString("name")))
            }
        }
        result
    }

    fun fromTemplate(id: String, actor: String = "AWEI"): JSONObject = locked {
        validateId(id)
        val source = File(templates, id + ".ailart")
        require(source.isFile) { "模板不存在" }
        val doc = ZipFile(source).use { zip ->
            val entry = requireNotNull(zip.getEntry("project.json"))
            JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
        }
        doc.put("id", UUID.randomUUID().toString()).put("createdBy", actor)
        val newId = doc.getString("id")
        val result = snapshot(doc)
        atomic(draft(newId), doc.toString())
        atomic(pointer, newId)
        markRecent(newId)
        result
    }

    private fun copyCurrent(name: String, activate: Boolean = true,
                            actor: String = "AWEI"): JSONObject {
        require(name.trim().isNotBlank()) { "工程名称不能为空" }
        val source = loadCurrent()
        val doc = JSONObject(source.toString())
        val id = UUID.randomUUID().toString()
        doc.put("id", id).put("createdBy", actor)
        // A new document starts with the current composed state so old operations remain immutable.
        doc.put("base", replay(source).put("name", name.trim().take(100)))
        doc.put("operations", JSONArray())
        snapshot(doc)
        atomic(draft(id), doc.toString())
        if (activate) {
            atomic(pointer, id)
            markRecent(id)
        }
        return doc
    }

    private fun markRecent(id: String) {
        val existing = if (recentIndex.isFile) JSONArray(recentIndex.readText()) else JSONArray()
        val entries = JSONArray().put(id)
        for (i in 0 until existing.length()) {
            val next = existing.getString(i)
            if (next != id && entries.length() < 16) entries.put(next)
        }
        atomic(recentIndex, entries.toString())
    }

    private fun saveDocument(doc: JSONObject): JSONObject {
        val id = doc.getString("id")
        val destination = archive(id)
        writeArchive(doc, destination)
        atomic(File(documents, id + ".sha256"), digest(doc.toString()))
        val links = if (externalLinks.isFile) JSONObject(externalLinks.readText()) else JSONObject()
        links.optJSONObject(id)?.let {
            it.put("pending", true)
            atomic(externalLinks, links.toString())
        }
        return JSONObject().put("id", id).put("path", destination.absolutePath)
            .put("externalUri", links.optJSONObject(id)?.optString("uri", "") ?: "")
            .put("bytes", destination.length())
    }

    private fun writeArchive(doc: JSONObject, destination: File) {
        val temp = File(destination.parentFile, "." + UUID.randomUUID() + ".tmp")
        try {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(doc.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val used = mutableSetOf<String>()
                val operations = doc.getJSONArray("operations")
                val state = replay(doc)
                val layers = state.getJSONArray("layers")
                for (i in 0 until layers.length()) {
                    val layer = layers.getJSONObject(i)
                    if (layer.optString("kind") == "image") used.add(layer.getString("asset"))
                    layer.optJSONArray("contentOrder")?.let { order ->
                        for (n in 0 until order.length()) {
                            val event = order.getJSONObject(n)
                            if (event.optString("kind") in setOf("paste", "erase")) used.add(event.getString("asset"))
                        }
                    }
                }
                for (i in 0 until operations.length()) {
                    val operation = operations.getJSONObject(i)
                    if (operation.getString("type") in setOf("IMAGE_IMPORT", "PASTE_IMAGE", "PIXEL_PASTE")) {
                        used.add(operation.getJSONObject("parameters").getString("asset"))
                    }
                }
                used.forEach { asset ->
                    zip.putNextEntry(ZipEntry("assets/" + asset + ".png"))
                    zip.write(assetFile(asset).readBytes())
                    zip.closeEntry()
                }
            }
            require(temp.renameTo(destination)) { "保存工程文件失败" }
        } finally { temp.delete() }
    }

    private fun digest(data: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun open(id: String): JSONObject = locked {
        validateId(id)
        if (!draft(id).exists()) {
            val file = archive(id)
            require(file.isFile) { "工程不存在" }
            ZipFile(file).use { zip ->
                val entry = requireNotNull(zip.getEntry("project.json")) { "工程缺少 project.json" }
                val bytes = zip.getInputStream(entry).use { it.readNBytes(32 * 1024 * 1024 + 1) }
                require(bytes.size <= 32 * 1024 * 1024) { "工程数据过大" }
                val doc = JSONObject(String(bytes, Charsets.UTF_8))
                require(doc.getString("id") == id && doc.getInt("format") == 1)
                snapshot(doc)
                zip.entries().asSequence().filter { it.name.startsWith("assets/") }.forEach { asset ->
                    val name = asset.name.removePrefix("assets/").removeSuffix(".png")
                    require(asset.name == "assets/$name.png")
                    validateId(name)
                    val content = zip.getInputStream(asset).use { it.readNBytes(MAX_ASSET_BYTES + 1) }
                    require(content.size <= MAX_ASSET_BYTES)
                    atomicBytes(assetFile(name), content)
                }
                atomic(draft(id), doc.toString())
            }
        }
        val doc = JSONObject(draft(id).readText())
        val result = snapshot(doc)
        atomic(pointer, id)
        markRecent(id)
        result
    }

    fun importArchive(bytes: ByteArray, actor: String = "AWEI"): JSONObject = locked {
        require(bytes.size in 1..64 * 1024 * 1024) { "工程文件超过 64 MB" }
        var project: JSONObject? = null
        val importedAssets = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 128) { "工程条目过多" }
                val path = entry.name
                when {
                    path == "project.json" -> {
                        require(project == null) { "工程数据重复" }
                        val data = zip.readNBytes(32 * 1024 * 1024 + 1)
                        require(data.size <= 32 * 1024 * 1024)
                        project = JSONObject(String(data, Charsets.UTF_8))
                    }
                    path.startsWith("assets/") -> {
                        val asset = path.removePrefix("assets/").removeSuffix(".png")
                        require(path == "assets/$asset.png") { "工程资源路径无效" }
                        validateId(asset)
                        require(asset !in importedAssets) { "工程资源重复" }
                        val data = zip.readNBytes(MAX_ASSET_BYTES + 1)
                        require(data.size <= MAX_ASSET_BYTES) { "工程图片过大" }
                        importedAssets[asset] = data
                    }
                    else -> error("工程中含有未知条目")
                }
                zip.closeEntry()
            }
        }
        val doc = requireNotNull(project) { "工程缺少 project.json" }
        require(doc.getInt("format") == 1) { "工程格式不受支持" }
        validateId(doc.getString("id"))
        val operations = doc.getJSONArray("operations")
        val assetIds = importedAssets.keys.associateWith { UUID.randomUUID().toString() }
        val baseLayers = doc.getJSONObject("base").getJSONArray("layers")
        for (i in 0 until baseLayers.length()) {
            val layer = baseLayers.getJSONObject(i)
            if (layer.optString("kind") == "image") {
                val oldAsset = layer.getString("asset")
                require(oldAsset in importedAssets) { "工程图片缺失" }
                layer.put("asset", assetIds.getValue(oldAsset))
            }
        }
        for (i in 0 until baseLayers.length()) {
            baseLayers.getJSONObject(i).optJSONArray("contentOrder")?.let { order ->
                for (n in 0 until order.length()) {
                    val event = order.getJSONObject(n)
                    if (event.optString("kind") in setOf("paste", "erase")) {
                        val old = event.getString("asset")
                        require(old in importedAssets) { "工程剪贴资源缺失" }
                        event.put("asset", assetIds.getValue(old))
                    }
                }
            }
        }
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            if (op.getString("type") in setOf("IMAGE_IMPORT", "PASTE_IMAGE", "PIXEL_PASTE")) {
                val parameters = op.getJSONObject("parameters")
                val oldId = parameters.getString("asset")
                require(oldId in importedAssets) { "工程图片缺失" }
                parameters.put("id", parameters.optString("id").ifBlank { oldId })
                parameters.put("asset", assetIds.getValue(oldId))
            }
        }
        val id = UUID.randomUUID().toString()
        doc.put("id", id).put("createdBy", actor)
        val result = snapshot(doc)
        importedAssets.forEach { (asset, data) -> atomicBytes(assetFile(assetIds.getValue(asset)), data) }
        atomic(draft(id), doc.toString())
        atomic(pointer, id)
        markRecent(id)
        result
    }

    fun openImage(encoded: String, name: String = "未命名图像",
                  actor: String = "AWEI"): JSONObject = locked {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 1..MAX_ASSET_BYTES) { "图片大小上限为 8 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 64..4096 && bounds.outHeight in 64..4096) {
            "图片边长需要在 64–4096 像素之间"
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("图片格式无效，请使用 PNG 或 JPEG")
        val png = java.io.ByteArrayOutputStream().use { output ->
            require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        require(png.size <= MAX_ASSET_BYTES) { "图片转换后超过 8 MB" }
        val id = UUID.randomUUID().toString()
        val asset = UUID.randomUUID().toString()
        val layer = newLayer(UUID.randomUUID().toString(), "image", "图像图层", "", asset)
        val base = JSONObject().put("width", bounds.outWidth).put("height", bounds.outHeight)
            .put("name", name.trim().ifBlank { "未命名图像" }.take(100))
            .put("background", "#00000000").put("layers", JSONArray().put(layer))
            .put("selectedLayerId", layer.getString("id")).put("selection", JSONObject.NULL)
        val doc = JSONObject().put("format", 1).put("id", id)
            .put("base", base).put("createdBy", actor).put("operations", JSONArray())
        atomicBytes(assetFile(asset), png)
        try {
            val result = snapshot(doc)
            atomic(draft(id), doc.toString())
            atomic(pointer, id)
            markRecent(id)
            result
        } catch (error: Throwable) {
            assetFile(asset).delete()
            throw error
        }
    }

    fun importImage(actor: String, encoded: String): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 1..MAX_ASSET_BYTES) { "图片大小上限为 8 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "图片边长上限为 4096 像素" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("图片格式无效，请使用 PNG 或 JPEG")
        require(bitmap.width in 1..4096 && bitmap.height in 1..4096)
        val id = UUID.randomUUID().toString()
        val png = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        require(png.size <= MAX_ASSET_BYTES) { "图片转换后超过 8 MB" }
        atomicBytes(assetFile(id), png)
        // apply() takes the same process monitor but acquires a new file lock, so write directly here.
        val doc = loadCurrent()
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("actor", actor)
            .put("type", "IMAGE_IMPORT").put("timestamp", System.currentTimeMillis())
            .put("parameters", JSONObject().put("asset", id).put("width", bitmap.width)
                .put("height", bitmap.height))
        doc.getJSONArray("operations").put(op)
        try {
            val result = snapshot(doc)
            atomic(draft(doc.getString("id")), doc.toString())
            result
        } catch (error: Throwable) {
            assetFile(id).delete()
            throw error
        }
    }

    private fun snapshot(doc: JSONObject): JSONObject {
        val state = replay(doc)
        val id = doc.getString("id")
        val saved = archive(id)
        val operations = doc.getJSONArray("operations")
        val (undoStack, redoStack) = historyStacks(operations)
        val operationById = (0 until operations.length())
            .map { operations.getJSONObject(it) }
            .filter { it.getString("type") !in setOf("REVERT", "RESTORE") }
            .associateBy { it.getString("id") }
        fun operationLabel(id: String?): String {
            if (id == null) return ""
            val operation = operationById[id] ?: return ""
            return when (operation.getString("type")) {
                "LAYER_CREATE", "IMAGE_IMPORT", "PASTE_IMAGE" -> "添加图层"
                "GROUP_CREATE" -> "新建图层组"
                "LAYER_DELETE" -> "删除图层"
                "STROKE_ADD" -> when (operation.getJSONObject("parameters").optString("tool")) {
                    "gradient" -> when (operation.getJSONObject("parameters").optString("gradientMode", "linear")) {
                        "radial" -> "径向渐变"
                        "angular" -> "角度渐变"
                        else -> "线性渐变"
                    }
                    "mirror" -> "多重画笔"
                    "dyna" -> "动态画笔"
                    "calligraphy" -> "斜头书法笔"
                    "line" -> "直线"
                    "rectangle" -> "矩形"
                    "ellipse" -> "椭圆"
                    "polygon" -> "多边形"
                    "polyline" -> "折线"
                    "bezier" -> "贝塞尔曲线"
                    "ink" -> "自由画笔"
                    "pencil" -> "铅笔"
                    "soft" -> "软笔"
                    "spray" -> "喷枪"
                    "eraser" -> "橡皮擦"
                    else -> "绘制笔画"
                }
                "STROKE_ERASE" -> "删除笔画"
                "TRANSFORM" -> "变换图层"
                "CROP" -> "裁剪画布"
                "LAYER_RENAME" -> "重命名图层"
                "LAYER_SELECT" -> "选择图层"
                "LAYER_VISIBLE" -> "显示或隐藏图层"
                "LAYER_LOCK" -> "锁定或解锁图层"
                "LAYER_OPACITY" -> "调整图层不透明度"
                "LAYER_BLEND" -> "调整图层混合模式"
                "LAYER_PROPERTIES" -> "修改图层属性"
                "LAYER_MOVE", "LAYER_MOVE_STEP" -> "调整图层顺序"
                "PIXEL_EDIT" -> if (operation.getJSONObject("parameters").optString("mode") == "CLEAR")
                    "清除像素" else "填充像素"
                "PIXEL_PASTE" -> if (operation.getJSONObject("parameters")
                    .optString("action") == "FILL_CONTIGUOUS") "填充相连区域" else if (operation.getJSONObject("parameters")
                    .optString("action") == "FILL_CONTIGUOUS_ERASE") "擦除相连区域" else "粘贴像素"
                "LAYER_COPY" -> "复制图层"
                "SELECTION_CREATE", "SELECTION_CLEAR", "SELECTION_EDIT" -> "修改选区"
                "DOCUMENT_RENAME" -> "重命名工程"
                else -> "画室操作"
            }
        }
        // A state represents the first N currently reachable edits. Redone edits stay
        // visible as future states until a new edit starts a different branch.
        val reachableIds = undoStack + redoStack.asReversed()
        val originalOrder = operationById.keys.withIndex().associate { it.value to it.index }
        var newestOriginalIndex = -1
        val timeline = JSONArray().put(JSONObject().put("id", "")
            .put("label", "初始画布").put("actor", doc.getString("createdBy"))
            .put("timestamp", 0L))
        for (operationId in reachableIds) {
            val operation = operationById.getValue(operationId)
            val originalIndex = originalOrder.getValue(operationId)
            val reappliedOutOfOrder = originalIndex < newestOriginalIndex
            newestOriginalIndex = maxOf(newestOriginalIndex, originalIndex)
            val entry = JSONObject().put("id", operationId)
                .put("label", if (reappliedOutOfOrder)
                    "重新应用 · " + operationLabel(operationId) else operationLabel(operationId))
                .put("actor", operation.getString("actor"))
                .put("type", operation.getString("type"))
                .put("timestamp", operation.optLong("timestamp", 0L))
            if (operation.getString("type") == "STROKE_ADD") {
                val stroke = operation.getJSONObject("parameters")
                entry.put("tool", stroke.optString("tool", "ink"))
                    .put("color", stroke.getString("color"))
            }
            timeline.put(entry)
        }
        return JSONObject().put("id", doc.getString("id"))
            .put("state", state).put("revision", doc.getJSONArray("operations").length())
            .put("timeline", timeline).put("timelinePosition", undoStack.size)
            .put("canUndo", undoStack.isNotEmpty()).put("canRedo", redoStack.isNotEmpty())
            .put("undoLabel", operationLabel(undoStack.lastOrNull()))
            .put("redoLabel", operationLabel(redoStack.lastOrNull()))
            .put("hasClipboard", editClipboard.isFile)
            .put("dirty", externalLink(id)?.optBoolean("pending") == true ||
                !saved.exists() || File(documents, id + ".sha256").let { marker ->
                if (marker.isFile) marker.readText() != digest(doc.toString())
                else draft(id).lastModified() > saved.lastModified()
            })
            .put("storagePath", if (saved.isFile) saved.absolutePath else "")
            .put("externalUri", externalLink(id)?.optString("uri", "") ?: "")
            .put("externalPending", externalLink(id)?.optBoolean("pending") ?: false)
            .put("operations", JSONArray(doc.getJSONArray("operations").toString()))
    }

    private fun replay(doc: JSONObject): JSONObject {
        val state = JSONObject(doc.getJSONObject("base").toString())
        val operations = doc.getJSONArray("operations")
        val disabled = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val target = op.optJSONObject("parameters")?.optString("targetId") ?: ""
            when (op.getString("type")) {
                "REVERT" -> {
                    require(target in seen && target !in disabled) { "目标操作已撤销或不存在" }
                    disabled.add(target)
                }
                "RESTORE" -> {
                    require(target in disabled) { "目标操作不在撤销状态" }
                    disabled.remove(target)
                }
                else -> require(seen.add(op.getString("id"))) { "重复的操作 ID" }
            }
        }
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            if (op.getString("id") !in disabled && op.getString("type") !in setOf("REVERT", "RESTORE")) {
                edit(state, op.getString("type"), op.getJSONObject("parameters"))
            }
        }
        return state
    }

    private fun edit(state: JSONObject, type: String, p: JSONObject) {
        val layers = state.getJSONArray("layers")
        fun find(id: String): Pair<Int, JSONObject> {
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                if (layer.getString("id") == id) return i to layer
            }
            error("图层不存在：$id；该撤销与后续操作冲突")
        }
        when (type) {
            "LAYER_CREATE", "GROUP_CREATE", "IMAGE_IMPORT" -> {
                val id = p.optString("id").ifBlank { p.optString("asset") }
                require(id.isNotBlank())
                require((0 until layers.length()).none { layers.getJSONObject(it).getString("id") == id })
                val parentId = p.optString("parentId")
                if (parentId.isNotBlank()) require(find(parentId).second.getString("kind") == "group")
                val kind = if (type == "GROUP_CREATE") "group" else if (type == "IMAGE_IMPORT") "image" else "paint"
                layers.put(newLayer(id, kind, p.optString("name", if (kind == "group") "图层组" else "图层"), parentId, p.optString("asset")))
                if (p.optBoolean("select", false)) state.put("selectedLayerId", id)
            }
            "PASTE_IMAGE" -> {
                val asset = p.getString("asset")
                validateId(asset)
                val id = p.getString("id")
                validateId(id)
                require((0 until layers.length()).none { layers.getJSONObject(it).getString("id") == id })
                val layer = newLayer(id, "image", "粘贴图层", "", asset)
                layer.put("x", p.getInt("x")).put("y", p.getInt("y"))
                layers.put(layer)
                state.put("selectedLayerId", id)
            }
            "PIXEL_EDIT", "PIXEL_PASTE" -> {
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") in setOf("paint", "image") && !lockedByParent(layer, layers))
                require(layer.optString("parentId").isBlank() &&
                    layer.getDouble("x") == 0.0 && layer.getDouble("y") == 0.0 &&
                    layer.getDouble("scale") == 1.0 && layer.getDouble("rotation") == 0.0) {
                    "请先取消图层变换和分组，再编辑选区像素"
                }
                val order = contentOrder(layer)
                if (type == "PIXEL_PASTE") {
                    validateId(p.getString("asset"))
                    val kind = if (p.optString("action") == "FILL_CONTIGUOUS_ERASE") "erase" else "paste"
                    order.put(JSONObject().put("kind", kind).put("asset", p.getString("asset"))
                        .put("x", p.getInt("x")).put("y", p.getInt("y")))
                } else {
                    val x = p.getInt("x"); val y = p.getInt("y")
                    val width = p.getInt("width"); val height = p.getInt("height")
                    require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
                        x.toLong() + width <= state.getInt("width") &&
                        y.toLong() + height <= state.getInt("height"))
                    val mode = p.getString("mode")
                    require(mode == "CLEAR" || mode == "FILL")
                    if (mode == "FILL") requireColor(p.getString("color"))
                    val event = JSONObject().put("kind", mode.lowercase()).put("x", x).put("y", y)
                        .put("width", width).put("height", height)
                        .put("color", p.optString("color"))
                    p.optJSONObject("selection")?.let { event.put("selection", JSONObject(it.toString())) }
                    order.put(event)
                }
            }
            "DOCUMENT_RENAME" -> state.put("name", p.getString("name").trim().take(100).also { require(it.isNotBlank()) })
            "LAYER_SELECT" -> state.put("selectedLayerId", find(p.getString("id")).second.getString("id"))
            "LAYER_RENAME" -> find(p.getString("id")).second.put("name", p.getString("name").take(100))
            "LAYER_VISIBLE" -> find(p.getString("id")).second.put("visible", p.getBoolean("visible"))
            "LAYER_OPACITY" -> find(p.getString("id")).second.put("opacity", p.getDouble("opacity").also { require(it in 0.0..1.0) })
            "LAYER_LOCK" -> find(p.getString("id")).second.put("locked", p.getBoolean("locked"))
            "LAYER_BLEND" -> find(p.getString("id")).second.put("blend", p.getString("blend").also { require(it in setOf("normal", "multiply", "screen", "add")) })
            "LAYER_PROPERTIES" -> {
                val layer = find(p.getString("id")).second
                val name = p.optString("name", layer.getString("name")).trim().take(100)
                require(name.isNotBlank()) { "图层名称不能为空" }
                val opacity = p.optDouble("opacity", layer.getDouble("opacity"))
                require(opacity in 0.0..1.0) { "图层不透明度必须在 0–100% 之间" }
                val blend = p.optString("blend", layer.getString("blend"))
                require(blend in setOf("normal", "multiply", "screen", "add")) { "不支持的混合模式" }
                layer.put("name", name).put("opacity", opacity).put("blend", blend)
                    .put("visible", p.optBoolean("visible", layer.getBoolean("visible")))
                    .put("locked", p.optBoolean("locked", layer.getBoolean("locked")))
            }
            "LAYER_MOVE_STEP" -> {
                val (index, layer) = find(p.getString("id"))
                val direction = p.getString("direction")
                require(direction == "up" || direction == "down") { "图层移动方向必须是 up 或 down" }
                val siblings = (0 until layers.length()).filter {
                    layers.getJSONObject(it).optString("parentId") == layer.optString("parentId")
                }
                val siblingPosition = siblings.indexOf(index)
                val next = siblingPosition + if (direction == "up") 1 else -1
                require(next in siblings.indices) { "图层已在当前组的最" + if (direction == "up") "上方" else "下方" }
                val otherIndex = siblings[next]
                val other = layers.getJSONObject(otherIndex)
                layers.put(index, other)
                layers.put(otherIndex, layer)
            }
            "LAYER_MOVE" -> {
                val (index, layer) = find(p.getString("id"))
                val position = p.getInt("index").also { require(it in 0 until layers.length()) }
                val copy = (0 until layers.length()).map { layers.getJSONObject(it) }.toMutableList()
                copy.removeAt(index); copy.add(position, layer)
                state.put("layers", JSONArray(copy))
            }
            "LAYER_DELETE" -> {
                val (index, layer) = find(p.getString("id"))
                require(!layer.getBoolean("locked"))
                require((0 until layers.length()).none { layers.getJSONObject(it).optString("parentId") == p.getString("id") }) {
                    "请先删除或移出组中的图层"
                }
                layers.remove(index)
                if (state.optString("selectedLayerId") == p.getString("id")) {
                    val remaining = (0 until layers.length()).map { layers.getJSONObject(it) }
                    val replacement = remaining.lastOrNull {
                        it.optString("parentId") == layer.optString("parentId")
                    } ?: remaining.firstOrNull {
                        it.getString("id") == layer.optString("parentId")
                    } ?: remaining.lastOrNull()
                    state.put("selectedLayerId", replacement?.getString("id") ?: "")
                }
            }
            "LAYER_COPY" -> {
                val (_, original) = find(p.getString("id"))
                val mapping = mutableMapOf(original.getString("id") to p.getString("newId"))
                val subtree = mutableListOf(original)
                var cursor = 0
                while (cursor < subtree.size) {
                    val parent = subtree[cursor++]
                    for (i in 0 until layers.length()) {
                        val child = layers.getJSONObject(i)
                        if (child.optString("parentId") == parent.getString("id")) {
                            mapping[child.getString("id")] = UUID.nameUUIDFromBytes(
                                "${p.getString("newId")}:${child.getString("id")}".toByteArray()).toString()
                            subtree.add(child)
                        }
                    }
                }
                subtree.forEach { source ->
                    val copy = JSONObject(source.toString())
                    copy.put("id", mapping.getValue(source.getString("id")))
                    copy.put("parentId", mapping[source.optString("parentId")] ?: source.optString("parentId"))
                    copy.put("name", source.getString("name") + " 副本")
                    val strokes = copy.getJSONArray("strokes")
                    for (i in 0 until strokes.length()) {
                        val stroke = strokes.getJSONObject(i)
                        stroke.put("id", UUID.nameUUIDFromBytes("${copy.getString("id")}:$i".toByteArray()).toString())
                        stroke.put("layerId", copy.getString("id"))
                    }
                    copy.optJSONArray("contentOrder")?.let { order ->
                        val oldStrokes = source.getJSONArray("strokes")
                        val newStrokes = copy.getJSONArray("strokes")
                        for (i in 0 until order.length()) {
                            val event = order.getJSONObject(i)
                            if (event.optString("kind") == "stroke") {
                                val oldId = event.getString("id")
                                val index = (0 until oldStrokes.length()).firstOrNull {
                                    oldStrokes.getJSONObject(it).getString("id") == oldId
                                }
                                if (index != null) event.put("id", newStrokes.getJSONObject(index).getString("id"))
                            }
                        }
                    }
                    layers.put(copy)
                }
                if (p.optBoolean("select", false)) state.put("selectedLayerId", p.getString("newId"))
            }
            "STROKE_ADD" -> {
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") == "paint" && !lockedByParent(layer, layers))
                val points = p.getJSONArray("points")
                require(points.length() in 1..10000)
                for (i in 0 until points.length()) {
                    val point = points.getJSONArray(i)
                    require(point.length() in 2..3)
                    require(point.getDouble(0).isFinite() && point.getDouble(1).isFinite())
                    if (point.length() == 3) require(point.getDouble(2) in 0.0..1.0)
                }
                requireColor(p.optString("color", "#FF000000"))
                require(p.getDouble("width") in 0.1..512.0)
                require(p.optDouble("opacity", 1.0) in 0.0..1.0)
                val tool = p.optString("tool", "pencil")
                require(tool in setOf("pencil", "ink", "eraser", "soft", "spray", "mirror", "dyna", "calligraphy",
                    "line", "rectangle", "ellipse", "polygon", "polyline", "bezier", "gradient"))
                require(when (tool) {
                    "line", "rectangle", "ellipse", "gradient" -> points.length() == 2
                    "polygon" -> points.length() >= 3
                    "polyline" -> points.length() >= 2
                    "bezier" -> points.length() in 4..1024 && (points.length() - 1) % 3 == 0
                    else -> true
                }) { "形状顶点数量无效" }
                if (p.optBoolean("fillShape", false))
                    require(tool in setOf("rectangle", "ellipse", "polygon")) {
                        "只有闭合形状可使用前景色填充"
                    }
                if (tool == "calligraphy") {
                    val angle = p.optDouble("nibAngle", 45.0)
                    require(angle.isFinite() && angle in 0.0..180.0) {
                        "书法笔尖角度必须在 0–180° 之间"
                    }
                    p.put("nibAngle", angle)
                }
                if (tool == "dyna") {
                    val mass = p.optDouble("mass", 0.5)
                    val drag = p.optDouble("drag", 0.15)
                    require(mass.isFinite() && mass in 0.0..1.0 &&
                        drag.isFinite() && drag in 0.0..1.0) { "动态画笔的惯性和阻力必须在 0–1 之间" }
                    p.put("mass", mass).put("drag", drag)
                }
                if (tool == "mirror") {
                    val direction = p.optString("mirrorDirection", "vertical")
                    require(direction in setOf("vertical", "horizontal", "quad", "radial", "snowflake",
                        "translate", "copytranslate", "interval")) {
                        "对称方式无效"
                    }
                    val count = p.optInt("mirrorCount", 6)
                    require(count in 2..12) { "旋转对称画笔数必须在 2–12 之间" }
                    // Store the axis in the stroke event so history, export and AI replay
                    // retain the original mirror center even after the canvas is cropped.
                    val radius = p.optDouble("mirrorRadius", 80.0)
                    require(radius.isFinite() && radius in 0.0..512.0) {
                        "随机平移半径必须在 0–512 px 之间"
                    }
                    val seed = if (p.has("mirrorSeed")) p.getInt("mirrorSeed")
                        else kotlin.random.Random.nextInt(Int.MAX_VALUE)
                    require(seed >= 0) { "随机种子不能为负数" }
                    val centers = p.optJSONArray("mirrorCenters") ?: JSONArray()
                    require(centers.length() <= 11) { "子画笔数量不能超过 11 支" }
                    for (i in 0 until centers.length()) {
                        val point = centers.getJSONArray(i)
                        require(point.length() == 2 && point.getDouble(0).isFinite() &&
                            point.getDouble(1).isFinite()) { "子画笔位置无效" }
                        require(point.getDouble(0) in 0.0..state.getDouble("width") &&
                            point.getDouble(1) in 0.0..state.getDouble("height")) {
                            "子画笔位置不在画布范围内"
                        }
                    }
                    p.put("mirrorCenters", centers)
                    val intervalX = p.optInt("mirrorIntervalX", 1024)
                    val intervalY = p.optInt("mirrorIntervalY", 1024)
                    require(intervalX in 128..2048 && intervalY in 128..2048) {
                        "横纵间隔必须在 128–2048 px 之间"
                    }
                    val canvasWidth = state.getInt("width")
                    val canvasHeight = state.getInt("height")
                    if (direction == "interval") require(
                        (canvasWidth / intervalX + 1) * (canvasHeight / intervalY + 1) <= 48
                    ) { "间隔复制画笔超过 48 支，请加大间隔" }
                    p.put("mirrorIntervalX", intervalX).put("mirrorIntervalY", intervalY)
                        .put("canvasWidth", canvasWidth).put("canvasHeight", canvasHeight)
                    p.put("mirrorDirection", direction).put("mirrorCount", count)
                        .put("mirrorRadius", radius).put("mirrorSeed", seed)
                    val axisX = p.optDouble("axisX", state.getInt("width") / 2.0)
                    val axisY = p.optDouble("axisY", state.getInt("height") / 2.0)
                    require(axisX.isFinite() && axisX in 0.0..state.getDouble("width") &&
                        axisY.isFinite() && axisY in 0.0..state.getDouble("height")) {
                        "镜像轴不在画布范围内"
                    }
                    p.put("axisX", axisX).put("axisY", axisY)
                }
                if (tool == "gradient") {
                    val mode = p.optString("gradientMode", "linear")
                    require(mode in setOf("linear", "radial", "angular")) { "渐变模式无效" }
                    p.put("gradientMode", mode)
                    if (p.has("gradientEndColor"))
                        requireColor(p.getString("gradientEndColor"))
                    val a = points.getJSONArray(0); val b = points.getJSONArray(1)
                    require(kotlin.math.hypot(b.getDouble(0) - a.getDouble(0),
                        b.getDouble(1) - a.getDouble(1)) >= 0.01) { "请拖出渐变方向" }
                }
                layer.getJSONArray("strokes").put(JSONObject(p.toString()))
                layer.optJSONArray("contentOrder")?.put(JSONObject().put("kind", "stroke")
                    .put("id", p.getString("id")))
            }
            "STROKE_ERASE" -> {
                val layer = find(p.getString("layerId")).second
                require(!lockedByParent(layer, layers))
                val strokes = layer.getJSONArray("strokes")
                val index = (0 until strokes.length()).firstOrNull { strokes.getJSONObject(it).getString("id") == p.getString("strokeId") }
                    ?: error("笔画不存在")
                strokes.remove(index)
            }
            "TRANSFORM" -> {
                val layer = find(p.getString("id")).second
                require(!lockedByParent(layer, layers))
                for (field in listOf("x", "y", "rotation", "scale")) if (p.has(field)) {
                    val value = p.getDouble(field)
                    require(value.isFinite() && (field != "scale" || value in 0.01..100.0))
                    layer.put(field, value)
                }
            }
            "SELECTION_CREATE" -> {
                for (field in listOf("x", "y", "width", "height")) require(p.getDouble(field).isFinite())
                val shape = p.optString("shape", "rect")
                require(shape in setOf("rect", "ellipse", "polygon")) { "选区形状无效" }
                require(p.getDouble("width") >= 0 && p.getDouble("height") >= 0)
                if (shape != "rect") require(p.getDouble("width") > 0 && p.getDouble("height") > 0)
                if (shape == "polygon") {
                    val vertices = p.getJSONArray("vertices")
                    require(vertices.length() in 3..2048) { "多边形选区需要 3–2048 个顶点" }
                    for (index in 0 until vertices.length()) {
                        val vertex = vertices.getJSONArray(index)
                        require(vertex.length() == 2 &&
                            vertex.getDouble(0) in 0.0..1.0 &&
                            vertex.getDouble(1) in 0.0..1.0) { "选区顶点范围无效" }
                    }
                }
                state.put("selection", JSONObject(p.toString()))
            }
            "SELECTION_CLEAR" -> state.put("selection", JSONObject.NULL)
            "SELECTION_EDIT" -> {
                val selection = state.optJSONObject("selection") ?: error("请先创建选区")
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") == "paint" && !lockedByParent(layer, layers))
                val transform = layerMatrix(layer, layers)
                val inverse = Matrix().also { require(transform.invert(it)) { "图层变换不可逆" } }
                val strokes = layer.getJSONArray("strokes")
                val chosen = (0 until strokes.length()).filter { index ->
                    intersects(strokes.getJSONObject(index).getJSONArray("points"), selection, transform)
                }
                require(chosen.isNotEmpty()) { "选区中没有笔画" }
                val action = p.getString("action")
                require(action != "ROTATE" || selection.optString("shape", "rect") == "rect") {
                    "非矩形选区的旋转需要可旋转蒙版，暂不可用"
                }
                val canvasCenterX = selection.getDouble("x") + selection.getDouble("width") / 2.0
                val canvasCenterY = selection.getDouble("y") + selection.getDouble("height") / 2.0
                val center = floatArrayOf(canvasCenterX.toFloat(), canvasCenterY.toFloat())
                inverse.mapPoints(center)
                val centerX = center[0].toDouble(); val centerY = center[1].toDouble()
                val movement = floatArrayOf(p.optDouble("dx", 0.0).toFloat(), p.optDouble("dy", 0.0).toFloat())
                inverse.mapVectors(movement)
                val copyOffset = floatArrayOf(20f, 20f)
                inverse.mapVectors(copyOffset)
                when (action) {
                    "DELETE" -> for (index in chosen.asReversed()) strokes.remove(index)
                    "COPY", "MOVE", "SCALE", "ROTATE" -> {
                        val source = chosen.map { JSONObject(strokes.getJSONObject(it).toString()) }
                        for ((ordinal, stroke) in source.withIndex()) {
                            val points = stroke.getJSONArray("points")
                            for (i in 0 until points.length()) {
                                val point = points.getJSONArray(i)
                                val x = point.getDouble(0)
                                val y = point.getDouble(1)
                                val transformed = when (action) {
                                    "MOVE" -> (x + movement[0]) to (y + movement[1])
                                    "SCALE" -> {
                                        val factor = p.getDouble("factor").also { require(it in 0.01..100.0) }
                                        (centerX + (x - centerX) * factor) to (centerY + (y - centerY) * factor)
                                    }
                                    "ROTATE" -> {
                                        val angle = Math.toRadians(p.getDouble("degrees"))
                                        val dx = x - centerX; val dy = y - centerY
                                        (centerX + dx * cos(angle) - dy * sin(angle)) to
                                            (centerY + dx * sin(angle) + dy * cos(angle))
                                    }
                                    else -> (x + copyOffset[0]) to (y + copyOffset[1])
                                }
                                require(transformed.first.isFinite() && transformed.second.isFinite())
                                point.put(0, transformed.first).put(1, transformed.second)
                            }
                            if (action == "COPY") stroke.put("id", "${p.getString("copyId")}:$ordinal")
                            else strokes.put(chosen[ordinal], stroke)
                            if (action == "COPY") {
                                strokes.put(stroke)
                                layer.optJSONArray("contentOrder")?.put(
                                    JSONObject().put("kind", "stroke").put("id", stroke.getString("id")))
                            }
                        }
                    }
                    else -> error("未知选区操作")
                }
                when (action) {
                    "MOVE" -> {
                        selection.put("x", selection.getDouble("x") + p.getDouble("dx"))
                        selection.put("y", selection.getDouble("y") + p.getDouble("dy"))
                    }
                    "SCALE" -> {
                        val factor = p.getDouble("factor")
                        selection.put("width", selection.getDouble("width") * factor)
                        selection.put("height", selection.getDouble("height") * factor)
                        selection.put("x", canvasCenterX - selection.getDouble("width") / 2)
                        selection.put("y", canvasCenterY - selection.getDouble("height") / 2)
                    }
                    "COPY" -> {
                        selection.put("x", selection.getDouble("x") + 20.0)
                        selection.put("y", selection.getDouble("y") + 20.0)
                    }
                }
            }
            "CROP" -> {
                state.put("width", p.getInt("width").also { require(it in 64..4096) })
                state.put("height", p.getInt("height").also { require(it in 64..4096) })
                val dx = p.optDouble("x", 0.0); val dy = p.optDouble("y", 0.0)
                require(dx.isFinite() && dy.isFinite())
                for (i in 0 until layers.length()) {
                    val layer = layers.getJSONObject(i)
                    if (layer.optString("parentId").isBlank()) {
                        layer.put("x", layer.getDouble("x") - dx)
                        layer.put("y", layer.getDouble("y") - dy)
                    }
                }
                state.put("selection", JSONObject.NULL)
            }
            else -> error("未知画室操作：$type")
        }
    }

    private fun historyStacks(operations: JSONArray): Pair<List<String>, List<String>> {
        val undoStack = mutableListOf<String>()
        val redoStack = mutableListOf<String>()
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val id = op.getString("id")
            val target = op.optJSONObject("parameters")?.optString("targetId") ?: ""
            when (op.getString("type")) {
                "REVERT" -> { undoStack.remove(target); redoStack.add(target) }
                "RESTORE" -> { redoStack.remove(target); undoStack.add(target) }
                else -> { undoStack.add(id); redoStack.clear() }
            }
        }
        return undoStack to redoStack
    }

    fun history(actor: String, redo: Boolean): JSONObject = locked {
        val (undoStack, redoStack) = historyStacks(loadCurrent().getJSONArray("operations"))
        val target = if (redo) redoStack.lastOrNull() else undoStack.lastOrNull()
        require(target != null) { if (redo) "没有可重做的操作" else "没有可撤销的操作" }
        // History commits under the same lock; the replay validates dependencies before writing.
        appendToCurrent(actor, if (redo) "RESTORE" else "REVERT", JSONObject().put("targetId", target))
    }

    /** Jump between reachable states as one atomic history change. */
    fun historyJump(actor: String, targetId: String, expectedRevision: Int): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val doc = loadCurrent()
        val operations = doc.getJSONArray("operations")
        require(expectedRevision == operations.length()) {
            "工程已由另一端更新，请刷新足迹再操作"
        }
        val (undoStack, redoStack) = historyStacks(operations)
        val reachable = undoStack + redoStack.asReversed()
        val destination = if (targetId.isEmpty()) 0 else reachable.indexOf(targetId) + 1
        require(destination in 0..reachable.size &&
            (targetId.isEmpty() || destination > 0)) { "足迹状态已不在当前分支" }
        val current = undoStack.size
        if (destination == current) return@locked snapshot(doc)
        val changes = if (destination < current) {
            undoStack.subList(destination, current).asReversed().map { "REVERT" to it }
        } else {
            redoStack.asReversed().take(destination - current).map { "RESTORE" to it }
        }
        for ((type, id) in changes) {
            operations.put(JSONObject().put("id", UUID.randomUUID().toString())
                .put("actor", actor).put("type", type)
                .put("parameters", JSONObject().put("targetId", id))
                .put("timestamp", System.currentTimeMillis()))
        }
        // Replay validates every dependency before the draft is written.
        val result = snapshot(doc)
        atomic(draft(doc.getString("id")), doc.toString())
        result.put("lastOperationId", operations.getJSONObject(operations.length() - 1).getString("id"))
    }

    fun clipboardInfo(): JSONObject = locked {
        if (editClipboard.isFile) JSONObject(editClipboard.readText()) else JSONObject()
    }

    private fun editingRectangle(state: JSONObject): IntArray {
        val canvasWidth = state.getInt("width")
        val canvasHeight = state.getInt("height")
        val selected = state.optJSONObject("selection")
        if (selected == null) return intArrayOf(0, 0, canvasWidth, canvasHeight)
        val left = kotlin.math.floor(selected.getDouble("x")).toInt().coerceIn(0, canvasWidth)
        val top = kotlin.math.floor(selected.getDouble("y")).toInt().coerceIn(0, canvasHeight)
        val right = kotlin.math.ceil(selected.getDouble("x") + selected.getDouble("width"))
            .toInt().coerceIn(0, canvasWidth)
        val bottom = kotlin.math.ceil(selected.getDouble("y") + selected.getDouble("height"))
            .toInt().coerceIn(0, canvasHeight)
        require(right > left && bottom > top) { "选区不在画布范围内" }
        return intArrayOf(left, top, right - left, bottom - top)
    }

    private fun copyableLayer(state: JSONObject): JSONObject {
        val layers = state.getJSONArray("layers")
        val selected = state.optString("selectedLayerId")
        val layer = (0 until layers.length()).map { layers.getJSONObject(it) }
            .firstOrNull { it.getString("id") == selected } ?: error("请先选择图层")
        require(layer.getString("kind") in setOf("paint", "image") &&
            layer.getBoolean("visible") && layer.optString("parentId").isBlank()) {
            "请选择可见的根绘画图层或图像图层"
        }
        return layer
    }

    private fun editableLayer(state: JSONObject): JSONObject {
        val layers = state.getJSONArray("layers")
        val id = state.optString("selectedLayerId")
        val layer = (0 until layers.length()).map { layers.getJSONObject(it) }
            .firstOrNull { it.getString("id") == id } ?: error("请先选择图层")
        require(layer.getString("kind") == "paint" || layer.getString("kind") == "image") {
            "当前图层不支持像素编辑"
        }
        require(!lockedByParent(layer, layers) && layer.getBoolean("visible")) {
            "图层已锁定或隐藏"
        }
        require(layer.optString("parentId").isBlank() &&
            layer.getDouble("x") == 0.0 && layer.getDouble("y") == 0.0 &&
            layer.getDouble("scale") == 1.0 && layer.getDouble("rotation") == 0.0) {
            "请先取消图层变换和分组，再编辑选区像素"
        }
        return layer
    }

    fun copyPixels(actor: String, merged: Boolean = false, cut: Boolean = false): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        require(!merged || !cut)
        val doc = loadCurrent()
        val state = replay(doc)
        val area = editingRectangle(state)
        val layer = if (merged) null else if (cut) editableLayer(state) else copyableLayer(state)
        val view = snapshot(doc)
        if (layer != null) {
            val imageState = view.getJSONObject("state")
            imageState.put("background", "#00000000")
            val only = imageState.getJSONArray("layers")
            for (i in 0 until only.length()) {
                val candidate = only.getJSONObject(i)
                if (candidate.getString("id") != layer.getString("id")) {
                    candidate.put("visible", false)
                } else {
                    // Copy the selected layer's pixels; compositing belongs to merged copy.
                    candidate.put("blend", "normal").put("opacity", 1.0)
                }
            }
        }
        val bitmap = ArtRenderer.render(this, view)
        val bytes = try {
            val clipped = Bitmap.createBitmap(area[2], area[3], Bitmap.Config.ARGB_8888)
            try {
                Canvas(clipped).drawBitmap(bitmap, -area[0].toFloat(), -area[1].toFloat(), Paint())
                state.optJSONObject("selection")?.takeIf {
                    it.optString("shape", "rect") != "rect"
                }?.let { selected ->
                    // A small reusable strip avoids allocating a second full 4K bitmap.
                    val mask = Bitmap.createBitmap(area[2], minOf(area[3], 128),
                        Bitmap.Config.ARGB_8888)
                    try {
                        val maskCanvas = Canvas(mask)
                        val destination = Canvas(clipped)
                        val selectionPath = ArtSelection.path(selected)
                        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                        val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        }
                        for (row in 0 until area[3] step mask.height) {
                            mask.eraseColor(Color.TRANSPARENT)
                            maskCanvas.save()
                            maskCanvas.translate(-area[0].toFloat(), -(area[1] + row).toFloat())
                            maskCanvas.drawPath(selectionPath, shapePaint)
                            maskCanvas.restore()
                            destination.drawBitmap(mask, 0f, row.toFloat(), clipPaint)
                        }
                    } finally { mask.recycle() }
                }
                ByteArrayOutputStream().use { stream ->
                    require(clipped.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    stream.toByteArray()
                }
            } finally { clipped.recycle() }
        } finally { bitmap.recycle() }
        require(bytes.size <= 8 * 1024 * 1024) { "选区图片超过 8 MB" }
        val asset = UUID.randomUUID().toString()
        atomicBytes(assetFile(asset), bytes)
        val clipboard = JSONObject().put("asset", asset).put("width", area[2]).put("height", area[3])
            .put("sourceActor", actor).put("sourceDocument", doc.getString("id"))
        atomic(editClipboard, clipboard.toString())
        if (cut) {
            val params = JSONObject().put("layerId", layer!!.getString("id"))
                .put("mode", "CLEAR").put("x", area[0]).put("y", area[1])
                .put("width", area[2]).put("height", area[3])
            state.optJSONObject("selection")?.let {
                params.put("selection", JSONObject(it.toString()))
            }
            appendToCurrent(actor, "PIXEL_EDIT", params)
        }
        clipboard.put("cut", cut)
    }

    fun pastePixels(actor: String, intoActive: Boolean = false,
                    atX: Int? = null, atY: Int? = null): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        require(editClipboard.isFile) { "画室剪贴板为空" }
        val clip = JSONObject(editClipboard.readText())
        val asset = clip.getString("asset")
        require(assetFile(asset).isFile) { "画室剪贴板图片已丢失" }
        val state = replay(loadCurrent())
        require((atX == null) == (atY == null)) { "请同时提供光标 X 与 Y" }
        if (atX != null && atY != null) {
            require(atX in 0..state.getInt("width") && atY in 0..state.getInt("height")) {
                "光标位置超出画布"
            }
        }
        val x = if (atX != null) atX - clip.getInt("width") / 2
            else (state.getInt("width") - clip.getInt("width")) / 2
        val y = if (atY != null) atY - clip.getInt("height") / 2
            else (state.getInt("height") - clip.getInt("height")) / 2
        val mode = if (intoActive) "PIXEL_PASTE" else "PASTE_IMAGE"
        val target = if (intoActive) editableLayer(state).getString("id") else ""
        appendToCurrent(actor, mode, JSONObject().put("asset", asset).put("id", UUID.randomUUID().toString())
            .put("layerId", target).put("x", x).put("y", y))
    }

    fun pasteAsNew(actor: String): JSONObject {
        val clip = clipboardInfo()
        require(clip.has("asset")) { "画室剪贴板为空" }
        require(clip.getInt("width") in 64..4096 && clip.getInt("height") in 64..4096) {
            "新画布至少需要 64 × 64 像素"
        }
        val bytes = locked { assetFile(clip.getString("asset")).readBytes() }
        return openImage(Base64.encodeToString(bytes, Base64.NO_WRAP), "粘贴图像", actor)
    }

    /**
     * Flood fill the connected region of the active, untransformed root layer.
     * A transparent PNG overlay is recorded as PIXEL_PASTE, so undo, archive
     * import and the compositing order all use the established asset pipeline.
     */
    fun fillContiguous(actor: String, x: Int, y: Int, color: String,
                       expectedRevision: Int? = null, tolerance: Int = 0,
                       referenceAllLayers: Boolean = false,
                       erase: Boolean = false): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        requireColor(color)
        require(tolerance in 0..100) { "颜色容差必须在 0–100 之间" }
        val fill = Color.parseColor(color)
        if (!erase) require(Color.alpha(fill) > 0) { "填充色不能完全透明；擦除区域请启用擦除模式" }
        val doc = loadCurrent()
        if (expectedRevision != null) require(doc.getJSONArray("operations").length() == expectedRevision) {
            "工程已由另一位编辑者更新，请重新读取画布"
        }
        val state = replay(doc)
        val layer = editableLayer(state)
        val canvasWidth = state.getInt("width")
        val canvasHeight = state.getInt("height")
        require(x in 0 until canvasWidth && y in 0 until canvasHeight) { "填充位置不在画布内" }
        val area = editingRectangle(state)
        val shapedSelection = state.optJSONObject("selection")?.takeIf {
            it.optString("shape", "rect") != "rect"
        }
        val region = shapedSelection?.let {
            Region().apply {
                setPath(ArtSelection.path(it), Region(0, 0, canvasWidth, canvasHeight))
            }
        }
        if (region != null) require(region.contains(x, y)) {
            "填充位置不在当前选区内"
        }
        val leftLimit = area[0]
        val topLimit = area[1]
        val rightLimit = leftLimit + area[2]
        val bottomLimit = topLimit + area[3]
        require(x in leftLimit until rightLimit && y in topLimit until bottomLimit) {
            "填充位置不在选区内"
        }
        val view = snapshot(doc)
        if (!referenceAllLayers) {
            val isolated = view.getJSONObject("state")
            isolated.put("background", "#00000000")
            val visibleLayers = isolated.getJSONArray("layers")
            for (i in 0 until visibleLayers.length()) {
                val candidate = visibleLayers.getJSONObject(i)
                if (candidate.getString("id") != layer.getString("id")) {
                    candidate.put("visible", false)
                } else {
                    candidate.put("opacity", 1.0).put("blend", "normal")
                }
            }
        }
        val source = ArtRenderer.render(this, view)
        try {
            val target = source.getPixel(x, y)
            if (!erase && !referenceAllLayers && tolerance == 0 && target == fill &&
                Color.alpha(fill) == 255) return@locked snapshot(doc)
            val channelLimit = tolerance * 255 / 100
            // Use a separate visited mask: mutating reference pixels is unsafe as
            // soon as tolerance can match the marker color.
            val visited = java.util.BitSet(canvasWidth * canvasHeight)
            var stack = IntArray(2048)
            var count = 0
            fun push(px: Int, py: Int) {
                if (count == stack.size) stack = stack.copyOf(stack.size * 2)
                stack[count++] = py * canvasWidth + px
            }
            fun matches(px: Int, py: Int): Boolean {
                if (px !in leftLimit until rightLimit || py !in topLimit until bottomLimit ||
                    visited.get(py * canvasWidth + px) ||
                    (region != null && !region.contains(px, py))) return false
                val pixel = source.getPixel(px, py)
                return kotlin.math.abs(Color.alpha(pixel) - Color.alpha(target)) <= channelLimit &&
                    kotlin.math.abs(Color.red(pixel) - Color.red(target)) <= channelLimit &&
                    kotlin.math.abs(Color.green(pixel) - Color.green(target)) <= channelLimit &&
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(target)) <= channelLimit
            }
            var minX = x; var maxX = x
            var minY = y; var maxY = y
            push(x, y)
            while (count > 0) {
                val position = stack[--count]
                val row = position / canvasWidth
                val seed = position % canvasWidth
                if (!matches(seed, row)) continue
                var begin = seed
                while (begin > leftLimit && matches(begin - 1, row)) begin--
                var end = seed
                while (end + 1 < rightLimit && matches(end + 1, row)) end++
                for (column in begin..end) {
                    visited.set(row * canvasWidth + column)
                }
                minX = minOf(minX, begin); maxX = maxOf(maxX, end)
                minY = minOf(minY, row); maxY = maxOf(maxY, row)
                for (neighbor in intArrayOf(row - 1, row + 1)) {
                    if (neighbor !in topLimit until bottomLimit) continue
                    var column = begin
                    while (column <= end) {
                        if (matches(column, neighbor)) {
                            push(column, neighbor)
                            do { column++ } while (column <= end && matches(column, neighbor))
                        } else column++
                    }
                }
            }
            val clipped = Bitmap.createBitmap(maxX - minX + 1, maxY - minY + 1,
                Bitmap.Config.ARGB_8888)
            val bytes = try {
                for (row in minY..maxY) {
                    for (column in minX..maxX) {
                        if (visited.get(row * canvasWidth + column))
                            clipped.setPixel(column - minX, row - minY,
                                if (erase) Color.WHITE else fill)
                    }
                }
                ByteArrayOutputStream().use { stream ->
                    require(clipped.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    stream.toByteArray()
                }
            } finally { clipped.recycle() }
            require(bytes.size <= MAX_ASSET_BYTES) { "填充区域图片超过 8 MB" }
            val asset = UUID.randomUUID().toString()
            atomicBytes(assetFile(asset), bytes)
            appendToCurrent(actor, "PIXEL_PASTE", JSONObject()
                .put("asset", asset).put("layerId", layer.getString("id"))
                .put("action", if (erase) "FILL_CONTIGUOUS_ERASE" else "FILL_CONTIGUOUS")
                .put("x", minX).put("y", minY))
        } finally { source.recycle() }
    }

    fun editPixels(actor: String, mode: String, color: String = ""): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        require(mode == "CLEAR" || mode == "FILL")
        val state = replay(loadCurrent())
        val layer = editableLayer(state)
        val area = editingRectangle(state)
        if (mode == "FILL") requireColor(color)
        val params = JSONObject().put("layerId", layer.getString("id"))
            .put("mode", mode).put("color", color).put("x", area[0]).put("y", area[1])
            .put("width", area[2]).put("height", area[3])
        state.optJSONObject("selection")?.let {
            params.put("selection", JSONObject(it.toString()))
        }
        appendToCurrent(actor, "PIXEL_EDIT", params)
    }

    private fun appendToCurrent(actor: String, type: String, params: JSONObject): JSONObject {
        val doc = loadCurrent()
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("actor", actor)
            .put("type", type).put("parameters", params).put("timestamp", System.currentTimeMillis())
        doc.getJSONArray("operations").put(op)
        val result = snapshot(doc)
        atomic(draft(doc.getString("id")), doc.toString())
        return result.put("lastOperationId", op.getString("id"))
    }

    private fun contentOrder(layer: JSONObject): JSONArray {
        layer.optJSONArray("contentOrder")?.let { return it }
        val order = JSONArray()
        val strokes = layer.getJSONArray("strokes")
        for (i in 0 until strokes.length()) {
            order.put(JSONObject().put("kind", "stroke").put("id", strokes.getJSONObject(i).getString("id")))
        }
        layer.put("contentOrder", order)
        return order
    }

    private fun newLayer(id: String, kind: String, name: String, parent: String, asset: String): JSONObject =
        JSONObject().put("id", id).put("kind", kind).put("name", name).put("parentId", parent)
            .put("visible", true).put("opacity", 1.0).put("locked", false).put("blend", "normal")
            .put("x", 0.0).put("y", 0.0).put("scale", 1.0).put("rotation", 0.0)
            .put("asset", asset).put("strokes", JSONArray())

    private fun lockedByParent(layer: JSONObject, layers: JSONArray): Boolean {
        var parent = layer
        repeat(layers.length() + 1) {
            if (parent.getBoolean("locked")) return true
            val id = parent.optString("parentId")
            if (id.isBlank()) return false
            parent = (0 until layers.length()).map { layers.getJSONObject(it) }
                .firstOrNull { it.getString("id") == id } ?: error("图层组不存在")
        }
        error("图层组存在循环引用")
    }

    private fun layerMatrix(layer: JSONObject, layers: JSONArray): Matrix {
        val chain = mutableListOf(layer)
        var parentId = layer.optString("parentId")
        repeat(layers.length()) {
            if (parentId.isNotBlank()) {
                val parent = (0 until layers.length()).map { layers.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == parentId } ?: error("图层组不存在")
                chain.add(parent)
                parentId = parent.optString("parentId")
            }
        }
        require(parentId.isBlank()) { "图层组存在循环引用" }
        return Matrix().also { matrix ->
            for (item in chain) {
                matrix.postScale(item.getDouble("scale").toFloat(), item.getDouble("scale").toFloat())
                matrix.postRotate(item.getDouble("rotation").toFloat())
                matrix.postTranslate(item.getDouble("x").toFloat(), item.getDouble("y").toFloat())
            }
        }
    }

    private fun intersects(points: JSONArray, selection: JSONObject, matrix: Matrix): Boolean {
        var previous: FloatArray? = null
        for (i in 0 until points.length()) {
            val point = points.getJSONArray(i)
            val mapped = floatArrayOf(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
            matrix.mapPoints(mapped)
            if (ArtSelection.contains(selection, mapped[0].toDouble(), mapped[1].toDouble()))
                return true
            previous?.let { old ->
                if (ArtSelection.intersectsSegment(selection,
                        old[0].toDouble(), old[1].toDouble(),
                        mapped[0].toDouble(), mapped[1].toDouble())) return true
            }
            previous = mapped
        }
        return false
    }

    private fun loadCurrent(): JSONObject {
        require(pointer.isFile) { "请先新建或打开工程" }
        val id = pointer.readText().trim()
        validateId(id)
        return JSONObject(draft(id).readText())
    }

    private fun draft(id: String): File { validateId(id); return File(drafts, "$id.json") }
    private fun archive(id: String): File { validateId(id); return File(documents, "$id.ailart") }
    fun assetFile(id: String): File { validateId(id); return File(assets, "$id.png") }
    private fun validateId(id: String) { require(id.matches(Regex("[a-f0-9-]{36}"))) { "工程标识无效" } }
    private fun requireColor(value: String) { require(value.matches(Regex("#[A-Fa-f0-9]{8}"))) { "颜色必须是 #AARRGGBB" } }

    private fun atomic(file: File, value: String) = atomicBytes(file, value.toByteArray(Charsets.UTF_8))
    private fun atomicBytes(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            require(temp.renameTo(file)) { "写入工程失败" }
        } finally { temp.delete() }
    }

    private companion object {
        val processLock = Any()
        const val MAX_ASSET_BYTES = 8 * 1024 * 1024
    }
}
