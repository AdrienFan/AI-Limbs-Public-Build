package com.ai.limbs.plugins.artstudio

import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/** Both the Host presentation and Resident business runtime use the same plugin data directory. */
internal class ArtStore(private val root: File) {
    private val drafts = File(root, "drafts")
    private val documents = File(root, "documents")
    private val assets = File(root, "assets")
    private val pointer = File(root, "current.txt")
    private val lockFile = File(root, "art-studio.lock")

    init {
        require(drafts.mkdirs() || drafts.isDirectory)
        require(documents.mkdirs() || documents.isDirectory)
        require(assets.mkdirs() || assets.isDirectory)
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(processLock) {
        FileOutputStream(lockFile, true).channel.use { channel ->
            val lock: FileLock = channel.lock()
            try { block() } finally { lock.release() }
        }
    }

    fun create(width: Int, height: Int, background: String = "#FFFFFFFF"): JSONObject = locked {
        require(width in 64..4096 && height in 64..4096) { "画布边长需要在 64–4096 像素之间" }
        requireColor(background)
        val id = UUID.randomUUID().toString()
        val base = JSONObject().put("width", width).put("height", height)
            .put("background", background).put("layers", JSONArray())
            .put("selection", JSONObject.NULL)
        val doc = JSONObject().put("format", 1).put("id", id).put("base", base)
            .put("operations", JSONArray())
        atomic(draft(id), doc.toString())
        atomic(pointer, id)
        snapshot(doc)
    }

    fun current(): JSONObject = locked { snapshot(loadCurrent()) }

    fun revision(): String = locked {
        if (!pointer.isFile) ""
        else {
            val id = pointer.readText().trim()
            validateId(id)
            "$id:${draft(id).lastModified()}:${draft(id).length()}"
        }
    }

    fun list(): JSONArray = locked {
        JSONArray().also { out ->
            drafts.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
                val doc = JSONObject(file.readText())
                out.put(JSONObject().put("id", doc.getString("id"))
                    .put("saved", archive(doc.getString("id")).exists()))
            }
        }
    }

