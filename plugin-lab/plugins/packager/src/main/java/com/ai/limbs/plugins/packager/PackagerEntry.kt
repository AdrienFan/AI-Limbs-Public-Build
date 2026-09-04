package com.ai.limbs.plugins.packager

import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PackagerEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val engine = PackagerEngine(host)
        val panel = PackagerPanel(engine)
        host.registerProvider(PANEL_ID, panel, mapOf("kind" to "packager_control_panel"))
        host.registerCapability(
            INSPECT_CAPABILITY,
            "AI Limbs 插件包识别",
            "读取 APK versionName，并识别 .ailpsys / .ailp / .ailx 打包配置。",
            InProcessCapabilityExecutor { parameters -> inspectCapability(engine, parameters) }
        )
        host.registerCapability(
            PACKAGE_CAPABILITY,
            "AI Limbs 插件包打包并验证",
            "按 APK 内版本号生成带版本文件名的签名 .ailpsys / .ailp / .ailx，并执行完整性与签名复验。",
            InProcessCapabilityExecutor { parameters -> packageCapability(engine, parameters) }
        )
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "AI Limbs 打包中心",
                description = "多选 APK / 扫描指定文件夹 → 队列识别 → Ed25519 签名 → 复验 → 最终分发包。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("blocks", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "支持系统插件、插件与 .ailx 子插件。可多选 APK 或扫描指定文件夹加入队列；已知官方 payload 自动识别，第三方包可提供 Manifest 模板。"))
                        .put(JSONObject().put("type", "dynamic_panel").put("provider_id", PANEL_ID)))
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "打包中心",
                description = "批量打包、签名并验证 AI Limbs 插件",
                screenId = SCREEN_ID
            )
        )
        return InProcessPluginHandle { Unit }
    }

    private fun inspectCapability(engine: PackagerEngine, parameters: String): String = runCatching {
        val root = JSONObject(parameters)
        engine.inspectJson(
            inputSource = root.optString("input_path"),
            manifestSource = root.optString("manifest_path").ifBlank { null }
        ).toString()
    }.getOrElse(::errorJson)

    private suspend fun packageCapability(engine: PackagerEngine, parameters: String): String = runCatching {
        val root = JSONObject(parameters)
        engine.packageAndVerify(
            inputPath = root.optString("input_path"),
            manifestPath = root.optString("manifest_path").ifBlank { null },
            outputDirectory = root.optString("output_directory").ifBlank { null }
        ).toString()
    }.getOrElse(::errorJson)

    private fun errorJson(error: Throwable): String = JSONObject()
        .put("status", "ERROR")
        .put("message", error.message ?: error::class.java.simpleName)
        .toString()

    private companion object {
        const val PANEL_ID = "plugin.packager.control_panel"
        const val SCREEN_ID = "plugin.system.packager.screen"
        const val TILE_ID = "plugin.system.packager.tile"
        const val INSPECT_CAPABILITY = "plugin.packager.inspect"
        const val PACKAGE_CAPABILITY = "plugin.packager.package"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}

private data class PackagerQueueItem(
    val source: String,
    val sourceName: String,
    val displayName: String,
    val version: String,
    val packageName: String,
    val artifactExtension: String,
    val manifestSource: String,
    val manifestOverride: String?,
    var state: String = "READY",
    var message: String = "",
    var outputPath: String = ""
)

private class PackagerPanel(private val engine: PackagerEngine) : InProcessUiStateProvider {
    private val queue = mutableListOf<PackagerQueueItem>()
    private var manifestPath = ""
    private var outputDirectory = ""
    private var isPackaging = false
    private var statusLines = listOf(
        "选择一个或多个 APK，或扫描指定文件夹；识别成功后会加入待打包队列。",
        "队列按顺序执行；单项失败不会阻断后续项目。"
    )

    /**
     * Packager owns queue/business state only. File/folder picker UI and queue-card rendering belong
     * to Plugin Center schema, so this plugin never takes Activity or Compose ownership directly.
     */
    private val mutableState = MutableStateFlow<String?>(buildStateJson())
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()

    override suspend fun perform(eventId: String, payloadJson: String): String {
        val payload = JSONObject(payloadJson)
        val fieldValues = payload.optJSONObject("field_values")
        manifestPath = fieldValues?.optString(FIELD_MANIFEST)?.trim().orEmpty()
        outputDirectory = fieldValues?.optString(FIELD_OUTPUT)?.trim().orEmpty()
        return when (eventId) {
            ACTION_ADD_FILES -> addSelectedFiles(payload)
            ACTION_SCAN_FOLDER -> scanFolder(payload)
            ACTION_REMOVE -> removeItem(payload)
            ACTION_CLEAR -> clearQueue()
            ACTION_KEYS -> keys()
            ACTION_PACKAGE_QUEUE -> packageQueue()
            else -> result("未知操作：$eventId")
        }
    }

