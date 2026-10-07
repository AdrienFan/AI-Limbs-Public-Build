package com.ai.limbs.plugins.visualmanager

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.flow.collectLatest
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class VisualManagerPageProvider(
    private val host: InProcessPluginUiHost,
    private val actions: VisualManagerPageActions
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { VisualWorkbench(actions, host.providers) } }
        }
}

@Composable
private fun VisualWorkbench(actions: VisualManagerPageActions, providers: InProcessProviderDirectory) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var dashboard by remember { mutableStateOf<JSONObject?>(null) }
    var tab by remember { mutableStateOf(0) }
    var selectedScreen by remember { mutableStateOf<String?>(null) }
    var selectedCamera by remember { mutableStateOf<String?>(null) }
    var previews by remember { mutableStateOf<Map<String, VisualPreview>>(emptyMap()) }
    var notice by remember { mutableStateOf("正在读取工作台状态…") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var pageText by remember { mutableStateOf("") }
    var shownRecord by remember { mutableStateOf<VisualPreview?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            visible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    suspend fun invoke(name: String, p: JSONObject = JSONObject()) =
        VisualOperationResult.requireSuccess(actions.call(name, p))

    suspend fun reload() {
        val state = invoke("status")
        dashboard = state
        val targets = objects(state.optJSONObject("screen_targets")?.optJSONArray("targets"))
            .filter { it.optBoolean("capture_supported", false) }
        val sources = objects(state.optJSONObject("camera_sources")?.optJSONArray("sources"))
        if (selectedScreen == null && targets.isNotEmpty()) selectedScreen = targets.first().getString("target_id")
        if (selectedCamera == null && sources.isNotEmpty()) selectedCamera = sources.first().getString("source_id")
        val availablePreviews = state.getJSONObject("previews")
        for (kind in listOf("screen", "camera")) {
            val summary = availablePreviews.optJSONObject(kind)
            if (summary != null && summary.getLong("captured_at_ms") != previews[kind]?.capturedAtMs) {
                val result = invoke("preview.read", JSONObject().put("kind", kind))
                val image = VisualPreview.fromResponse(result, result)
                check(image.kind == kind) { "预览来源与请求不一致" }
                previews = previews + (kind to image)
            }
        }
    }

    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && visible) scope.launch {
                try { reload() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "状态读取失败：${e.message}" }
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }

    fun act(label: String, block: suspend () -> JSONObject) {
        if (working || stopping) return
        operation = scope.launch {
            working = true
            error = null
            notice = label
            try {
                val result = block()
                result.optJSONObject("preview")?.let { metadata ->
                    val image = VisualPreview.fromResponse(result, metadata)
                    previews = previews + (image.kind to image)
                }
                notice = result.optString("message", "$label 完成")
                try { reload() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "操作已完成，但状态同步失败：${e.message}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "操作未完成"
                notice = "操作未完成"
            } finally { working = false }
        }
    }

    fun stopAll() {
        if (stopping) return
        operation?.cancel()
        scope.launch {
            stopping = true
            error = null
            try {
                invoke("stop", JSONObject().put("kind", "all"))
                notice = "屏幕和摄像头均已停止"
                reload()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message; notice = "停止未完成，请查看异常" }
            finally { stopping = false }
        }
    }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        // Core publishes changes through the existing generic state provider.
        // Reading status never changes the signal, so this is event-driven, without a retry timer.
        try {
            reload()
            if (notice == "正在读取工作台状态…") notice = "状态已同步"
            providers.observe(VISUAL_STATE_ID).collectLatest { binding ->
                if (binding != null) {
                    val provider = checkNotNull(binding.payload as? InProcessUiStateProvider) { "视觉状态通道类型错误" }
                    provider.stateJson.collectLatest {
                        try { reload() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = "状态读取失败：${e.message}" }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "状态读取失败：${e.message}" }
    }

    val latestSnapshotId = dashboard?.optJSONObject("page")?.optJSONObject("latest_snapshot")?.optString("snapshot_id")
    LaunchedEffect(latestSnapshotId) { pageText = "" }

    val kind = if (tab == 0) "screen" else "camera"
    val state = dashboard?.optJSONObject(kind)
    val sessions = objects(state?.optJSONArray("sessions"))
    val session = sessions.firstOrNull()
    val sessionId = session?.getString("session_id")
    val active = session != null
    val available = state != null && state.optBoolean("available", true)
    val sharedOperation = dashboard?.optJSONObject("operations")?.optJSONObject(kind)
    val busy = working || stopping || sharedOperation?.optString("phase") in listOf("BUSY", "STOPPING")
    val permission = dashboard?.optJSONObject("camera_permission")
    val authorized = permission?.optBoolean("permission_granted", false) == true

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("视觉工作台", style = MaterialTheme.typography.headlineSmall)
                Text("屏幕 ${sessionLabel(dashboard?.optJSONObject("screen"))}  ·  相机 ${sessionLabel(dashboard?.optJSONObject("camera"))}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = !stopping, onClick = { stopAll() }) {
                Text(if (stopping) "正在停止…" else "全部停止")
            }
        }
        if (working || stopping) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        error?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { error = null }) { Text("收起") }
                }
            }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            listOf("屏幕", "摄像头", "页面文字", "图像记录").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0, 1 -> {
                    val sources = if (kind == "screen") objects(dashboard?.optJSONObject("screen_targets")?.optJSONArray("targets"))
                        .filter { it.optBoolean("capture_supported", false) }
                    else objects(dashboard?.optJSONObject("camera_sources")?.optJSONArray("sources"))
                    val selected = if (kind == "screen") selectedScreen else selectedCamera
                    AdaptiveWorkspace(
                        hasImage = previews[kind] != null,
                        controls = {
                            Text(if (kind == "screen") "屏幕共享" else "摄像头画面", style = MaterialTheme.typography.titleLarge)
                            Text("1 · 选择来源", style = MaterialTheme.typography.titleSmall)
                            sources.forEach { source ->
                                val id = source.getString(if (kind == "screen") "target_id" else "source_id")
                                val label = if (kind == "screen") source.getString("name") else
                                    "${when (source.getString("lens_facing")) { "front" -> "前置"; "back" -> "后置"; else -> "外接" }}镜头 $id"
                                FilterChip(selected = selected == id, enabled = !busy && !active,
                                    onClick = { if (kind == "screen") selectedScreen = id else selectedCamera = id },
                                    label = { Text(label) })
                            }
                            if (sources.isEmpty()) Text("暂无可用来源，请检查设备与宿主状态。")
                            state?.optString("error")?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                            if (kind == "camera") {
                                Text("2 · 相机权限", style = MaterialTheme.typography.titleSmall)
                                Text(if (authorized) "相机已授权" else "相机尚未授权")
                                permission?.optString("error")?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                                if (!authorized) {
                                    Button(enabled = !busy && permission?.optBoolean("available", true) != false && permission?.optBoolean("can_request_again", true) != false,
                                        onClick = {
                                            act("申请相机权限") { invoke("permission", JSONObject().put("operation", "request")) }
                                        }) { Text("授权相机") }
                                    OutlinedButton(enabled = !busy, onClick = {
                                        act("打开权限设置") { invoke("permission", JSONObject().put("operation", "open_settings")) }
                                    }) { Text("打开系统权限设置") }
                                }
                            } else Text("开始共享时由系统申请屏幕授权，请选择共享范围。", style = MaterialTheme.typography.bodySmall)
                            Text(if (active) "会话正在运行" else "3 · 开始获取画面", style = MaterialTheme.typography.titleSmall)
                            if (!active) {
                                Button(enabled = !busy && available && selected != null && (kind == "screen" || authorized), onClick = {
                                    act("开始获取画面") {
                                        invoke("start", JSONObject().put("kind", kind).put("source_id", selected))
                                    }
                                }) { Text(if (kind == "screen") "开始共享" else "开启摄像头") }
                                OutlinedButton(enabled = !busy && available && selected != null && (kind == "screen" || authorized), onClick = {
                                    act("拍摄并保存") { invoke("capture", JSONObject().put("kind", kind).put("source_id", selected)) }
                                }) { Text(if (kind == "screen") "截图并保存" else "拍一张并保存") }
                            } else {
                                Text("来源：${session!!.optString(if (kind == "screen") "target_id" else "source_id")}\n开始：${time(session.optLong("started_at_ms"))}",
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(enabled = !busy, onClick = {
                                    act("获取新画面") { invoke("frame", JSONObject().put("kind", kind).put("session_id", sessionId)) }
                                }) { Text("获取一帧") }
                                OutlinedButton(enabled = !busy, onClick = {
                                    act("保存原图") { invoke("frame", JSONObject().put("kind", kind).put("session_id", sessionId).put("save", true)) }
                                }) { Text("获取并保存原图") }
                                Button(enabled = !stopping, onClick = {
                                                                operation?.cancel()
                                    scope.launch {
                                        stopping = true
                                        try {
                                            invoke("stop", JSONObject().put("kind", kind))
                                            notice = "此会话已停止"
                                            reload()
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { error = e.message }
                                        finally { stopping = false }
                                    }
                                }) { Text("停止并释放") }
                            }
                            sharedOperation?.optString("message")?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("兰儿按需要即时获取画面，也可以在这里手动取图。离开页面不停止会话，使用“停止”释放设备。",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !working && !stopping, onClick = {
                                act("同步状态") { reload(); JSONObject().put("success", true) }
                            }) { Text("同步状态") }
                        },
                        preview = {
                            PreviewPanel(previews[kind], if (active) "最新获取画面" else "最后获取画面 · 会话未开启")
                            if (previews[kind] != null) {
                                OutlinedButton(enabled = !busy, onClick = {
                                    act("保存当前预览") { invoke("preview.save", JSONObject().put("kind", kind)) }
                                }) { Text("保存当前预览") }
                            }
                        })
                }
                2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("页面文字", style = MaterialTheme.typography.titleLarge)
                    Text("兰儿可即时读取目标应用暴露的完整文字。这里查看同一份最近快照，文字可选择和复制。",
                        style = MaterialTheme.typography.bodyMedium)
                    dashboard?.optJSONObject("operations")?.optJSONObject("page")?.optString("message")
                        ?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    val latest = dashboard?.optJSONObject("page")?.optJSONObject("latest_snapshot")
                    latest?.let {
                        Text("最近来源：${it.optString("package_name")}\n读取时间：${time(it.getLong("created_at_ms"))}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(enabled = !working && latest != null, onClick = {
                        act("加载页面全文") {
                            val id = latest!!.getString("snapshot_id")
                            val text = StringBuilder()
                            var offset = 0
                            do {
                                val result = invoke("page.text", JSONObject().put("snapshot_id", id).put("offset", offset))
                                text.append(result.getString("text"))
                                val more = result.getBoolean("has_more")
                                if (more) {
                                    val next = result.getInt("next_offset")
                                    check(next > offset) { "页面续读没有前进" }
                                    offset = next
                                }
                            } while (more)
                            pageText = text.toString()
                            JSONObject().put("success", true)
                        }
                    }) { Text("查看最近页面全文") }
                    if (pageText.isNotEmpty()) SelectionContainer { Text(pageText) }
                }
                3 -> {
                    val records = dashboard?.getJSONObject("assets")
                    val entries = objects(records?.optJSONArray("items"))
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("图像记录", style = MaterialTheme.typography.titleLarge)
                            Text("${records?.optInt("count", 0) ?: 0} 张 · ${bytes(records?.optLong("bytes", 0) ?: 0)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("仅保留明确保存的图像。最多 60 张、64 MiB，超出后删除最旧记录；预览不会自动存入这里。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (entries.isEmpty()) item { Text("还没有保存图像。") }
                        items(entries, key = { it.getString("asset_id") }) { meta ->
                            ImageRecord(actions, meta, !working && !stopping,
                                onView = { act("查看图像") {
                                    val result = invoke("images.read", JSONObject().put("asset_id", meta.getString("asset_id")))
                                    shownRecord = VisualPreview.fromResponse(result, result)
                                    result
                                } },
                                onDelete = { act("删除图像") {
                                    invoke("images.delete", JSONObject().put("asset_id", meta.getString("asset_id")))
                                } })
                        }
                        item {
                            OutlinedButton(enabled = !working && entries.isNotEmpty(), onClick = { confirmClear = true }) {
                                Text("清空图像记录")
                            }
                        }
                    }
                }
            }
        }
    }

    if (shownRecord != null) AlertDialog(
        onDismissRequest = { shownRecord = null }, title = { Text("图像记录") },
        text = { PreviewPanel(shownRecord, "已保存图像") },
        confirmButton = { TextButton(onClick = { shownRecord = null }) { Text("关闭") } })
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("清空图像记录？") },
        text = { Text("将删除本插件保存的全部图像。当前预览和视觉会话不受影响。") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            act("清空图像记录") { invoke("images.clear") }
        }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun AdaptiveWorkspace(hasImage: Boolean, controls: @Composable () -> Unit, preview: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) { controls() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) { preview() }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasImage) {
                preview()
                HorizontalDivider()
                controls()
            } else {
                controls()
                HorizontalDivider()
                preview()
            }
        }
    }
}

