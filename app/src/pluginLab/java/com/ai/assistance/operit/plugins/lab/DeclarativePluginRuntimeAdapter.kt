package com.ai.assistance.operit.plugins.lab

import com.ai.assistance.operit.plugins.center.PluginCapabilityExecutor
import com.ai.assistance.operit.plugins.center.PluginCapabilityParameterSpec
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginExtensionPoints
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapter
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapterContext
import com.ai.assistance.operit.plugins.center.PluginRuntimeHandle
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe v1 runtime: JSON contributes capabilities and UI, but never loads Dex/Jar/native code and
 * never receives Android Context or filesystem paths.
 */
internal object DeclarativePluginRuntimeAdapter : PluginRuntimeAdapter {
    override val kind: String = "declarative"

    override suspend fun mount(context: PluginRuntimeAdapterContext): PluginRuntimeHandle {
        rejectUnsupportedDeclarations(context)
        val root = readRuntime(context)
        if (root.optInt("schema", 0) != 1) {
            throw PluginInstallException("DECLARATIVE_SCHEMA_UNSUPPORTED", "runtime.json schema must be 1")
        }

        val actualCapabilities = registerCapabilities(context, root.optJSONArray("capabilities"))
        requireExact(
            "capabilities",
            context.manifest.provides.capabilities,
            actualCapabilities
        )

        val actualExtensions = registerExtensions(context, root.optJSONArray("extensions"))
        val declaredExtensions = context.manifest.provides.extensions
            .map { it.point to it.id }
            .toSet()
        requireExact("extensions", declaredExtensions, actualExtensions)

        return object : PluginRuntimeHandle {
            override suspend fun stop() = Unit
        }
    }

    private fun rejectUnsupportedDeclarations(context: PluginRuntimeAdapterContext) {
        if (context.manifest.provides.services.isNotEmpty() ||
            context.manifest.provides.providers.isNotEmpty()) {
            throw PluginInstallException(
                "DECLARATIVE_CONTRIBUTION_UNSUPPORTED",
                "Declarative v1 supports capabilities and UI extensions only"
            )
        }
    }

    private fun readRuntime(context: PluginRuntimeAdapterContext): JSONObject {
        val rawEntry = context.manifest.runtime.entry
            ?: throw PluginInstallException("RUNTIME_ENTRY_MISSING", "Declarative runtime entry is required")
        val entry = rawEntry.trim().replace('\\', '/')
        val segments = entry.split('/').filter { it.isNotBlank() }
        if (entry.startsWith('/') || segments.isEmpty() || segments.any { it == "." || it == ".." } ||
            !entry.lowercase().endsWith(".json")) {
            throw PluginInstallException("RUNTIME_ENTRY_INVALID", "Unsafe declarative runtime entry: $rawEntry")
        }
        val root = context.contentDir.canonicalFile
        val file = segments.fold(root) { parent, segment -> File(parent, segment) }.canonicalFile
        if (file.parentFile == null ||
            !(file == root || file.path.startsWith(root.path + File.separator)) ||
            !file.isFile) {
            throw PluginInstallException("RUNTIME_ENTRY_NOT_FOUND", "Runtime entry was not found: $entry")
        }
        if (file.length() > MAX_RUNTIME_BYTES) {
            throw PluginInstallException("RUNTIME_ENTRY_TOO_LARGE", "Declarative runtime exceeds 512 KiB")
        }
        return try {
            JSONObject(file.readText())
        } catch (error: Throwable) {
            throw PluginInstallException("RUNTIME_ENTRY_INVALID_JSON", "Invalid declarative runtime JSON", error)
        }
    }

    private fun registerCapabilities(
        context: PluginRuntimeAdapterContext,
        array: JSONArray?
    ): Set<String> {
        val registered = linkedSetOf<String>()
        for (index in 0 until (array?.length() ?: 0)) {
            val descriptor = array?.optJSONObject(index)
                ?: throw PluginInstallException("DECLARATIVE_CAPABILITY_INVALID", "Capability $index must be an object")
            val id = descriptor.requiredString("id")
            if (!registered.add(id)) {
                throw PluginInstallException("DECLARATIVE_CAPABILITY_DUPLICATE", "Duplicate capability: $id")
            }
            context.payloadContext.registrar.registerCapability(id, capability(context, descriptor))
        }
        return registered
    }