    private fun addSelectedFiles(payload: JSONObject): String = runCatching {
        val sources = buildList {
            payload.optJSONArray("selected_uris")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            payload.optString("selected_uri").trim().takeIf { it.isNotBlank() }?.let(::add)
        }
        require(sources.isNotEmpty()) { "没有选择 APK" }
        addSources(sources, "选择")
    }.getOrElse { failure("加入队列失败", it) }

    private fun scanFolder(payload: JSONObject): String = runCatching {
        val folder = payload.optString("selected_uri").trim()
        require(folder.isNotBlank()) { "没有选择扫描文件夹" }
        val candidates = engine.scanFolder(folder)
        if (candidates.isEmpty()) {
            statusLines = listOf("所选文件夹中没有找到 APK。")
            return@runCatching result("没有找到 APK")
        }
        addSources(candidates.map(PackagerSource::source), "扫描", candidates.size)
    }.getOrElse { failure("扫描文件夹失败", it) }

    private fun addSources(sources: List<String>, origin: String, discovered: Int = sources.size): String {
        var added = 0
        var duplicate = 0
        val failures = mutableListOf<String>()
        val manifestOverride = manifestPath.ifBlank { null }
        sources.distinct().forEach { source ->
            if (queue.any { it.source == source }) {
                duplicate += 1
                return@forEach
            }
            runCatching { engine.inspectJson(source, manifestOverride) }
                .onSuccess { info ->
                    queue += PackagerQueueItem(
                        source = source,
                        sourceName = info.optString("source_name").ifBlank { source },
                        displayName = info.optString("display_name"),
                        version = info.optString("version"),
                        packageName = info.optString("package_name"),
                        artifactExtension = info.optString("artifact_extension"),
                        manifestSource = info.optString("manifest_source"),
                        manifestOverride = manifestOverride
                    )
                    added += 1
                }
                .onFailure { error ->
                    failures += "${source.substringAfterLast('/')}: ${error.message ?: "识别失败"}"
                }
        }
        statusLines = buildList {
            add("${origin}完成：发现 $discovered 个，加入 $added 个，重复 $duplicate 个，失败 ${failures.size} 个。")
            failures.take(5).forEach { add("• $it") }
            if (failures.size > 5) add("• 另有 ${failures.size - 5} 个识别失败项目")
        }
        return result("已更新待打包队列")
    }

    private fun removeItem(payload: JSONObject): String {
        if (isPackaging) return result("队列执行中，暂不能移除")
        val id = payload.optString("item_id").trim()
        val removed = queue.removeAll { it.source == id }
        statusLines = listOf(if (removed) "已从队列移除 1 项。" else "目标已不在队列中。")
        return result(if (removed) "已移除" else "项目不存在")
    }

    private fun clearQueue(): String {
        if (isPackaging) return result("队列执行中，暂不能清空")
        val count = queue.size
        queue.clear()
        statusLines = listOf("已清空 $count 个待打包项目。")
        return result("队列已清空")
    }

