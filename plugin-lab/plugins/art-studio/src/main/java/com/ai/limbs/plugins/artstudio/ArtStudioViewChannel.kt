package com.ai.limbs.plugins.artstudio

import android.os.SystemClock
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

internal const val ART_VIEW_CONTROL = "$ART_ID.view.control"

/**
 * Core owns the channel; the real page publishes observations and executes requests.
 * StateFlow/perform is the existing Runtime Provider protocol, including Resident transport.
 * Process-local Compose state is never used as evidence that a remote canvas is attached.
 */
internal class ArtStudioViewChannel(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : InProcessUiStateProvider {
    private data class Request(
        val id: String, val session: String, val operation: String, val parameters: JSONObject,
        val documentId: String?, val expiresAt: Long,
        val result: CompletableDeferred<JSONObject> = CompletableDeferred(),
        var claimed: Boolean = false
    )
    private val gate = Any()
    private val commandMutex = Mutex()
    private val events = MutableStateFlow<String?>(null)
    override val stateJson: StateFlow<String?> = events
    private var session: String? = null
    private var heartbeat = 0L
    private var snapshot = StudioViewSettings().describe()
        .put("toolOptionsWindow", StudioToolWindowState().describe().put("visible", false))
        .put("canvasAttached", false).put("pageVisible", false)
    private val deferredPreferences = JSONObject()
    private var pending: Request? = null
    private var closed = false

    init { synchronized(gate) { publish() } }

    private fun livePage() = !closed && session != null &&
        clock() - heartbeat in 0..LEASE_MS && snapshot.optBoolean("pageVisible")
    private fun liveCanvas() = livePage() && snapshot.optBoolean("canvasAttached") &&
        snapshot.optJSONObject("canvasZoom") != null

    fun describe(): JSONObject = synchronized(gate) {
        val result = JSONObject(snapshot.toString())
        val attached = liveCanvas()
        result.put("canvasAttached", attached).put("pageVisible", livePage())
        if (!attached) result.remove("canvasZoom")
        result.getJSONObject("toolOptionsWindow").put("visible",
            attached && result.getJSONObject("toolOptionsWindow").getBoolean("open"))
        result
    }

    private fun publish() {
        val request = pending
        events.value = JSONObject().put("schema", 1).put("session", session ?: JSONObject.NULL)
            .put("applyPreferences", JSONObject(deferredPreferences.toString()))
            .put("request", if (request == null) JSONObject.NULL else JSONObject()
                .put("id", request.id).put("session", request.session)
                .put("operation", request.operation).put("parameters", request.parameters)
                .put("documentId", request.documentId ?: JSONObject.NULL)
                .put("expiresAt", request.expiresAt).put("claimed", request.claimed)).toString()
    }

    private fun failPending(message: String) {
        pending?.result?.completeExceptionally(IllegalStateException(message))
        pending = null
    }

    private fun validate(operation: String, p: JSONObject) {
        when (operation) {
            "command" -> require(p.getString("command") in ArtStudioViewControl.commandNames)
            "zoom" -> {
                require(p.getString("documentId").isNotBlank())
                require(p.getDouble("percent").isFinite())
            }
            "set" -> {
                require(p.getString("option") in optionNames)
                p.getBoolean("enabled")
            }
            "tool_select" -> require(ArtToolCatalog.implemented.any { it.first == p.getString("toolId") })
            "zoom_tool" -> require(p.getString("mode") in setOf("in", "out", "toggle"))
            "presentation" -> require(p.getString("mode") in presentationNames)
            "tool_options" -> {
                require(p.getString("action") in setOf("show", "minimize", "restore", "close", "move"))
                if (p.getString("action") == "show") {
                    val id = p.getString("toolId")
                    require(ArtToolCatalog.implemented.any { it.first == id } ||
                        ArtToolCatalog.pending.any { it.id == id }) { "未知工具：$id" }
                }
                if (p.getString("action") == "move") for (key in listOf("xDp", "yDp"))
                    require(p.getDouble(key).isFinite() && p.getDouble(key) in 0.0..1_000_000.0)
            }
            else -> error("未知视图操作：$operation")
        }
    }

    /** Preferences remain usable before opening a page, as in the previous public API. */
    private fun deferPreference(operation: String, p: JSONObject): JSONObject {
        val key: String
        val value: Any
        when (operation) {
            "set" -> { key = p.getString("option"); value = p.getBoolean("enabled") }
            "zoom_tool" -> {
                key = "zoomToolMode"
                value = if (p.getString("mode") == "toggle") {
                    if (snapshot.getString(key) == "in") "out" else "in"
                } else p.getString("mode")
            }
            "presentation" -> { key = "presentationMode"; value = p.getString("mode") }
            else -> error("此视图操作需要打开画布")
        }
        snapshot.put(key, value)
        if (key == "zoomToolMode") snapshot.put("zoomToolBadge", if (value == "in") "大" else "小")
        deferredPreferences.put(key, value)
        publish()
        return describe().put("accepted", true).put("deferredUntilPageOpens", true)
    }

    suspend fun execute(operation: String, parameters: JSONObject): JSONObject = commandMutex.withLock {
        validate(operation, parameters)
        var deferred: JSONObject? = null
        val request = synchronized(gate) {
            check(!closed) { "画室视图连接已关闭" }
            if (operation in setOf("set", "zoom_tool", "presentation") && !livePage()) {
                deferred = deferPreference(operation, parameters)
                null
            } else {
                val needsCanvas = operation in setOf("zoom", "command", "tool_options", "tool_select")
                check(if (needsCanvas) liveCanvas() else livePage()) { "请先打开画室画布，再操作视图" }
                val zoom = snapshot.optJSONObject("canvasZoom")
                val documentId = if (needsCanvas) requireNotNull(zoom).getString("documentId") else null
                if (operation == "zoom") {
                    require(parameters.getString("documentId") == documentId) { "画布已切换，请重新读取 view.state" }
                    val range = requireNotNull(zoom)
                    require(parameters.getDouble("percent") in range.getDouble("minPercent")..range.getDouble("maxPercent")) {
                        "缩放比例超出当前画布范围，请读取 view.state"
                    }
                }
                check(pending == null)
                Request(UUID.randomUUID().toString(), requireNotNull(session), operation,
                    JSONObject(parameters.toString()), documentId, clock() + TIMEOUT_MS).also {
                    pending = it
                    publish()
                }
            }
        }
        if (request == null) return@withLock requireNotNull(deferred)
        try {
            withTimeout(TIMEOUT_MS) { request.result.await() }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("画室页面未在期限内返回视图执行回执", error)
        } finally {
            synchronized(gate) {
                if (pending === request) {
                    pending = null
                    publish()
                }
            }
        }
    }

    override suspend fun perform(eventId: String, payloadJson: String): String = synchronized(gate) {
        check(!closed) { "画室视图连接已关闭" }
        require(payloadJson.length <= 16384)
        val p = JSONObject(payloadJson)
        val token = p.getString("session")
        require(token.length in 1..128)
        val result = when (eventId) {
            "attach" -> {
                // A new page generation invalidates every request targeting the previous page.
                failPending("画室页面已更换，请重新操作")
                session = token
                heartbeat = clock()
                snapshot = JSONObject(p.getJSONObject("state").toString())
                val preferences = JSONObject(deferredPreferences.toString())
                publish()
                JSONObject().put("accepted", true).put("applyPreferences", preferences)
            }
            "update" -> {
                check(token == session) { "画室页面会话已失效" }
                heartbeat = clock()
                snapshot = JSONObject(p.getJSONObject("state").toString())
                val applied = deferredPreferences.keys().asSequence().toList().filter { key ->
                    snapshot.opt(key) == deferredPreferences.get(key)
                }
                for (key in applied) deferredPreferences.remove(key)
                if (applied.isNotEmpty()) publish()
                if (pending != null && !livePage()) {
                    failPending("画室页面已关闭或不可见")
                    publish()
                }
                JSONObject().put("accepted", true)
            }
            "claim" -> {
                val request = pending
                val matches = token == session && request != null && request.session == token &&
                    request.id == p.getString("id") && !request.claimed
                if (!matches) JSONObject().put("accepted", false) else {
                    val target = requireNotNull(request)
                    val documentMatches = target.documentId == null ||
                        snapshot.optJSONObject("canvasZoom")?.optString("documentId") == target.documentId
                    val valid = livePage() && clock() < target.expiresAt && documentMatches
                    if (!valid) {
                        failPending("画布或页面已变化，视图命令未执行")
                    } else target.claimed = true
                    publish()
                    JSONObject().put("accepted", valid)
                }
            }
            "complete" -> {
                val request = pending
                if (token != session || request == null || request.id != p.getString("id") || !request.claimed)
                    JSONObject().put("accepted", false)
                else {
                    snapshot = JSONObject(p.getJSONObject("state").toString())
                    heartbeat = clock()
                    pending = null
                    if (clock() >= request.expiresAt)
                        request.result.completeExceptionally(IllegalStateException("视图命令回执已过期"))
                    else if (p.getBoolean("success")) request.result.complete(p.getJSONObject("result"))
                    else request.result.completeExceptionally(IllegalStateException(p.getString("error")))
                    publish()
                    JSONObject().put("accepted", true)
                }
            }
            "detach" -> {
                val matches = token == session
                if (matches) {
                    failPending("画室页面已关闭")
                    session = null
                    publish()
                }
                JSONObject().put("accepted", matches)
            }
            else -> error("未知页面连接事件：$eventId")
        }
        result.toString()
    }

    fun close() = synchronized(gate) {
        closed = true
        session = null
        failPending("画室插件已停止")
        publish()
    }

    companion object {
        const val LEASE_MS = 5000L
        const val TIMEOUT_MS = 5000L
        val optionNames = setOf("panelsHidden", "statusBarVisible", "gridVisible", "pixelGridVisible")
        val presentationNames = setOf("normal", "fullscreen_portrait", "fullscreen_landscape")
    }
}
