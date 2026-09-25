package com.ai.limbs.plugins.visualmanager

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class VisualManagerPageProvider(
    private val host: InProcessPluginUiHost,
    private val actions: VisualManagerPageActions
) : InProcessPageProvider {
    override fun createView(context: Context, sharedUi: InProcessSharedUiHost): View =
        ComposeView(host.createPluginContext(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    VisualManagerPage(actions)
                }
            }
        }
}

@Composable
private fun VisualManagerPage(actions: VisualManagerPageActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dashboard by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    fun toast(message: String, long: Boolean = false) {
        Toast.makeText(
            context,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    fun refresh() {
        scope.launch {
            busy = true
            runCatching { actions.dashboard() }
                .onSuccess {
                    dashboard = it
                    lastError = null
                }
                .onFailure {
                    lastError = it.message ?: "视觉状态读取失败"
                }
            busy = false
        }
    }

    fun act(success: String, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess {
                    toast(success)
                    runCatching { actions.dashboard() }
                        .onSuccess { dashboard = it; lastError = null }
                        .onFailure { lastError = it.message ?: "刷新视觉状态失败" }
                }
                .onFailure {
                    lastError = it.message ?: "视觉操作失败"
                    toast(lastError ?: "视觉操作失败", true)
                }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val screen = dashboard?.optJSONObject("screen")
    val targets = dashboard?.optJSONObject("screen_targets")
    val camera = dashboard?.optJSONObject("camera")
    val sources = dashboard?.optJSONObject("camera_sources")
    val assets = dashboard?.optJSONObject("assets")
    val screenSessions = screen?.optJSONArray("sessions") ?: JSONArray()
    val cameraSessions = camera?.optJSONArray("sessions") ?: JSONArray()
    val screenActive = screenSessions.length() > 0
    val cameraActive = cameraSessions.length() > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("视觉管理", style = MaterialTheme.typography.headlineSmall)
        Text(
            "屏幕共享、摄像头和视觉缓存统一从这里收口。插件停用时会主动释放自己持有的视觉会话。",
            style = MaterialTheme.typography.bodySmall
        )

        lastError?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("当前异常", style = MaterialTheme.typography.titleSmall)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    if (
                        message.contains("not bound", ignoreCase = true) ||
                        message.contains("not registered", ignoreCase = true)
                    ) {
                        Text(
                            "视觉 Host Primitive 需要包含 build72 视觉源语的 AI Limbs 基座。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("活动视觉", style = MaterialTheme.typography.titleMedium)
                Text("屏幕共享：${if (screenActive) "开启 · ${screenSessions.length()} 个会话" else "关闭"}")
                Text("摄像头：${if (cameraActive) "开启 · ${cameraSessions.length()} 个会话" else "关闭"}")
                Text(
                    "视觉缓存：${assets?.optInt("count", 0) ?: 0} 项 · ${formatBytes(assets?.optLong("bytes", 0L) ?: 0L)}"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = { refresh() }
                    ) { Text("刷新") }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !busy && (screenActive || cameraActive),
                        onClick = {
                            act("全部视觉会话已停止") { actions.stopAll() }
                        }
                    ) { Text("全部停止") }
                }
            }
        }

        SectionTitle("屏幕共享")
        Text(
            if (screen?.optBoolean("projection_ready", false) == true) {
                "系统共享屏授权：已就绪"
            } else {
                "系统共享屏授权：按需申请；首次开始共享时会触发系统授权。"
            },
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            enabled = !busy,
            onClick = {
                act("屏幕截图已保存到视觉缓存") {
                    actions.screenCapture()
                }
            }
        ) { Text("单次截图") }

        jsonObjects(targets?.optJSONArray("targets")).forEach { target ->
            val targetId = target.optString("target_id")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        target.optString("name").ifBlank { targetId },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(targetId, style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy,
                        onClick = {
                            act("屏幕共享已开始") {
                                actions.screenStart(
                                    JSONObject()
                                        .put("target_id", targetId)
                                        .put("prime", true)
                                )
                            }
                        }
                    ) { Text("开始共享") }
                }
            }
        }
        if (targets?.optBoolean("available", true) == false) {
            Text(
                targets.optString("error", "当前没有可用显示目标"),
                style = MaterialTheme.typography.bodySmall
            )
        }

        jsonObjects(screenSessions).forEach { session ->
            val sessionId = session.optString("session_id")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("共享会话", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${session.optString("target_id")} · ${formatTime(session.optLong("started_at_ms"))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                act("屏幕帧已保存到视觉缓存") {
                                    actions.screenFrame(
                                        JSONObject().put("session_id", sessionId)
                                    )
                                }
                            }
                        ) { Text("取一帧") }
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                act("共享会话已停止") {
                                    actions.screenStop(
                                        JSONObject().put("session_id", sessionId)
                                    )
                                }
                            }
                        ) { Text("停止") }
                    }
                }
            }
        }

        SectionTitle("摄像头")
        if (sources != null && !sources.optBoolean("permission_granted", true)) {
            Text(
                "CAMERA 权限尚未授予 AI Limbs；启动或单拍前需要先授予相机权限。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        jsonObjects(sources?.optJSONArray("sources")).forEach { source ->
            val sourceId = source.optString("source_id")
            val facing = source.optString("lens_facing", "unknown")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("摄像头 $sourceId · $facing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "可用 JPEG 尺寸：${source.optJSONArray("jpeg_sizes")?.length() ?: 0}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                act("摄像头会话已开始") {
                                    actions.cameraStart(
                                        JSONObject().put("source_id", sourceId)
                                    )
                                }
                            }
                        ) { Text("启动") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                act("照片已保存到视觉缓存") {
                                    actions.cameraCapture(
                                        JSONObject().put("source_id", sourceId)
                                    )
                                }
                            }
                        ) { Text("单拍") }
                    }
                }
            }
        }

        jsonObjects(cameraSessions).forEach { session ->
            val sessionId = session.optString("session_id")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("摄像头会话", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${session.optString("source_id")} · ${session.optInt("width")}×${session.optInt("height")} · ${formatTime(session.optLong("started_at_ms"))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                act("摄像头帧已保存到视觉缓存") {
                                    actions.cameraFrame(
                                        JSONObject().put("session_id", sessionId)
                                    )
                                }
                            }
                        ) { Text("取一帧") }
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                act("摄像头会话已停止") {
                                    actions.cameraStop(
                                        JSONObject().put("session_id", sessionId)
                                    )
                                }
                            }
                        ) { Text("停止") }
                    }
                }
            }
        }

        SectionTitle("视觉缓存")
        Text(
            "这里只管理视觉管理插件自己的帧，不会删除其他插件或聊天产生的图片。",
            style = MaterialTheme.typography.bodySmall
        )
        val assetItems = jsonObjects(assets?.optJSONArray("items"))
        if (assetItems.isEmpty()) {
            Text("暂无视觉缓存。", style = MaterialTheme.typography.bodySmall)
        } else {
            assetItems.take(30).forEach { asset ->
                val assetId = asset.optString("asset_id")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "${asset.optString("kind")} · ${formatTime(asset.optLong("captured_at_ms"))}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "${formatBytes(asset.optLong("bytes"))} · ${asset.optString("file_name")}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                act("视觉缓存已删除") {
                                    actions.deleteAsset(
                                        JSONObject().put("asset_id", assetId)
                                    )
                                }
                            }
                        ) { Text("删除") }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    act("视觉缓存已清空") { actions.clearAssets() }
                }
            ) { Text("清空全部视觉缓存") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

private fun jsonObjects(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return "时间未知"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(epochMs))
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
