package com.ai.limbs.plugins.artstudio

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Page-local execution stays on Main; only opaque Provider messages cross the Runtime. */
@Composable
internal fun StudioViewConnection(
    host: InProcessPluginUiHost,
    physicalState: () -> JSONObject,
    execute: (String, JSONObject) -> JSONObject,
    restartKey: Int,
    onConnected: () -> Unit,
    onConnectionError: (String) -> Unit
) {
    val directory = remember(host) { host.providers.observe(ART_VIEW_CONTROL) }
    val binding by directory.collectAsState()
    val provider = binding?.let {
        check(it.ownerPluginId == ART_ID) { "画室视图 Provider 所有权不符" }
        requireNotNull(it.payload as? InProcessUiStateProvider) { "画室视图 Provider 协议不符" }
    }
    val readState by rememberUpdatedState(physicalState)
    val dispatch by rememberUpdatedState(execute)
    val connected by rememberUpdatedState(onConnected)
    val failed by rememberUpdatedState(onConnectionError)
    LaunchedEffect(provider, restartKey) {
        if (provider == null) return@LaunchedEffect
        val session = UUID.randomUUID().toString()
        var attachedSession = false
        suspend fun perform(event: String, payload: JSONObject): JSONObject =
            withContext(Dispatchers.IO) {
                JSONObject(provider.perform(event, payload.put("session", session).toString()))
            }
        fun applyPreferences(preferences: JSONObject) {
            for (key in ArtStudioViewChannel.optionNames)
                if (preferences.has(key)) ArtStudioViewControl.setOption(key, preferences.getBoolean(key))
            if (preferences.has("zoomToolMode"))
                ArtStudioViewControl.setZoomToolMode(preferences.getString("zoomToolMode"))
            if (preferences.has("presentationMode"))
                ArtStudioViewControl.setPresentationMode(preferences.getString("presentationMode"))
        }
        try {
            coroutineScope {
                val attached = perform("attach", JSONObject().put("state", readState()))
                attachedSession = true
                applyPreferences(attached.getJSONObject("applyPreferences"))
                perform("update", JSONObject().put("state", readState()))
                connected()
                launch {
                    provider.stateJson.collect { json ->
                        if (json == null) return@collect
                        val envelope = JSONObject(json)
                        if (envelope.optString("session") != session) return@collect
                        val preferences = envelope.getJSONObject("applyPreferences")
                        if (preferences.length() > 0) {
                            applyPreferences(preferences)
                            perform("update", JSONObject().put("state", readState()))
                        }
                        val request = envelope.optJSONObject("request") ?: return@collect
                        if (request.getBoolean("claimed")) return@collect
                        val id = request.getString("id")
                        val claim = perform("claim", JSONObject().put("id", id))
                        if (!claim.getBoolean("accepted")) return@collect
                        val receipt = JSONObject().put("id", id)
                        try {
                            check(SystemClock.elapsedRealtime() < request.getLong("expiresAt")) {
                                "视图命令已过期，未执行"
                            }
                            val physical = readState()
                            check(physical.getBoolean("pageVisible")) { "画室页面不可见，未执行" }
                            if (!request.isNull("documentId")) {
                                check(physical.getBoolean("canvasAttached") &&
                                    physical.optJSONObject("canvasZoom")?.optString("documentId") ==
                                    request.getString("documentId")) { "画布已切换，未执行旧视图命令" }
                            }
                            val result = dispatch(request.getString("operation"), request.getJSONObject("parameters"))
                            receipt.put("success", true).put("result", result)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            host.logger.e("ArtStudio", "视图命令执行失败", error)
                            receipt.put("success", false).put("error", error.message ?: error.javaClass.simpleName)
                        }
                        perform("complete", receipt.put("state", readState()))
                    }
                }
                while (isActive) {
                    delay(400)
                    perform("update", JSONObject().put("state", readState()))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            host.logger.e("ArtStudio", "画室视图连接失败", error)
            failed(error.message ?: error.javaClass.simpleName)
        } finally {
            if (attachedSession) withContext(NonCancellable) {
                try { perform("detach", JSONObject()) }
                catch (error: Exception) { host.logger.e("ArtStudio", "画室视图断开失败", error) }
            }
        }
    }
}
