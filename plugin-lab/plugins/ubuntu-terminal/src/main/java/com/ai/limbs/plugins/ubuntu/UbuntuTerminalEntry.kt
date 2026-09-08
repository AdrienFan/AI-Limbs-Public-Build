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

class UbuntuTerminalEntry : InProcessPluginEntry {
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
                title = "Ubuntu命令终端",
                description = "Ubuntu 插件自持完整页面、rootfs、持久 PTY、多标签终端与兰儿共享观察；原生可执行底座由 Host Native Runtime v1 提供。",
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
                title = "Ubuntu命令终端",
                description = "插件自持 Ubuntu 页面、rootfs 与多标签持久 PTY，原生执行由 Host Native Runtime v1 提供",
                screenId = SCREEN_ID
            )
        )

        return InProcessPluginHandle {
            processCapability.shutdown()
            terminal.prepareForMaintenance()
        }
    }

    private companion object {
        const val PAGE_PROVIDER_ID = "plugin.ubuntu.page"
        const val SCREEN_ID = "plugin.system_environment.screen"
        const val TILE_ID = "plugin.system.ubuntu_terminal.tile"
        const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
    }
}
