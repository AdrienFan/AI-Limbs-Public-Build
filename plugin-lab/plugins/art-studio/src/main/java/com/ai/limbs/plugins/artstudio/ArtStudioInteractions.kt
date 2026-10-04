package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.util.Base64
import com.ai.limbs.plugin.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.function.Consumer

internal const val ART_INTERACTIONS = "$ART_ID.interactions"

/** Parent-owned display/session substrate; child-owned game rules stay behind opaque event channels. */
internal class ArtStudioInteractions(private val host: InProcessPluginHost) : InProcessUiStateProvider, AutoCloseable {
    private class Entry(val id: String, val token: String, val panel: InProcessUiStateProvider,
        val canvas: CanvasEndpoint, var document: JSONObject) { lateinit var observer: Job }
    private val entries = linkedMapOf<String, Entry>()
    override val stateJson = MutableStateFlow<String?>("""{"schema":1,"panels":[],"canvas":null}""")
    private var closed = false
    private var active: CanvasEndpoint? = null
    @Synchronized fun bind(binding: ChildExtensionBinding): AutoCloseable {
        check(!closed)
        val payload = binding.payload as? Map<*, *> ?: error("互动扩展必须发布业务binding")
        require(payload["schema"] == 1)
        val panel = payload["panel"] as? InProcessUiStateProvider ?: error("互动扩展缺少panel")
        @Suppress("UNCHECKED_CAST")
        val connect = payload["connect"] as? Consumer<InProcessUiStateProvider> ?: error("互动扩展缺少connect")
        require(binding.extensionId !in entries)
        val token = UUID.randomUUID().toString()
        val canvas = CanvasEndpoint(binding.extensionId, token)
        val entry = Entry(binding.extensionId, token, panel, canvas, validatePanel(requireNotNull(panel.stateJson.value)))
        connect.accept(canvas)
        entry.observer = host.scope.launch(start = CoroutineStart.LAZY) {
            panel.stateJson.collect { raw ->
                try {
                    val parsed = validatePanel(requireNotNull(raw))
                    synchronized(this@ArtStudioInteractions) {
                        if (entries[entry.id] === entry) { entry.document = parsed; publish() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    host.logger.e("ArtStudio", "Invalid interactive panel from ${entry.id}", error)
                    synchronized(this@ArtStudioInteractions) {
                        if (entries[entry.id] === entry) { entry.document.put("open", false); publish() }
                    }
                }
            }
        }
        entries[entry.id] = entry; publish(); entry.observer.start()
        return AutoCloseable {
            synchronized(this) {
                if (entries[entry.id] === entry) {
                    entries.remove(entry.id); entry.observer.cancel()
                    try { canvas.close() } finally { publish() }
                }
            }
        }
    }
    private fun validatePanel(raw: String): JSONObject {
        require(raw.length <= 8 * 1024 * 1024) { "互动面板超过大小限制" }
        val p = JSONObject(raw)
        require(p.getInt("schema") == 1 && p.getString("title").length in 1..80)
        p.getBoolean("open"); p.getString("formKey"); p.getInt("revision")
        for (key in listOf("messages", "fields", "actions")) require(p.getJSONArray(key).length() <= 32)
        val fields = p.getJSONArray("fields")
        val ids = (0 until fields.length()).map { fields.getJSONObject(it).getString("id") }
        require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[a-z][a-z0-9_]{0,31}")) })
        return p
    }
    @Synchronized private fun publish() {
        val panels = JSONArray()
        for (entry in entries.values) panels.put(JSONObject().put("extensionId", entry.id).put("binding", entry.token)
            .put("document", JSONObject(entry.document.toString())))
        stateJson.value = JSONObject().put("schema", 1).put("panels", panels)
            .put("canvas", active?.describe() ?: JSONObject.NULL).toString()
    }
    override suspend fun perform(eventId: String, payloadJson: String): String {
        require(eventId == "phone")
        val p = JSONObject(payloadJson)
        val entry = synchronized(this) {
            check(!closed)
            val e = requireNotNull(entries[p.getString("extensionId")]) { "互动扩展已停用" }
            require(e.token == p.getString("binding")) { "互动扩展已更换" }; e
        }
        host.childExtensions.recordUse(entry.id)
        return entry.panel.perform(p.getString("event"), p.getJSONObject("parameters").toString())
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        entries.values.forEach { it.observer.cancel(); it.canvas.close() }
        entries.clear(); publish()
    }
    private inner class CanvasEndpoint(val owner: String, val token: String) : InProcessUiStateProvider, AutoCloseable {
        override val stateJson = MutableStateFlow<String?>("{}")
        private val mutex = Mutex()
        private var root: File? = null
        private var store: ArtStore? = null
        private var drawer = ""
        private var frozen = true
        @Volatile private var revoked = false
        fun describe(): JSONObject = JSONObject().put("root", requireNotNull(root).absolutePath)
            .put("token", token).put("owner", owner).put("drawer", drawer).put("frozen", frozen)
        override suspend fun perform(eventId: String, payloadJson: String): String = mutex.withLock {
            withContext(Dispatchers.IO) {
                check(!revoked) { "临时画布通道已撤销" }
                val p = JSONObject(payloadJson)
                when (eventId) {
                    "create" -> {
                        val actor = p.getString("drawer"); require(actor in setOf("AWEI", "LANER"))
                        synchronized(this@ArtStudioInteractions) {
                            check(!revoked) { "临时画布通道已撤销" }
                            check(active == null) { "已有互动扩展占用画布" }
                            val directory = File(host.cacheDir, "interactive-canvases/${UUID.randomUUID()}")
                            check(directory.mkdirs()); File(directory, ".session-active").writeText(token)
                            try {
                                val canvas = ArtStore(directory, ephemeral = true)
                                canvas.create(1000, 700, name = "互动临时画布", actor = actor)
                                root = directory; store = canvas; drawer = actor; frozen = false
                                active = this@CanvasEndpoint; publish()
                                JSONObject().put("ready", true).toString()
                            } catch (error: Throwable) { directory.deleteRecursively(); throw error }
                        }
                    }
                    "release" -> { release(); "{}" }
                    "snapshot" -> requireNotNull(store) { "临时画布未创建" }.current().toString()
                    "apply" -> {
                        check(!frozen); require(drawer == "LANER") { "只有兰儿绘画回合接受绘画入口" }
                        val canvas = requireNotNull(store)
                        val type = p.getString("type")
                        require(type in setOf("STROKE_ADD", "SHAPE_CREATE", "SHAPE_DELETE", "LAYER_CREATE", "LAYER_SELECT"))
                        val result = canvas.withViewSnapshot { current ->
                            val snapshot = requireNotNull(current)
                            val parameters = JSONObject(p.getJSONObject("params").toString())
                                .put("documentId", snapshot.getString("id")).put("expectedRevision", snapshot.getInt("revision"))
                            if (!parameters.has("layerId")) parameters.put("layerId", snapshot.getJSONObject("state").getString("selectedLayerId"))
                            canvas.apply("LANER", type, parameters)
                        }
                        result.toString()
                    }
                    "freeze", "preview" -> {
                        val canvas = requireNotNull(store)
                        val source = if (eventId == "freeze") canvas.freezeEphemeral() else canvas.captureCurrentViewSource()
                        val bitmap = source.use { captured -> canvas.withRenderAssets(captured.assets) {
                            ArtRenderer.render(canvas, requireNotNull(captured.snapshot), maxEdge = 1000)
                        } }
                        try {
                            val bytes = ByteArrayOutputStream()
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                            val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                            if (eventId == "freeze") synchronized(this@ArtStudioInteractions) {
                                check(!revoked && active === this@CanvasEndpoint)
                                File(requireNotNull(root), "final.png").writeBytes(bytes.toByteArray())
                                frozen = true; publish()
                            }
                            JSONObject().put("base64", encoded).put("width", bitmap.width).put("height", bitmap.height)
                                .put("mcp_content", JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", encoded))).toString()
                        } finally { bitmap.recycle() }
                    }
                    else -> error("未知临时画布操作：$eventId")
                }
            }
        }
        private fun release() = synchronized(this@ArtStudioInteractions) {
            store?.revokeEphemeral()
            root?.let { check(it.deleteRecursively()) { "临时画布清理失败" } }
            store = null; root = null; frozen = true
            if (active === this) active = null
            publish()
        }
        override fun close() { revoked = true; release() }
    }
}
