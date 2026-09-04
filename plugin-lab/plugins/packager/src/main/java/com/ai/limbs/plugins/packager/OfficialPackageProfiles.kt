package com.ai.limbs.plugins.packager

import org.json.JSONArray
import org.json.JSONObject

object OfficialPackageProfiles {
    fun resolve(packageName: String, version: String): Pair<PackagerArtifactType, JSONObject>? = when (packageName) {
        "com.ai.limbs.plugincenter.system.v1" -> PackagerArtifactType.SYSTEM to pluginCenter(version)
        "com.ai.limbs.payload.extensionhub" -> PackagerArtifactType.PARENT to extensionHub(version)
        "com.ai.limbs.payload.bridge" -> PackagerArtifactType.PARENT to bridge(version)
        "com.ai.limbs.payload.developerguide" -> PackagerArtifactType.PARENT to developerGuide(version)
        "com.ai.limbs.payload.packager" -> PackagerArtifactType.PARENT to packager(version)
        "com.ai.limbs.payload.rdc" -> PackagerArtifactType.CHILD to rdc(version)
        "com.ai.limbs.payload.triggercmd" -> PackagerArtifactType.CHILD to triggerCmd(version)
        else -> null
    }

    private fun pluginCenter(version: String): JSONObject = JSONObject()
        .put("format", "AIL_SYSTEM_PLUGIN_V1")
        .put("schema_version", 1)
        .put("plugin_id", "ai_limbs.system.plugin_center")
        .put("version", version)
        .put("display", display("Plugin Center", "AI Limbs 插件与系统接口管理中心"))
        .put("system", JSONObject().put("role", "plugin_center").put("host_abi", JSONObject().put("min", 2).put("max", 2)))
        .put("runtime", systemRuntime("payload/plugin-center.apk", "com.ai.limbs.plugincenter.PluginCenterEntry"))
        .put("permissions", JSONObject().put("requested_scopes", JSONArray()))
    private fun extensionHub(version: String): JSONObject = parentBase(
        pluginId = "plugin.system.extension_hub",
        version = version,
        name = "Plugin Extension Hub",
        description = "AI Limbs 二级扩展管理器：对 AIL_EXTENSION_V1 / .ailx 执行完整性、发布者信任、安装、挂载、备份与生命周期管理。",
        role = "system_extension_hub",
        entryClass = "com.ai.limbs.plugins.extensionhub.ExtensionHubEntry"
    ).put(
        "dependencies",
        JSONObject()
            .put("plugins", JSONArray())
            .put(
                "services",
                JSONArray().put(
                    JSONObject()
                        .put("id", "system.plugin_center.delegated_gateway")
                        .put("min_api", 1)
                )
            )
    ).put(
        "provides",
        provides(providers = listOf("system.extension.hub"))
    )

    private fun bridge(version: String): JSONObject {
        val root = parentBase(
            pluginId = "plugin.system.bridge",
            version = version,
            name = "Bridge",
            description = "AI Limbs Bridge Core：发布 ai_limbs.bridge.provider@3，由 .ailx 子插件动态提供 RDC、TRIGGERcmd 等 Bridge Provider。",
            role = "system_bridge",
            entryClass = "com.ai.limbs.plugins.bridge.BridgePluginEntry"
        )
        root.put(
            "dependencies",
            JSONObject()
                .put("plugins", JSONArray())
                .put("services", JSONArray())
        )
        root.put("permissions", JSONObject().put("requested_scopes", JSONArray().put("host.notification@1")))
        root.put(
            "provides",
            provides(
                capabilities = listOf("plugin.bridge.select_provider"),
                providers = listOf("plugin.bridge.control_panel"),
                extensions = listOf(
                    extension("ai_limbs.ui.home_tile", "plugin.system.bridge.tile"),
                    extension("ai_limbs.ui.screen", "plugin.system.bridge.screen", api = 2)
                )
            )
        )
        return root
    }

