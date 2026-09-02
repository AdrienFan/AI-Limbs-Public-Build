package com.ai.limbs.plugins.packager

import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessDynamicPanelProvider
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPanelAction
import com.ai.limbs.plugin.runtime.InProcessPanelField
import com.ai.limbs.plugin.runtime.InProcessPanelResult
import com.ai.limbs.plugin.runtime.InProcessPanelState
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessScreenBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                blocks = listOf(
                    InProcessScreenBlock.Text("支持系统插件、父级插件与 .ailx 子级扩展。已知官方 payload 可自动识别；第三方包可提供同目录 Manifest 模板。"),
                    InProcessScreenBlock.DynamicPanel(PANEL_ID)
                )
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
    }
}

private class PackagerPanel(private val engine: PackagerEngine) : InProcessDynamicPanelProvider {
    private var inputPath = ""
    private var manifestPath = ""
    private var outputDirectory = ""
    private var statusLines = listOf(
        "输入 APK 后先点“识别”；已知官方 payload 会自动判断包类型。",
        "最终文件名固定为：<Display Name> v<APK versionName>.<ext>"
    )
    private val mutableState = MutableStateFlow(buildState())
    override val state: StateFlow<InProcessPanelState?> = mutableState.asStateFlow()

    override suspend fun perform(
        actionId: String,
        fieldValues: Map<String, String>
    ): InProcessPanelResult {
        inputPath = fieldValues[FIELD_INPUT]?.trim().orEmpty()
        manifestPath = fieldValues[FIELD_MANIFEST]?.trim().orEmpty()
        outputDirectory = fieldValues[FIELD_OUTPUT]?.trim().orEmpty()
        return when (actionId) {
            ACTION_SCAN -> scan()
            ACTION_INSPECT -> inspect()
            ACTION_KEYS -> keys()
            ACTION_PACKAGE -> packageArtifact()
            else -> result("未知操作：$actionId")
        }
    }
    private fun scan(): InProcessPanelResult = runCatching {
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

    private fun inspect(): InProcessPanelResult = runCatching {
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
    private suspend fun keys(): InProcessPanelResult = runCatching {
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

    private suspend fun packageArtifact(): InProcessPanelResult = runCatching {
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
    private fun buildState(): InProcessPanelState = InProcessPanelState(
        title = "插件打包",
        description = "版本号从 APK 自动读取；输出名自动包含 Display Name 与版本号。",
        statusLines = statusLines,
        fields = listOf(
            InProcessPanelField(
                id = FIELD_INPUT,
                label = "输入 APK 路径",
                value = inputPath,
                placeholder = "/storage/emulated/0/Download/.../plugin-debug.apk"
            ),
            InProcessPanelField(
                id = FIELD_MANIFEST,
                label = "Manifest 模板（第三方包可选）",
                value = manifestPath,
                placeholder = "留空则自动识别官方 payload 或同目录 JSON"
            ),
            InProcessPanelField(
                id = FIELD_OUTPUT,
                label = "输出目录",
                value = outputDirectory,
                placeholder = "留空 = 与输入 APK 同目录"
            )
        ),
        actions = listOf(
            InProcessPanelAction(ACTION_SCAN, "扫描 Download"),
            InProcessPanelAction(ACTION_INSPECT, "识别", requiredFieldIds = setOf(FIELD_INPUT)),
            InProcessPanelAction(ACTION_KEYS, "导入/检查密钥"),
            InProcessPanelAction(ACTION_PACKAGE, "打包并验证", requiredFieldIds = setOf(FIELD_INPUT))
        )
    )
    private fun result(message: String): InProcessPanelResult {
        mutableState.value = buildState()
        return InProcessPanelResult(
            message = message,
            fieldValues = mapOf(
                FIELD_INPUT to inputPath,
                FIELD_MANIFEST to manifestPath,
                FIELD_OUTPUT to outputDirectory
            )
        )
    }

    private fun failure(prefix: String, error: Throwable): InProcessPanelResult {
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
