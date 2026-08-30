package com.ai.assistance.operit.plugins.center

import org.json.JSONArray
import org.json.JSONObject

class PluginManifestException(
    val code: String,
    message: String
) : IllegalArgumentException(message)

object PluginManifestParser {
    private val PLUGIN_ID = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
    private val EXTENSION_POINT = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
    private val SYMBOLIC_ID = Regex("^[A-Za-z0-9]+(?:[._:/-][A-Za-z0-9]+)*$")

    fun parse(rawJson: String): PluginManifest {
        val root = runCatching { JSONObject(rawJson) }
            .getOrElse { throw PluginManifestException("MANIFEST_JSON_INVALID", "plugin.json is not valid JSON") }
        val format = root.requiredString("format")
        if (format != PluginAbi.FORMAT) {
            throw PluginManifestException("FORMAT_UNSUPPORTED", "Expected ${PluginAbi.FORMAT}, got $format")
        }
        val schemaVersion = root.requiredInt("schema_version")
        if (schemaVersion != PluginAbi.SCHEMA_VERSION) {
            throw PluginManifestException("SCHEMA_UNSUPPORTED", "Unsupported schema_version=$schemaVersion")
        }
        val pluginId = root.requiredString("plugin_id").also(::requirePluginId)
        val version = root.requiredString("version")
        requireVersion(version)
        val api = root.requiredObject("api")
        val apiTarget = api.requiredInt("target")
        val apiMin = api.requiredInt("min")
        if (apiMin <= 0 || apiTarget < apiMin || apiMin > PluginAbi.CURRENT_API || apiTarget > PluginAbi.CURRENT_API) {
            throw PluginManifestException("ABI_INCOMPATIBLE", "Plugin API range $apiMin..$apiTarget is incompatible with kernel API ${PluginAbi.CURRENT_API}")
        }
        val displayObject = root.optJSONObject("display") ?: JSONObject()
        val display = PluginDisplaySpec(
            name = displayObject.optString("name").trim().ifBlank { pluginId },
            description = displayObject.optString("description").trim().ifBlank { null },
            iconEntry = displayObject.optString("icon").trim().ifBlank { null }?.also(PluginPackagePaths::requireSafeRelativePath)
        )
        val roles = root.optJSONArray("roles").stringSet("roles")
        val activationObject = root.requiredObject("activation")
        val activationMode = PluginActivationMode.fromWireName(activationObject.requiredString("mode"))
            ?: throw PluginManifestException("ACTIVATION_UNSUPPORTED", "Unsupported activation mode")
        val runtimeObject = root.requiredObject("runtime")
        val runtimeKind = runtimeObject.requiredString("kind").lowercase().also { requireSymbolic("runtime.kind", it) }
        val runtimeEntry = runtimeObject.optString("entry").trim().ifBlank { null }
        if (runtimeKind != "none" && runtimeEntry == null) {
            throw PluginManifestException("RUNTIME_ENTRY_MISSING", "runtime.entry is required for runtime kind $runtimeKind")
        }
        runtimeEntry?.let(PluginPackagePaths::requireSafeRelativePath)
        val runtime = PluginRuntimeSpec(runtimeKind, runtimeEntry, runtimeObject.optJSONObject("config")?.toString())
        val dependencies = parseDependencies(root.optJSONObject("dependencies"))
        val permissions = PluginPermissionSpec(root.optJSONObject("permissions")?.optJSONArray("requested_scopes").stringSet("permissions.requested_scopes"))
        val providesObject = root.optJSONObject("provides")
        val provides = PluginProvidesSpec(
            capabilities = providesObject?.optJSONArray("capabilities").stringSet("provides.capabilities", true),
            services = providesObject?.optJSONArray("services").stringSet("provides.services", true),
            providers = providesObject?.optJSONArray("providers").stringSet("provides.providers", true),
            extensions = parseExtensions(providesObject?.optJSONArray("extensions"))
        )
        val signature = root.optJSONObject("signature")?.let(::parseSignature)
        return PluginManifest(
            format = format,
            schemaVersion = schemaVersion,
            pluginId = pluginId,
            version = version,
            apiTarget = apiTarget,
            apiMin = apiMin,
            display = display,
            roles = roles,
            activationMode = activationMode,
            runtime = runtime,
            dependencies = dependencies,
            permissions = permissions,
            provides = provides,
            uiRawJson = root.optJSONObject("ui")?.toString(),
            signature = signature
        )
    }

