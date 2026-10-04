package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Small previews use the ordinary compositor, with the chosen layer as an isolated root. */
internal object ArtLayerPreview {
    fun snapshot(documentId: String, state: JSONObject, chosen: JSONObject): JSONObject {
        val rows = ArtAnimation.layers(state)
        val keep = linkedSetOf(chosen.getString("id"))
        repeat(rows.size) {
            rows.filter { it.optString("parentId") in keep }.forEach { keep.add(it.getString("id")) }
        }
        val layers = JSONArray()
        for (layer in rows) if (layer.getString("id") in keep) {
            val projected = JSONObject()
            // Resolved current-cel contents are immutable; inactive cels never belong in a thumbnail.
            layer.keys().forEach { field ->
                if (field != "animationKeys") projected.put(field, layer.get(field))
            }
            if (layer.getString("id") == chosen.getString("id"))
                projected.put("parentId", "").put("visible", true)
            layers.put(projected)
        }
        return JSONObject().put("id", documentId + ":" + chosen.getString("id"))
            .put("revision", 0).put("state", JSONObject()
                .put("width", state.getInt("width")).put("height", state.getInt("height"))
                .put("background", "#00000000").put("layers", layers))
    }
}

/** One pending renderer per page; at most 16 completed 128px images stay cached. */
internal class StudioLayerPreviews : AutoCloseable {
    private val worker = Mutex()
    private val entries = LinkedHashMap<String, StudioFrameCache<Bitmap>>(16, 0.75f, true)
    private var closed = false

    @Synchronized private fun lookup(key: String): StudioFrameCache.Lease<Bitmap>? {
        check(!closed) { "图层预览已关闭" }
        return entries[key]?.acquire()
    }
    @Synchronized private fun publish(key: String, bitmap: Bitmap): StudioFrameCache.Lease<Bitmap> {
        check(!closed) { "图层预览已关闭" }
        val entry = StudioFrameCache<Bitmap> { it.recycle() }
        val lease = requireNotNull(entry.replace(bitmap))
        entries.put(key, entry)?.close()
        while (entries.size > 16) {
            val oldest = entries.keys.first()
            entries.remove(oldest)!!.close()
        }
        return lease
    }

    suspend fun acquire(store: ArtStore, snapshot: JSONObject): StudioFrameCache.Lease<Bitmap> {
        val key = withContext(Dispatchers.Default) { ArtEditorPixels.key(snapshot) }
        return worker.withLock {
            lookup(key)?.let { return@withLock it }
            currentCoroutineContext().ensureActive()
            var pending: Bitmap? = null
            val started = System.nanoTime()
            var renderThread = ""
            try {
                withContext(Dispatchers.IO) {
                    renderThread = Thread.currentThread().name
                    store.capturePreviewSource(snapshot).use { source ->
                        store.withRenderAssets(source.assets) {
                            pending = ArtRenderer.render(store, snapshot, maxEdge = 128)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val lease = publish(key, requireNotNull(pending))
                pending = null
                lease
            } finally {
                pending?.recycle()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (elapsed >= 1000) android.util.Log.w("ArtStudioPerf",
                    "phase=layerPreview renderMs=$elapsed thread=$renderThread")
            }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        entries.values.forEach { it.close() }
        entries.clear()
    }
}
