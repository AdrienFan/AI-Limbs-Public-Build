package com.ai.limbs.plugins.developerguide

import com.ai.limbs.plugin.runtime.InProcessDynamicPanelProvider
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPanelResult
import com.ai.limbs.plugin.runtime.InProcessPanelState
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessScreenBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        val hostSurfacePanel = HostSurfaceGuidePanel(host)
        host.registerProvider(
            HOST_SURFACES_PROVIDER_ID,
            hostSurfacePanel,
            mapOf("kind" to "read_only_host_surface_catalog")
        )
        hostSurfacePanel.start()

        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "AI Limbs 开发说明",
                description = "只读开发与维护手册。固定规范 + 管理员开发模式 Host Surface 动态目录。",
                blocks = buildList {
                    DeveloperGuideContent.sections.forEach { section ->
                        add(InProcessScreenBlock.DynamicPanel(section.providerId))
                    }
                    add(InProcessScreenBlock.DynamicPanel(HOST_SURFACES_PROVIDER_ID))
                }
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "开发说明",
                description = "AI Limbs 插件、子插件、Host Contract 与维护规则",
                screenId = SCREEN_ID
            )
        )
        return InProcessPluginHandle { Unit }
    }

    private companion object {
        const val SCREEN_ID = "plugin.system.developer_guide.screen"
        const val TILE_ID = "plugin.system.developer_guide.tile"
        const val HOST_SURFACES_PROVIDER_ID = "plugin.developer_guide.host_surfaces"
    }
}
private class StaticGuidePanel(section: GuideSection) : InProcessDynamicPanelProvider {
    private val mutableState = MutableStateFlow(
        InProcessPanelState(
            title = section.title,
            description = section.description,
            statusLines = section.lines
        )
    )
    override val state: StateFlow<InProcessPanelState?> = mutableState.asStateFlow()

    override suspend fun perform(
        actionId: String,
        fieldValues: Map<String, String>
    ): InProcessPanelResult = error("开发说明为只读内容")
}

private class HostSurfaceGuidePanel(
    private val host: InProcessPluginHost
) : InProcessDynamicPanelProvider {
    private val mutableState = MutableStateFlow<InProcessPanelState?>(loadingState())
    override val state: StateFlow<InProcessPanelState?> = mutableState.asStateFlow()

    fun start() {
        host.scope.launch {
            while (isActive) {
                mutableState.value = loadState()
                delay(2_000L)
            }
        }
    }
    override suspend fun perform(
        actionId: String,
        fieldValues: Map<String, String>
    ): InProcessPanelResult = error("Host Surface 目录为只读内容")

    private suspend fun loadState(): InProcessPanelState = runCatching {
        val root = JSONObject(
            host.invokeHostCapability(HOST_SURFACE_CAPABILITY, "{}")
        )
        val surfaces = root.getJSONArray("surfaces")
        val developerMode = root.optBoolean("developer_mode", false)
        var openCount = 0
        val lines = mutableListOf<String>()
        lines += "数据源：${root.optString("source", "HostSurfacePolicy")}"
        lines += "管理员开发模式：${if (developerMode) "已开启" else "已关闭"}"
        lines += "说明：这里只列管理员开发模式管理的 Host Surface / Public Contract，不代表 AI Limbs 全部内部类。"
        lines += ""
        for (index in 0 until surfaces.length()) {
            val item = surfaces.getJSONObject(index)
            val allowed = item.optBoolean("allowed", false)
            if (allowed) openCount++
            val stateLabel = if (allowed) "OPEN" else "CLOSED"
            lines += "[$stateLabel] ${item.optString("title")}"
            lines += "  ID：${item.optString("id")}"
            lines += "  类型：${item.optString("kind")}"
            lines += "  ${item.optString("detail")}"
            item.optString("required_scope").takeIf { it.isNotBlank() && it != "null" }?.let {
                lines += "  Scope：$it"
            }
            val contracts = item.optJSONArray("public_contracts")
            if (contracts != null && contracts.length() > 0) {
                lines += "  Public Contract：" + buildList {
                    for (i in 0 until contracts.length()) add(contracts.getString(i))
                }.joinToString()
            }
            lines += ""
        }
        InProcessPanelState(
            title = "7. 当前 Host Surface / Public Contract",
            description = "与管理员开发模式中的 Host Surface Policy 同源，每 2 秒只读刷新。",
            statusLines = listOf(
                "共 ${surfaces.length()} 项 · OPEN $openCount · CLOSED ${surfaces.length() - openCount}"
            ) + lines
        )
    }.getOrElse { error ->
        InProcessPanelState(
            title = "7. 当前 Host Surface / Public Contract",
            description = "动态接口目录暂不可读取；固定开发规范仍然有效。",
            statusLines = listOf(
                "目录接口：$HOST_SURFACE_CAPABILITY",
                "状态：不可用",
                "原因：${error.message ?: error::class.java.simpleName}",
                "这通常表示管理员关闭了对应 Host Surface，或宿主尚未提供该 Contract。"
            )
        )
    }

    private fun loadingState() = InProcessPanelState(
        title = "7. 当前 Host Surface / Public Contract",
        description = "正在从管理员 Host Surface Policy 读取只读目录……"
    )

    private companion object {
        const val HOST_SURFACE_CAPABILITY = "core.host_surface.snapshot"
    }
}