    fun apply(actor: String, type: String, params: JSONObject): JSONObject = locked {
        require(actor == "AWEI" || actor == "LANER")
        val doc = loadCurrent()
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

    fun save(): JSONObject = locked {
        val doc = loadCurrent()
        val id = doc.getString("id")
        val destination = archive(id)
        val temp = File(documents, "$id.tmp")
        try {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(doc.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val used = mutableSetOf<String>()
                val operations = doc.getJSONArray("operations")
                for (i in 0 until operations.length()) {
                    val operation = operations.getJSONObject(i)
                    if (operation.getString("type") != "IMAGE_IMPORT") continue
                    val asset = operation.getJSONObject("parameters").getString("asset")
                    if (asset.isNotBlank() && used.add(asset)) {
                        zip.putNextEntry(ZipEntry("assets/$asset.png"))
                        zip.write(assetFile(asset).readBytes())
                        zip.closeEntry()
                    }
                }
            }
            require(temp.renameTo(destination)) { "保存工程文件失败" }
        } finally { temp.delete() }
        JSONObject().put("id", id).put("path", destination.absolutePath)
            .put("bytes", destination.length())
    }

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
        result
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
        return JSONObject().put("id", doc.getString("id"))
            .put("state", state).put("operations", JSONArray(doc.getJSONArray("operations").toString()))
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
                layers.put(JSONObject().put("id", id).put("kind", kind)
                    .put("name", p.optString("name", if (kind == "group") "图层组" else "图层"))
                    .put("parentId", parentId)
                    .put("visible", true).put("opacity", 1.0).put("locked", false)
                    .put("blend", "normal").put("x", 0.0).put("y", 0.0)
                    .put("scale", 1.0).put("rotation", 0.0)
                    .put("asset", p.optString("asset"))
                    .put("strokes", JSONArray()))
            }
            "LAYER_SELECT" -> state.put("selectedLayerId", find(p.getString("id")).second.getString("id"))
            "LAYER_RENAME" -> find(p.getString("id")).second.put("name", p.getString("name").take(100))
            "LAYER_VISIBLE" -> find(p.getString("id")).second.put("visible", p.getBoolean("visible"))
            "LAYER_OPACITY" -> find(p.getString("id")).second.put("opacity", p.getDouble("opacity").also { require(it in 0.0..1.0) })
            "LAYER_LOCK" -> find(p.getString("id")).second.put("locked", p.getBoolean("locked"))
            "LAYER_BLEND" -> find(p.getString("id")).second.put("blend", p.getString("blend").also { require(it in setOf("normal", "multiply", "screen", "add")) })
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
            }
            "LAYER_COPY" -> {
                val (_, original) = find(p.getString("id"))
                val copy = JSONObject(original.toString()).put("id", p.getString("newId"))
                    .put("name", original.getString("name") + " 副本")
                layers.put(copy)
            }
            "STROKE_ADD" -> {
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") == "paint" && !layer.getBoolean("locked"))
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
                require(p.optString("tool", "pencil") in setOf("pencil", "ink", "eraser", "soft", "spray"))
                layer.getJSONArray("strokes").put(JSONObject(p.toString()))
            }
            "STROKE_ERASE" -> {
                val layer = find(p.getString("layerId")).second
                require(!layer.getBoolean("locked"))
                val strokes = layer.getJSONArray("strokes")
                val index = (0 until strokes.length()).firstOrNull { strokes.getJSONObject(it).getString("id") == p.getString("strokeId") }
                    ?: error("笔画不存在")
                strokes.remove(index)
            }
            "TRANSFORM" -> {
                val layer = find(p.getString("id")).second
                require(!layer.getBoolean("locked"))
                for (field in listOf("x", "y", "rotation", "scale")) if (p.has(field)) {
                    val value = p.getDouble(field)
                    require(value.isFinite() && (field != "scale" || value in 0.01..100.0))
                    layer.put(field, value)
                }
            }
            "SELECTION_CREATE" -> {
                for (field in listOf("x", "y", "width", "height")) require(p.getDouble(field).isFinite())
                require(p.getDouble("width") >= 0 && p.getDouble("height") >= 0)
                state.put("selection", JSONObject(p.toString()))
            }
            "SELECTION_CLEAR" -> state.put("selection", JSONObject.NULL)
            "SELECTION_EDIT" -> {
                val selection = state.optJSONObject("selection") ?: error("请先创建矩形选区")
                val layer = find(p.getString("layerId")).second
                require(layer.getString("kind") == "paint" && !layer.getBoolean("locked"))
                val strokes = layer.getJSONArray("strokes")
                val chosen = (0 until strokes.length()).filter { index ->
                    val points = strokes.getJSONObject(index).getJSONArray("points")
                    val x = (0 until points.length()).sumOf { points.getJSONArray(it).getDouble(0) } / points.length()
                    val y = (0 until points.length()).sumOf { points.getJSONArray(it).getDouble(1) } / points.length()
                    x >= selection.getDouble("x") && y >= selection.getDouble("y") &&
                        x <= selection.getDouble("x") + selection.getDouble("width") &&
                        y <= selection.getDouble("y") + selection.getDouble("height")
                }
                require(chosen.isNotEmpty()) { "选区中没有笔画" }
                val action = p.getString("action")
                val centerX = selection.getDouble("x") + selection.getDouble("width") / 2.0
                val centerY = selection.getDouble("y") + selection.getDouble("height") / 2.0
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
                                    "MOVE" -> (x + p.getDouble("dx")) to (y + p.getDouble("dy"))
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
                                    else -> (x + 20.0) to (y + 20.0)
                                }
                                require(transformed.first.isFinite() && transformed.second.isFinite())
                                point.put(0, transformed.first).put(1, transformed.second)
                            }
                            if (action == "COPY") stroke.put("id", "${p.getString("copyId")}:$ordinal")
                            else strokes.put(chosen[ordinal], stroke)
                            if (action == "COPY") strokes.put(stroke)
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
                        selection.put("x", centerX - selection.getDouble("width") / 2)
                        selection.put("y", centerY - selection.getDouble("height") / 2)
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
                    layer.put("x", layer.getDouble("x") - dx)
                    layer.put("y", layer.getDouble("y") - dy)
                }
                state.put("selection", JSONObject.NULL)
            }
            else -> error("未知画室操作：$type")
        }
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
