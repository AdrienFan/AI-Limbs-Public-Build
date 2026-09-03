package com.ai.limbs.plugins.developerguide

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

class DeveloperGuideEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        DeveloperGuideContent.sections.forEach { section ->
            host.registerProvider(
                section.providerId,
                StaticGuidePanel(section),
                mapOf("kind" to "read_only_guide_section")
            )
        }
        host.registerCapability(
            HANDBOOK_CAPABILITY_ID,
            "AI Limbs 开发维护手册 / Recovery Handbook",
            "上下文丢失或接手维护时优先调用；返回当前插件架构、升级边界、安全规则与维护规范。",
            InProcessCapabilityExecutor { DeveloperGuideMachineApi.handbook(host.version) }
        )
        host.registerCapability(
            SECTION_CAPABILITY_ID,
            "AI Limbs 开发维护手册章节读取",
            "按 index 或 section 读取指定章节；用于低上下文情况下分段恢复维护知识。",
            InProcessCapabilityExecutor { parameters ->
                DeveloperGuideMachineApi.section(parameters, host.version)
            }
        )

        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "AI Limbs 开发说明",
                description = "当前正式插件开发、升级、安全与维护规范。UI 与机器读取接口共用同一内容源。",
                // The document is interpreted by Plugin Center. Host only routes this opaque JSON.
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("blocks", JSONArray().apply {
                        DeveloperGuideContent.sections.forEach { section ->
                            put(JSONObject().put("type", "dynamic_panel").put("provider_id", section.providerId))
                        }
                    })
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "开发说明",
                description = "AI Limbs 当前插件标准、升级边界与维护恢复手册",
                screenId = SCREEN_ID
            )
        )
        return InProcessPluginHandle { Unit }
    }

    private companion object {
        const val SCREEN_ID = "plugin.system.developer_guide.screen"
        const val TILE_ID = "plugin.system.developer_guide.tile"
        const val HANDBOOK_CAPABILITY_ID = "plugin.developer_guide.handbook"
        const val SECTION_CAPABILITY_ID = "plugin.developer_guide.section"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}

private class StaticGuidePanel(section: GuideSection) : InProcessUiStateProvider {
    /**
     * Read-only guide state encoded with the Plugin Center component schema.
     * Stable Kernel only transports this JSON and never learns how guide sections are rendered.
     */
    private val mutableState = MutableStateFlow<String?>(
        JSONObject()
            .put("schema", 1)
            .put("title", section.title)
            .put("description", section.description)
            .put("status_lines", JSONArray(section.lines))
            .put("fields", JSONArray())
            .put("actions", JSONArray())
            .toString()
    )
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()

    override suspend fun perform(eventId: String, payloadJson: String): String =
        error("开发说明为只读内容")
}

private object DeveloperGuideMachineApi {
    private const val GUIDE_SCHEMA = 1
    private const val TARGET = "AI Limbs V0.7.2+ formal plugin architecture"
    private const val HANDBOOK_ID = "plugin.developer_guide.handbook"
    private const val SECTION_ID = "plugin.developer_guide.section"

    fun handbook(pluginVersion: String): String = JSONObject()
        .put("document_id", "ai_limbs.developer_maintenance_handbook")
        .put("guide_schema", GUIDE_SCHEMA)
        .put("plugin_version", pluginVersion)
        .put("target", TARGET)
        .put("read_only", true)
        .put("recovery_instruction", "上下文丢失时，在修改 AI Limbs 前先完整读取本手册。")
        .put("capability", HANDBOOK_ID)
        .put("section_capability", SECTION_ID)
        .put("sections", sectionsArray())
        .toString()
    fun section(parametersJson: String, pluginVersion: String): String {
        val parameters = runCatching { JSONObject(parametersJson) }.getOrDefault(JSONObject())
        val byIndex = parameters.optInt("index", -1)
        val token = parameters.optString("section").trim()
        val tokenIndex = token.toIntOrNull()
        val section = when {
            byIndex in 1..DeveloperGuideContent.sections.size -> DeveloperGuideContent.sections[byIndex - 1]
            tokenIndex != null && tokenIndex in 1..DeveloperGuideContent.sections.size ->
                DeveloperGuideContent.sections[tokenIndex - 1]
            token.isNotBlank() -> DeveloperGuideContent.sections.firstOrNull {
                it.providerId.equals(token, ignoreCase = true) ||
                    it.title.contains(token, ignoreCase = true)
            }
            else -> null
        }
        return if (section == null) {
            JSONObject()
                .put("status", "SECTION_NOT_FOUND")
                .put("plugin_version", pluginVersion)
                .put("usage", "传入 {\"index\":1} 或 {\"section\":\"关键词/providerId\"}")
                .put("available", sectionIndex())
                .toString()
        } else sectionObject(section, DeveloperGuideContent.sections.indexOf(section) + 1)
            .put("plugin_version", pluginVersion)
            .toString()
    }
    private fun sectionsArray() = JSONArray().apply {
        DeveloperGuideContent.sections.forEachIndexed { index, section ->
            put(sectionObject(section, index + 1))
        }
    }

    private fun sectionIndex() = JSONArray().apply {
        DeveloperGuideContent.sections.forEachIndexed { index, section ->
            put(
                JSONObject()
                    .put("index", index + 1)
                    .put("id", section.providerId)
                    .put("title", section.title)
            )
        }
    }

    private fun sectionObject(section: GuideSection, index: Int) = JSONObject()
        .put("index", index)
        .put("id", section.providerId)
        .put("title", section.title)
        .put("description", section.description)
        .put("lines", JSONArray(section.lines))
}
