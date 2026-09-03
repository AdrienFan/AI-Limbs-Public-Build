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
                description = "APK → 读取版本 → 生成 manifest/integrity → Ed25519 签名 → 复验 → 最终分发包。",
                // Only the UI declaration migrates here; PackagerEngine and panel business logic stay unchanged.
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("blocks", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "支持系统插件、父级插件与 .ailx 子级扩展。已知官方 payload 可自动识别；第三方包可提供同目录 Manifest 模板。"))
                        .put(JSONObject().put("type", "dynamic_panel").put("provider_id", PANEL_ID)))
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "打包中心",
                description = "打包、签名并验证 AI Limbs 插件",
                screenId = SCREEN_ID
            )
        )
        return InProcessPluginHandle { Unit }
    }
    private fun inspectCapability(engine: PackagerEngine, parameters: String): String = runCatching {
        val root = JSONObject(parameters)
        engine.inspectJson(
            inputPath = root.optString("input_path"),
            manifestPath = root.optString("manifest_path").ifBlank { null }
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

private class PackagerPanel(private val engine: PackagerEngine) : InProcessUiStateProvider {
    private var inputPath = ""
    private var manifestPath = ""
    private var outputDirectory = ""
    private var statusLines = listOf(
        "输入 APK 后先点“识别”；已知官方 payload 会自动判断包类型。",
        "最终文件名固定为：<Display Name> v<APK versionName>.<ext>"
    )

    /**
     * The Packager publishes only opaque Plugin Center schema JSON across the shared UI boundary.
     * PackagerEngine remains the business source of truth; changing visual fields/actions no longer
     * requires adding data classes or enums to Stable Kernel.
     */
    private val mutableState = MutableStateFlow<String?>(buildStateJson())
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()

    override suspend fun perform(eventId: String, payloadJson: String): String {
        val fieldValues = JSONObject(payloadJson).optJSONObject("field_values")
        inputPath = fieldValues?.optString(FIELD_INPUT)?.trim().orEmpty()
        manifestPath = fieldValues?.optString(FIELD_MANIFEST)?.trim().orEmpty()
        outputDirectory = fieldValues?.optString(FIELD_OUTPUT)?.trim().orEmpty()
        return when (eventId) {
            ACTION_SCAN -> scan()
            ACTION_INSPECT -> inspect()
            ACTION_KEYS -> keys()
            ACTION_PACKAGE -> packageArtifact()
            else -> result("未知操作：$eventId")
        }
    }

    private fun scan(): String = runCatching {
        val files = engine.scanDownloads()
        if (files.isEmpty()) {
            statusLines = listOf("Download 下没有找到 APK。")
            return@runCatching result("没有找到 APK")
        }
        inputPath = files.first().absolutePath
        statusLines = buildList {
            add("已自动选择最近 APK：${files.first().name}")
            add("最近找到 ${files.size} 个 APK：")
            files.take(6).forEach { add("• ${it.absolutePath}") }
        }
        result("已选择最近下载的 APK")
    }.getOrElse { failure("扫描失败", it) }

    private fun inspect(): String = runCatching {
        val info = engine.inspectJson(inputPath, manifestPath.ifBlank { null })
        statusLines = listOf(
            "名称：${info.optString("display_name")}",
            "版本：${info.optString("version")}",
            "类型：${info.optString("artifact_type")}",
            "包名：${info.optString("package_name")}",
            "Signer：${info.optString("signer_id")}",
            "输出：${info.optString("output_name")}",
            "Manifest：${info.optString("manifest_source")}"
        )
        result("识别完成")
    }.getOrElse { failure("识别失败", it) }

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

    private suspend fun packageArtifact(): String = runCatching {
        val output = engine.packageAndVerify(
            inputPath = inputPath,
            manifestPath = manifestPath.ifBlank { null },
            outputDirectory = outputDirectory.ifBlank { null }
        )
        statusLines = listOf(
            "✅ 打包完成",
            "名称：${output.optString("display_name")}",
            "版本：${output.optString("version")}",
            "输出：${output.optString("output_name")}",
            "Payload SHA-256：通过",
            "Ed25519 Signature：${if (output.optBoolean("signature_verified")) "通过" else "失败"}",
            "Package SHA-256：${output.optString("package_sha256")}",
            "路径：${output.optString("output_path")}"
        )
        result("打包和验证全部通过")
    }.getOrElse { failure("打包失败", it) }

    private fun buildStateJson(): String = JSONObject()
        .put("schema", 1)
        .put("title", "插件打包")
        .put("description", "版本号从 APK 自动读取；输出名自动包含 Display Name 与版本号。")
        .put("status_lines", JSONArray(statusLines))
        .put("fields", JSONArray()
            .put(textField(
                FIELD_INPUT,
                "输入 APK 路径",
                inputPath,
                "/storage/emulated/0/Download/.../plugin-debug.apk"
            ))
            .put(textField(
                FIELD_MANIFEST,
                "Manifest 模板（第三方包可选）",
                manifestPath,
                "留空则自动识别官方 payload 或同目录 JSON"
            ))
            .put(textField(
                FIELD_OUTPUT,
                "输出目录",
                outputDirectory,
                "留空 = 与输入 APK 同目录"
            )))
        .put("actions", JSONArray()
            .put(action(ACTION_SCAN, "扫描 Download"))
            .put(action(ACTION_INSPECT, "识别", listOf(FIELD_INPUT)))
            .put(action(ACTION_KEYS, "导入/检查密钥"))
            .put(action(ACTION_PACKAGE, "打包并验证", listOf(FIELD_INPUT))))
        .toString()

    private fun textField(id: String, label: String, value: String, placeholder: String) = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("kind", "text")
        .put("value", value)
        .put("placeholder", placeholder)
        .put("enabled", true)

    private fun action(id: String, label: String, requiredFieldIds: List<String> = emptyList()) = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("enabled", true)
        .put("required_field_ids", JSONArray(requiredFieldIds))

    private fun result(message: String): String {
        mutableState.value = buildStateJson()
        return JSONObject()
            .put("message", message)
            .put("field_values", JSONObject()
                .put(FIELD_INPUT, inputPath)
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
        const val FIELD_INPUT = "input_path"
        const val FIELD_MANIFEST = "manifest_path"
        const val FIELD_OUTPUT = "output_directory"
        const val ACTION_SCAN = "scan_download"
        const val ACTION_INSPECT = "inspect"
        const val ACTION_KEYS = "keys"
        const val ACTION_PACKAGE = "package"
    }
}