    private fun capability(
        context: PluginRuntimeAdapterContext,
        descriptor: JSONObject
    ): PluginCapabilitySpec {
        val operation = descriptor.requiredString("operation").lowercase()
        val executor = when (operation) {
            "echo" -> PluginCapabilityExecutor { parameters ->
                JSONObject().put("data", JSONObject(parameters.toString()))
            }
            "constant" -> {
                val result = descriptor.optJSONObject("result")
                    ?: throw PluginInstallException(
                        "DECLARATIVE_CONSTANT_INVALID",
                        "constant operation requires an object result"
                    )
                val snapshot = result.toString()
                PluginCapabilityExecutor { JSONObject(snapshot) }
            }
            "host_capability" -> {
                val target = descriptor.requiredString("target").lowercase()
                if (!target.startsWith("core.")) {
                    throw PluginInstallException(
                        "DECLARATIVE_HOST_TARGET_INVALID",
                        "Host target must use the core.* namespace"
                    )
                }
                PluginCapabilityExecutor { parameters ->
                    context.payloadContext.capabilityInvoker.invoke(target, parameters)
                }
            }
            else -> throw PluginInstallException(
                "DECLARATIVE_OPERATION_UNSUPPORTED",
                "Unsupported declarative operation: $operation"
            )
        }

        val parameters = descriptor.optJSONArray("parameters")
        val parameterSpecs = buildList {
            for (index in 0 until (parameters?.length() ?: 0)) {
                val item = parameters?.optJSONObject(index)
                    ?: throw PluginInstallException(
                        "DECLARATIVE_PARAMETER_INVALID",
                        "Capability parameter $index must be an object"
                    )
                add(
                    PluginCapabilityParameterSpec(
                        name = item.requiredString("name"),
                        type = item.optString("type", "string"),
                        description = item.optString("description", ""),
                        required = item.optBoolean("required", true),
                        default = item.optString("default").takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        return PluginCapabilitySpec(
            displayName = descriptor.optString("display_name").ifBlank { descriptor.requiredString("id") },
            description = descriptor.optString("description", ""),
            invokeAliases = descriptor.stringList("invoke_aliases"),
            keywords = descriptor.stringList("keywords"),
            parameters = parameterSpecs,
            suggestedParamsJson = descriptor.optJSONObject("suggested_params")?.toString(),
            inputSchema = descriptor.optJSONObject("input_schema")?.toString(),
            executor = executor
        )
    }

    private fun registerExtensions(
        context: PluginRuntimeAdapterContext,
        array: JSONArray?
    ): Set<Pair<String, String>> {
        val registered = linkedSetOf<Pair<String, String>>()
        val declaredScreens = context.manifest.provides.extensions
            .filter { it.point == PluginExtensionPoints.UI_SCREEN }
            .map { it.id }
            .toSet()

        for (index in 0 until (array?.length() ?: 0)) {
            val descriptor = array?.optJSONObject(index)
                ?: throw PluginInstallException("DECLARATIVE_EXTENSION_INVALID", "Extension $index must be an object")
            val point = descriptor.requiredString("point").lowercase()
            val id = descriptor.requiredString("id")
            val key = point to id
            if (!registered.add(key)) {
                throw PluginInstallException("DECLARATIVE_EXTENSION_DUPLICATE", "Duplicate extension: $point:$id")
            }
            val payload = descriptor.optJSONObject("payload")
                ?: throw PluginInstallException("DECLARATIVE_EXTENSION_INVALID", "Extension payload is required")
            val typedPayload = when (point) {
                PluginExtensionPoints.UI_HOME_TILE -> {
                    val screenId = payload.requiredString("screen_id")
                    if (screenId !in declaredScreens) {
                        throw PluginInstallException(
                            "DECLARATIVE_SCREEN_NOT_DECLARED",
                            "Home tile references an undeclared screen: $screenId"
                        )
                    }
                    PluginHomeTileSpec(
                        ownerPluginId = context.manifest.pluginId,
                        id = id,
                        title = payload.requiredString("title"),
                        description = payload.optString("description", ""),
                        screenId = screenId
                    )
                }
                PluginExtensionPoints.UI_SCREEN -> screen(context, id, payload)
                PluginExtensionPoints.UI_THEME -> theme(context, id, payload)
                else -> throw PluginInstallException(
                    "DECLARATIVE_EXTENSION_POINT_UNSUPPORTED",
                    "Declarative runtime cannot bind extension point: $point"
                )
            }
            context.payloadContext.registrar.registerExtension(point, id, typedPayload)
        }
        return registered
    }

    private fun theme(
        context: PluginRuntimeAdapterContext,
        id: String,
        payload: JSONObject
    ): PluginThemeSpec {
        val mode = when (payload.requiredString("mode").lowercase()) {
            "light" -> PluginThemeMode.LIGHT
            "dark" -> PluginThemeMode.DARK
            else -> throw PluginInstallException(
                "DECLARATIVE_THEME_MODE_INVALID",
                "Theme mode must be light or dark"
            )
        }
        val colors = linkedMapOf<String, String>()
        payload.optJSONObject("colors")?.let { colorObject ->
            val keys = colorObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in THEME_COLOR_KEYS) {
                    throw PluginInstallException(
                        "DECLARATIVE_THEME_COLOR_KEY_INVALID",
                        "Unsupported theme color key: $key"
                    )
                }
                val value = colorObject.optString(key).trim()
                if (!THEME_COLOR_HEX.matches(value)) {
                    throw PluginInstallException(
                        "DECLARATIVE_THEME_COLOR_INVALID",
                        "Theme color $key must be #RRGGBB or #AARRGGBB"
                    )
                }
                colors[key] = value
            }
        }
        val backgroundGradient = buildList {
            val array = payload.optJSONArray("background_gradient")
            if (array != null) {
                if (array.length() !in 2..12) {
                    throw PluginInstallException(
                        "DECLARATIVE_THEME_GRADIENT_INVALID",
                        "background_gradient must contain 2-12 colors"
                    )
                }
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (!THEME_COLOR_HEX.matches(value)) {
                        throw PluginInstallException(
                            "DECLARATIVE_THEME_COLOR_INVALID",
                            "Gradient color $index must be #RRGGBB or #AARRGGBB"
                        )
                    }
                    add(value)
                }
            }
        }
        return PluginThemeSpec(
            ownerPluginId = context.manifest.pluginId,
            id = id,
            mode = mode,
            pureBlack = payload.optBoolean("pure_black", false),
            colors = colors,
            backgroundGradient = backgroundGradient
        )
    }

    private fun screen(
        context: PluginRuntimeAdapterContext,
        id: String,
        payload: JSONObject
    ): PluginScreenSpec {
        val array = payload.optJSONArray("blocks") ?: JSONArray()
        val blocks = buildList {
            for (index in 0 until array.length()) {
                val block = array.optJSONObject(index)
                    ?: throw PluginInstallException("DECLARATIVE_UI_BLOCK_INVALID", "UI block $index must be an object")
                when (block.requiredString("type").lowercase()) {
                    "text" -> add(PluginScreenBlock.Text(block.requiredString("text")))
                    "capability_button" -> {
                        val capabilityId = block.requiredString("capability_id")
                        if (capabilityId !in context.manifest.provides.capabilities) {
                            throw PluginInstallException(
                                "DECLARATIVE_UI_CAPABILITY_NOT_DECLARED",
                                "UI block references an undeclared capability: $capabilityId"
                            )
                        }
                        add(
                            PluginScreenBlock.CapabilityButton(
                                label = block.requiredString("label"),
                                capabilityId = capabilityId,
                                parameters = block.optJSONObject("parameters")?.let {
                                    JSONObject(it.toString())
                                } ?: JSONObject()
                            )
                        )
                    }
                    else -> throw PluginInstallException(
                        "DECLARATIVE_UI_BLOCK_UNSUPPORTED",
                        "Only text and capability_button UI blocks are supported"
                    )
                }
            }
        }
        return PluginScreenSpec(
            ownerPluginId = context.manifest.pluginId,
            id = id,
            title = payload.requiredString("title"),
            description = payload.optString("description").takeIf { it.isNotBlank() },
            blocks = blocks
        )
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).trim().takeIf { it.isNotBlank() }
            ?: throw PluginInstallException("DECLARATIVE_FIELD_REQUIRED", "Missing string field: $name")

    private fun JSONObject.stringList(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun <T> requireExact(label: String, declared: Set<T>, actual: Set<T>) {
        if (declared != actual) {
            throw PluginInstallException(
                "DECLARATIVE_CONTRIBUTION_MISMATCH",
                "$label declaration mismatch; missing=${declared - actual}, extra=${actual - declared}"
            )
        }
    }

    private val THEME_COLOR_HEX = Regex("^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    private val THEME_COLOR_KEYS = setOf(
        "primary", "on_primary", "primary_container", "on_primary_container",
        "secondary", "on_secondary", "secondary_container", "on_secondary_container",
        "tertiary", "on_tertiary", "tertiary_container", "on_tertiary_container",
        "background", "on_background", "surface", "on_surface",
        "surface_variant", "on_surface_variant", "outline", "error", "on_error"
    )
    private const val MAX_RUNTIME_BYTES = 512L * 1024L
}