    private suspend fun keys(): String = runCatching {
        val root = engine.signingStatus(import = true)
        require(root.optString("status") == "OK") { root.optString("message") }
        val items = root.optJSONArray("profiles")
        statusLines = buildList {
            add("三套签名身份已检查：")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    add("• ${item.optString("profile")} · ${item.optString("signer_id")} · imported=${item.optBoolean("imported")}")
                }
            }
        }
        result("签名密钥已导入/检查")
    }.getOrElse { failure("密钥检查失败", it) }

    /** Sequential by design: signing/output resources stay deterministic and one failure does not stop the queue. */
    private suspend fun packageQueue(): String {
        if (queue.isEmpty()) return result("待打包队列为空")
        if (isPackaging) return result("队列已经在执行")
        isPackaging = true
        var success = 0
        var failed = 0
        try {
            queue.forEachIndexed { index, item ->
                item.state = "PACKAGING"
                item.message = "${index + 1}/${queue.size} 正在打包"
                statusLines = listOf("队列处理中：${index + 1}/${queue.size}", "当前：${item.displayName} ${item.version}")
                publishState()
                runCatching {
                    engine.packageAndVerify(
                        inputPath = item.source,
                        manifestPath = item.manifestOverride,
                        outputDirectory = outputDirectory.ifBlank { null }
                    )
                }.onSuccess { output ->
                    item.state = "SUCCESS"
                    item.message = "✅ 签名、完整性与成品复验通过"
                    item.outputPath = output.optString("output_path")
                    success += 1
                }.onFailure { error ->
                    item.state = "FAILED"
                    item.message = "❌ ${error.message ?: "打包失败"}"
                    failed += 1
                }
                publishState()
            }
        } finally {
            isPackaging = false
        }
        statusLines = listOf("队列执行完成：成功 $success 个，失败 $failed 个，共 ${queue.size} 个。")
        return result("队列打包完成")
    }

    private fun buildStateJson(): String = JSONObject()
        .put("schema", 1)
        .put("title", "插件打包队列")
        .put("description", "多选 APK 或扫描指定文件夹加入队列；每项独立识别并按顺序打包验证。")
        .put("status_lines", JSONArray(statusLines))
        .put("leading_actions", JSONArray()
            .put(action(
                ACTION_ADD_FILES,
                "选择 APK",
                kind = "file_picker",
                multiple = true,
                mimeTypes = listOf("application/vnd.android.package-archive", "application/octet-stream")
            ))
            .put(action(ACTION_SCAN_FOLDER, "扫描文件夹", kind = "directory_picker")))
        .put("queue", JSONObject()
            .put("title", "待打包队列")
            .put("empty_text", "还没有待打包项目。可以多选 APK，或扫描一个指定文件夹。")
            .put("remove_event_id", ACTION_REMOVE)
            .put("remove_label", "移除")
            .put("clear_event_id", ACTION_CLEAR)
            .put("clear_label", "全部清除")
            .put("items", JSONArray().apply {
                queue.forEach { item ->
                    put(JSONObject()
                        .put("id", item.source)
                        .put("title", "${item.displayName} v${item.version}")
                        .put("subtitle", "${item.artifactExtension} · ${item.packageName}")
                        .put("lines", JSONArray().apply {
                            put("来源：${item.sourceName}")
                            put("Manifest：${item.manifestSource}")
                            if (item.outputPath.isNotBlank()) put("输出：${item.outputPath}")
                        })
                        .put("status", queueStatus(item)))
                }
            }))
        .put("fields", JSONArray()
            .put(textField(
                FIELD_MANIFEST,
                "默认 Manifest 模板（第三方包可选）",
                manifestPath,
                "留空 = 官方自动识别；共享存储路径可自动查找同目录 JSON"
            ))
            .put(textField(
                FIELD_OUTPUT,
                "输出目录",
                outputDirectory,
                "留空 = 文件路径沿用同目录；系统选择器来源输出到 Download/AI-Limbs-Packager"
            )))
        .put("actions", JSONArray()
            .put(action(ACTION_KEYS, "导入/检查密钥"))
            .put(action(ACTION_PACKAGE_QUEUE, "开始队列打包", enabled = queue.isNotEmpty() && !isPackaging)))
        .toString()

    private fun queueStatus(item: PackagerQueueItem): String = when (item.state) {
        "PACKAGING" -> "状态：处理中 · ${item.message}"
        "SUCCESS" -> "状态：已完成 · ${item.message}"
        "FAILED" -> "状态：失败 · ${item.message}"
        else -> "状态：待打包"
    }

    private fun textField(id: String, label: String, value: String, placeholder: String) = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("kind", "text")
        .put("value", value)
        .put("placeholder", placeholder)
        .put("enabled", !isPackaging)

    private fun action(
        id: String,
        label: String,
        kind: String = "invoke",
        multiple: Boolean = false,
        mimeTypes: List<String> = emptyList(),
        enabled: Boolean = !isPackaging
    ) = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("kind", kind)
        .put("multiple", multiple)
        .put("mime_types", JSONArray(mimeTypes))
        .put("enabled", enabled)
        .put("required_field_ids", JSONArray())

    private fun publishState() {
        mutableState.value = buildStateJson()
    }

    private fun result(message: String): String {
        publishState()
        return JSONObject()
            .put("message", message)
            .put("field_values", JSONObject()
                .put(FIELD_MANIFEST, manifestPath)
                .put(FIELD_OUTPUT, outputDirectory))
            .toString()
    }

    private fun failure(prefix: String, error: Throwable): String {
        val message = error.message ?: error::class.java.simpleName
        statusLines = listOf("❌ $prefix", message)
        return result("$prefix：$message")
    }

    private companion object {
        const val FIELD_MANIFEST = "manifest_path"
        const val FIELD_OUTPUT = "output_directory"
        const val ACTION_ADD_FILES = "add_files"
        const val ACTION_SCAN_FOLDER = "scan_folder"
        const val ACTION_REMOVE = "remove_queue_item"
        const val ACTION_CLEAR = "clear_queue"
        const val ACTION_KEYS = "keys"
        const val ACTION_PACKAGE_QUEUE = "package_queue"
    }
}