    private fun developerGuide(version: String): JSONObject = parentBase(
        pluginId = "plugin.system.developer_guide",
        version = version,
        name = "开发说明",
        description = "AI Limbs 当前插件开发、升级、安全与维护恢复手册；同时提供 AI 可调用只读接口。",
        role = "system_plugin",
        entryClass = "com.ai.limbs.plugins.developerguide.DeveloperGuideEntry"
    ).put(
        "provides",
        provides(
            capabilities = listOf("plugin.developer_guide.handbook", "plugin.developer_guide.section"),
            providers = listOf(
                "plugin.developer_guide.mechanism", "plugin.developer_guide.ailp", "plugin.developer_guide.ailpsys",
                "plugin.developer_guide.ailx", "plugin.developer_guide.ui", "plugin.developer_guide.security",
                "plugin.developer_guide.maintenance"
            ),
            extensions = listOf(
                extension("ai_limbs.ui.home_tile", "plugin.system.developer_guide.tile"),
                extension("ai_limbs.ui.screen", "plugin.system.developer_guide.screen", api = 2)
            )
        )
    )
    private fun packager(version: String): JSONObject = parentBase(
        pluginId = "plugin.system.packager",
        version = version,
        name = "AI Limbs 打包中心",
        description = "多选或扫描 APK，按队列识别、打包、签名并验证 .ailpsys / .ailp / .ailx 插件分发包。",
        role = "system_packager",
        entryClass = "com.ai.limbs.plugins.packager.PackagerEntry"
    ).put(
        "provides",
        provides(
            capabilities = listOf("plugin.packager.inspect", "plugin.packager.package"),
            providers = listOf("plugin.packager.control_panel"),
            extensions = listOf(
                extension("ai_limbs.ui.home_tile", "plugin.system.packager.tile"),
                extension("ai_limbs.ui.screen", "plugin.system.packager.screen", api = 2)
            )
        )
    )

    private fun rdc(version: String): JSONObject = childBase(
        extensionId = "ai_limbs.bridge.rdc",
        version = version,
        name = "RDC",
        description = "AI Limbs Remote Desktop Commander Bridge Provider。",
        entryClass = "com.ai.limbs.extensions.rdc.RdcExtensionEntry"
    )

    private fun triggerCmd(version: String): JSONObject = childBase(
        extensionId = "ai_limbs.bridge.triggercmd",
        version = version,
        name = "TRIGGERcmd",
        description = "AI Limbs TRIGGERcmd Bridge Provider。",
        entryClass = "com.ai.limbs.extensions.triggercmd.TriggerCmdExtensionEntry"
    )
    private fun parentBase(
        pluginId: String,
        version: String,
        name: String,
        description: String,
        role: String,
        entryClass: String
    ): JSONObject = JSONObject()
        .put("format", "AIL_PLUGIN_V1")
        .put("schema_version", 1)
        .put("plugin_id", pluginId)
        .put("version", version)
        .put("api", JSONObject().put("target", 1).put("min", 1))
        .put("display", display(name, description))
        .put("roles", JSONArray().put(role))
        .put("activation", JSONObject().put("mode", "hot"))
        .put("runtime", runtime("payload/plugin.apk", entryClass))
        .put("dependencies", JSONObject().put("plugins", JSONArray()).put("services", JSONArray()))
        .put("permissions", JSONObject().put("requested_scopes", JSONArray()))

    private fun childBase(
        extensionId: String,
        version: String,
        name: String,
        description: String,
        entryClass: String
    ): JSONObject = JSONObject()
        .put("format", "AIL_EXTENSION_V1")
        .put("schema_version", 1)
        .put("extension_id", extensionId)
        .put("version", version)
        .put("roles", JSONArray().put("system_extension"))
        .put("display", display(name, description))
        .put(
            "target",
            JSONObject()
                .put("plugin_id", "plugin.system.bridge")
                .put("extension_point", "ai_limbs.bridge.provider")
                .put("api", 3)
        )
        .put(
            "runtime",
            JSONObject()
                .put("kind", "android_child")
                .put("entry", "payload/extension.apk")
                .put("config", JSONObject().put("entry_class", entryClass))
        )
        .put("permissions", JSONObject().put("host_capabilities", JSONArray().put("core.bridge.remote.invoke")))

    private fun display(name: String, description: String): JSONObject =
        JSONObject().put("name", name).put("description", description)

    private fun runtime(entry: String, entryClass: String): JSONObject = JSONObject()
        .put("kind", "android_inprocess")
        .put("entry", entry)
        .put("config", JSONObject().put("entry_class", entryClass))

    private fun systemRuntime(entry: String, entryClass: String): JSONObject = JSONObject()
        .put("kind", "android_inprocess")
        .put("entry", entry)
        .put("entry_class", entryClass)

    /**
     * Declares a Host extension contract in generated manifests.
     * ui.screen uses API 2 because screen contents are now opaque Plugin Center documents; other
     * existing Host extension points remain on API 1.
     */
    private fun extension(point: String, id: String, api: Int = 1): JSONObject = JSONObject()
        .put("point", point)
        .put("id", id)
        .put("api", api)
    private fun provides(
        capabilities: List<String> = emptyList(),
        providers: List<String> = emptyList(),
        extensions: List<JSONObject> = emptyList()
    ): JSONObject {
        val extensionArray = JSONArray()
        extensions.forEach(extensionArray::put)
        return JSONObject()
            .put("capabilities", JSONArray(capabilities))
            .put("services", JSONArray())
            .put("providers", JSONArray(providers))
            .put("extensions", extensionArray)
    }
}
