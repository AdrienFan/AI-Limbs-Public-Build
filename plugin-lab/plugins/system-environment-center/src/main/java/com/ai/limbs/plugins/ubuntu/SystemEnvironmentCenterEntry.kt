package com.ai.limbs.plugins.ubuntu

import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugins.ubuntu.runtime.terminal.Pty
import com.ai.limbs.plugins.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.plugins.ubuntu.runtime.terminal.TerminalRuntimeAssets
import org.json.JSONArray
import org.json.JSONObject

class SystemEnvironmentCenterEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val nativeRuntime = UbuntuNativeRuntimeInstaller.prepare(host)
        TerminalRuntimeAssets.configure(host.runtimeEntryFile)
        Pty.configureNativeLibrary(nativeRuntime.ptyLibrary)

        val runtimeContext = UbuntuPluginRuntimeContext(
            base = host.applicationContext,
            pluginId = host.pluginId,
            pluginDataDir = host.dataDir,
            pluginCacheDir = host.cacheDir,
            nativeLibraryDir = nativeRuntime.directory
        )
        val terminal = TerminalManager.getInstance(runtimeContext)
        val lanerNetHostListenerSync = LanerNetHostListenerSync(host)
        val pageProvider = UbuntuTerminalPageProvider(
            host = host,
            terminal = terminal,
            nativeLibraryDir = nativeRuntime.directory
        )

        host.registerProvider(
            PAGE_PROVIDER_ID,
            pageProvider,
            mapOf(
                "kind" to "plugin_page",
                "screen_id" to SCREEN_ID
            )
        )

        UbuntuToolCapabilities.register(host, terminal)
        UbuntuFileSystemCapability.register(host, terminal)
        val processCapability = UbuntuProcessCapability(host.scope, terminal)
        processCapability.register(host)

        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "系统环境中心",
                description = "系统环境中心父级插件；当前以 Ubuntu 0.3.7 为迁移母本，后续承载 Ubuntu、Winlator 等系统环境子系统。",
                schemaId = PLUGIN_CENTER_UI_SCHEMA,
                documentJson = JSONObject()
                    .put("schema", 1)
                    .put("layout", "edge_to_edge")
                    .put(
                        "blocks",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "plugin_page")
                                .put("provider_id", PAGE_PROVIDER_ID)
                        )
                    )
                    .toString()
            )
        )
        host.registerHomeTile(
            InProcessHomeTile(
                id = TILE_ID,
                title = "系统环境中心",
                description = "统一承载和管理系统环境；当前保留 Ubuntu 0.3.7 运行能力作为迁移母本。",
                screenId = SCREEN_ID
            )
        )

        return InProcessPluginHandle {
            lanerNetHostListenerSync.close()
            processCapability.shutdown()
            terminal.prepareForMaintenance()
        }
    }

    private companion object {
        const val PAGE_PROVIDER_ID = "plugin.system_environment.page"
        const val SCREEN_ID = "plugin.system_environment_center.screen"
        const val TILE_ID = "plugin.system.environment_center.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}