@Composable
private fun PreviewPanel(image: VisualPreview?, label: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (image == null) {
                Box(Modifier.fillMaxWidth().height(240.dp).background(Color(0xFF11151C))) {
                    Text("尚未获取画面\n授权并开始后，画面会显示在这里。", Modifier.padding(24.dp))
                }
            } else {
                EncodedImage(image.data, "视觉画面", Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp))
                Text("获取于 ${time(image.capturedAtMs)} · ${image.width}×${image.height}",
                    style = MaterialTheme.typography.bodySmall)
                Text("这是一帧画面，获取时间才代表新鲜度。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EncodedImage(data: String, description: String, modifier: Modifier) {
    val state by produceState<Pair<ImageBitmap?, String?>>(null to null, data) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                val decoded = Base64.decode(data, Base64.NO_WRAP)
                checkNotNull(BitmapFactory.decodeByteArray(decoded, 0, decoded.size)) { "图像解码失败" }.asImageBitmap()
            }
            value = bitmap to null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = null to e.message }
    }
    val bitmap = state.first
    if (bitmap == null) Box(modifier) {
        Text(state.second ?: "正在加载画面…", Modifier.padding(12.dp))
    } else Image(bitmap, description, modifier, contentScale = ContentScale.Fit)
}

@Composable
private fun ImageRecord(actions: VisualManagerPageActions, meta: JSONObject, enabled: Boolean,
                        onView: () -> Unit, onDelete: () -> Unit) {
    var image by remember { mutableStateOf<VisualPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(meta.getString("asset_id")) {
        try {
            val result = VisualOperationResult.requireSuccess(actions.call("images.read",
                JSONObject().put("asset_id", meta.getString("asset_id")).put("max_edge", 240)))
            image = VisualPreview.fromResponse(result, result)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "缩略图读取失败：${e.message}" }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            image?.let { EncodedImage(it.data, "图像缩略图", Modifier.size(80.dp)) }
            Column(Modifier.weight(1f)) {
                Text(if (meta.getString("kind") == "camera") "摄像头图像" else "屏幕图像")
                Text(time(meta.getLong("captured_at_ms")), style = MaterialTheme.typography.bodySmall)
                Text(bytes(meta.getLong("bytes")), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(enabled = enabled, onClick = onView) { Text("查看") }
                    TextButton(enabled = enabled, onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

private fun objects(array: JSONArray?): List<JSONObject> =
    if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }

private fun time(ms: Long): String =
    if (ms > 0L) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms)) else "尚无记录"

private fun bytes(bytes: Long): String =
    if (bytes >= 1_048_576) "%.1f MiB".format(bytes / 1_048_576.0) else "%.1f KiB".format(bytes / 1024.0)

private fun sessionLabel(state: JSONObject?): String = when {
    state == null -> "未同步"
    !state.optBoolean("available", true) -> "状态异常"
    state.optBoolean("active", false) -> "运行中"
    else -> "未开启"
}
