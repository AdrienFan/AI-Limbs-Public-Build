package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable internal fun StudioInteractiveSurface(host: InProcessPluginUiHost, bridge: StudioMenuBridge,
    regularFrames: StudioFrameCache<StudioRenderFrame>, sharedUi: InProcessSharedUiHost,
    studio: @Composable (StudioFrameCache<StudioRenderFrame>, File, Boolean, Boolean) -> Unit) {
    var state by remember { mutableStateOf(JSONObject("""{"panels":[],"canvas":null}""")) }
    val context = LocalContext.current
    LaunchedEffect(host) {
        try {
            host.providers.observe(ART_INTERACTIONS).collectLatest { binding ->
                state = JSONObject("""{"panels":[],"canvas":null}""")
                if (binding == null) return@collectLatest
                require(binding.ownerPluginId == ART_ID)
                val provider = binding.payload as? InProcessUiStateProvider ?: error("互动目录类型错误")
                provider.stateJson.collect { raw -> state = JSONObject(requireNotNull(raw)) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            host.logger.e("ArtStudio", "Interactive directory failed", error)
            Toast.makeText(context, "互动扩展读取失败：${error.message}", Toast.LENGTH_LONG).show()
        }
    }
    StudioExtensionsUi(host, sharedUi, bridge)
    val panels = state.getJSONArray("panels")
    val open = (0 until panels.length()).map { panels.getJSONObject(it) }
        .filter { it.getJSONObject("document").getBoolean("open") }
    val canvas = state.optJSONObject("canvas")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (canvas == null) key("ordinary") {
            studio(regularFrames, host.dataDir, false, open.isEmpty())
        } else key(canvas.getString("root")) {
            val frames = remember { StudioFrameCache<StudioRenderFrame> { it.close() } }
            DisposableEffect(frames) { onDispose { frames.close() } }
            studio(frames, File(canvas.getString("root")), true,
                canvas.getString("drawer") == "AWEI" && !canvas.getBoolean("frozen"))
        }
        for (entry in open) key(entry.getString("extensionId"), entry.getString("binding")) {
            val imageFile = if (entry.getJSONObject("document").optBoolean("image")) {
                require(canvas != null && canvas.getString("owner") == entry.getString("extensionId"))
                File(canvas.getString("root"), "final.png")
            } else null
            StudioInteractiveWindow(host, bridge, entry, imageFile)
        }
    }
}

@Composable private fun BoxWithConstraintsScope.StudioInteractiveWindow(host: InProcessPluginUiHost,
    bridge: StudioMenuBridge, entry: JSONObject, imageFile: File?) {
    var window by remember { mutableStateOf(StudioToolWindowState(open = true, yDp = 64f)) }
    val panel = entry.getJSONObject("document")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val currentPanel by rememberUpdatedState(panel)
    val currentEntry by rememberUpdatedState(entry)
    fun send(event: String, parameters: JSONObject) {
        if (submitting || (event == "finish" && bridge.canvasWorking)) return
        submitting = true; error = null
        scope.launch {
            try {
                val binding = requireNotNull(host.providers.resolve(ART_INTERACTIONS)) { "互动目录未就绪" }
                require(binding.ownerPluginId == ART_ID)
                val provider = binding.payload as? InProcessUiStateProvider ?: error("互动目录类型错误")
                val captured = currentEntry
                val p = JSONObject(parameters.toString()).put("revision", currentPanel.getInt("revision"))
                withContext(Dispatchers.IO) {
                    provider.perform("phone", JSONObject().put("extensionId", captured.getString("extensionId"))
                        .put("binding", captured.getString("binding")).put("event", event).put("parameters", p).toString())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                host.logger.e("ArtStudio", "Interactive action failed: $event", failure)
                error = failure.message
            } finally { submitting = false }
        }
    }
    StudioToolOptionsWindow(window, panel.getString("title"),
        { x, y -> window = window.copy(xDp = x, yDp = y) },
        { window = window.copy(minimized = true) }, { window = window.copy(minimized = false) },
        { confirmExit = true }) {
        val messages = panel.getJSONArray("messages")
        for (index in 0 until messages.length()) Text(messages.getString(index))
        if (imageFile != null) StudioInteractiveImage(imageFile)
        key(panel.getString("formKey")) {
            val fields = panel.getJSONArray("fields")
            val values = remember { mutableStateMapOf<String, String>() }
            for (index in 0 until fields.length()) {
                val field = fields.getJSONObject(index); val id = field.getString("id")
                OutlinedTextField(value = values[id] ?: "", onValueChange = { values[id] = it },
                    label = { Text(field.getString("label")) }, enabled = !submitting,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            val actions = panel.getJSONArray("actions")
            for (index in 0 until actions.length()) {
                val action = actions.getJSONObject(index)
                val event = action.getString("event")
                val circular = action.getString("style") == "circle"
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        val parameters = JSONObject(action.getJSONObject("parameters").toString())
                        values.forEach { (key, value) -> parameters.put(key, value) }
                        send(event, parameters)
                    }, enabled = !submitting && (!action.has("enabled") || action.getBoolean("enabled")) &&
                        (event != "finish" || !bridge.canvasWorking),
                        shape = if (circular) CircleShape else MaterialTheme.shapes.small,
                        modifier = if (circular) Modifier.size(116.dp) else Modifier.fillMaxWidth()) {
                        Text(action.getString("title"), style = if (circular) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
        title = { Text("退出${panel.getString("title")}") }, text = { Text("本轮游戏画布和封存题目会清理。") },
        confirmButton = { TextButton(onClick = { confirmExit = false; send("exit", JSONObject()) }, enabled = !submitting) { Text("退出游戏") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续游戏") } })
}

@Composable private fun StudioInteractiveImage(file: File) {
    var image by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.absolutePath) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { decoded = BitmapFactory.decodeFile(file.absolutePath) }
            image = requireNotNull(decoded) { "游戏图片读取失败" }; decoded = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message }
        finally { decoded?.recycle() }
    }
    DisposableEffect(file.absolutePath) { onDispose { image?.recycle(); image = null } }
    image?.let { Image(it.asImageBitmap(), contentDescription = "本轮待猜图片", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