    private fun parseExtensions(value: JSONArray?): List<PluginExtensionSpec> {
        if (value == null) return emptyList()
        val seen = mutableSetOf<String>()
        return value.objects("provides.extensions").map { item ->
            val point = item.requiredString("point").lowercase().also(::requireExtensionPoint)
            val id = item.requiredString("id").also { requireSymbolic("extension id", it) }
            val apiVersion = item.requiredInt("api")
            if (apiVersion <= 0) {
                throw PluginManifestException("EXTENSION_API_INVALID", "extension api must be positive")
            }
            val key = "$point|$id"
            if (!seen.add(key)) {
                throw PluginManifestException("EXTENSION_DUPLICATE", "Duplicate extension declaration: $point:$id")
            }
            PluginExtensionSpec(point = point, id = id, apiVersion = apiVersion)
        }
    }

    private fun parseDependencies(value: JSONObject?): PluginDependencies {
        if (value == null) return PluginDependencies()
        val pluginDeps = value.optJSONArray("plugins").objects("dependencies.plugins").map { item ->
            val id = item.requiredString("id").also(::requirePluginId)
            val minVersion = item.optString("min_version").trim().ifBlank { null }
            minVersion?.let(::requireVersion)
            PluginDependencySpec(id, minVersion)
        }
        val serviceDeps = value.optJSONArray("services").objects("dependencies.services").map { item ->
            val id = item.requiredString("id").also { requireSymbolic("service dependency", it) }
            val minApi = if (item.has("min_api")) item.requiredInt("min_api") else null
            if (minApi != null && minApi <= 0) throw PluginManifestException("DEPENDENCY_INVALID", "service min_api must be positive")
            PluginServiceDependencySpec(id, minApi)
        }
        return PluginDependencies(pluginDeps, serviceDeps)
    }

    private fun parseSignature(value: JSONObject): PluginSignatureSpec {
        val algorithm = value.requiredString("algorithm")
        val signerId = value.requiredString("signer_id")
        val signatureEntry = value.requiredString("entry").also(PluginPackagePaths::requireSafeRelativePath)
        return PluginSignatureSpec(algorithm, signerId, signatureEntry)
    }

    private fun requirePluginId(value: String) {
        if (!PLUGIN_ID.matches(value)) throw PluginManifestException("PLUGIN_ID_INVALID", "Invalid plugin_id: $value")
    }

    private fun requireExtensionPoint(value: String) {
        if (!EXTENSION_POINT.matches(value)) {
            throw PluginManifestException("EXTENSION_POINT_INVALID", "Invalid extension point: $value")
        }
    }

    private fun requireVersion(value: String) {
        if (SemanticVersion.parse(value) == null) throw PluginManifestException("VERSION_INVALID", "Version must be SemVer: $value")
    }

    private fun requireSymbolic(field: String, value: String) {
        if (!SYMBOLIC_ID.matches(value)) throw PluginManifestException("SYMBOLIC_ID_INVALID", "Invalid $field: $value")
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).trim().ifBlank { throw PluginManifestException("MANIFEST_FIELD_MISSING", "$name is required") }

    private fun JSONObject.requiredInt(name: String): Int {
        if (!has(name)) throw PluginManifestException("MANIFEST_FIELD_MISSING", "$name is required")
        return runCatching { getInt(name) }.getOrElse { throw PluginManifestException("MANIFEST_FIELD_INVALID", "$name must be an integer") }
    }

    private fun JSONObject.requiredObject(name: String): JSONObject =
        optJSONObject(name) ?: throw PluginManifestException("MANIFEST_FIELD_INVALID", "$name must be an object")

    private fun JSONArray?.stringSet(field: String, symbolic: Boolean = false): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isBlank()) throw PluginManifestException("MANIFEST_FIELD_INVALID", "$field contains a blank value")
                if (symbolic) requireSymbolic(field, value)
                add(value)
            }
        }
    }

    private fun JSONArray?.objects(field: String): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                add(optJSONObject(index) ?: throw PluginManifestException("MANIFEST_FIELD_INVALID", "$field[$index] must be an object"))
            }
        }
    }
}
