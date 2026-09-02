package com.ai.assistance.operit.plugins.center

object PluginAbi {
    const val FORMAT = "AIL_PLUGIN_V1"
    const val SCHEMA_VERSION = 1
    const val CURRENT_API = 1
    const val PACKAGE_EXTENSION = ".ailp"
    const val MANIFEST_ENTRY = "plugin.json"
}

enum class PluginActivationMode(val wireName: String) {
    HOT("hot"),
    RESTART_REQUIRED("restart_required"),
    COLD_EXTENSION("cold_extension");

    companion object {
        fun fromWireName(value: String): PluginActivationMode? =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
    }
}

enum class PluginLifecycleState {
    INSTALLED,
    MOUNTING,
    ACTIVE,
    BLOCKED,
    UNMOUNTING,
    DISABLED,
    PENDING_RESTART,
    FAILED,
    QUARANTINED
}

data class PluginDisplaySpec(
    val name: String,
    val description: String? = null,
    val iconEntry: String? = null
)

data class PluginRuntimeSpec(
    val kind: String,
    val entry: String? = null,
    val configJson: String? = null
)

data class PluginDependencySpec(
    val pluginId: String,
    val minVersion: String? = null
)

data class PluginServiceDependencySpec(
    val serviceId: String,
    val minApi: Int? = null
)

data class PluginDependencies(
    val plugins: List<PluginDependencySpec> = emptyList(),
    val services: List<PluginServiceDependencySpec> = emptyList()
)

data class PluginPermissionSpec(
    val requestedScopes: Set<String> = emptySet()
)

data class PluginExtensionSpec(
    val point: String,
    val id: String,
    val apiVersion: Int
)

data class PluginProvidesSpec(
    val capabilities: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
    val providers: Set<String> = emptySet(),
    val extensions: List<PluginExtensionSpec> = emptyList()
)

data class PluginIntegritySpec(
    val algorithm: String,
    val entries: Map<String, String>
)

data class PluginSignatureSpec(
    val algorithm: String,
    val signerId: String,
    val signatureEntry: String
)

data class PluginManifest(
    val format: String,
    val schemaVersion: Int,
    val pluginId: String,
    val version: String,
    val apiTarget: Int,
    val apiMin: Int,
    val display: PluginDisplaySpec,
    val roles: Set<String>,
    val activationMode: PluginActivationMode,
    val runtime: PluginRuntimeSpec,
    val dependencies: PluginDependencies,
    val permissions: PluginPermissionSpec,
    val provides: PluginProvidesSpec,
    val uiRawJson: String?,
    val integrity: PluginIntegritySpec?,
    val signature: PluginSignatureSpec?
)

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList()
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1
        val size = maxOf(prerelease.size, other.prerelease.size)
        for (index in 0 until size) {
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return 0
    }

    companion object {
        private val PATTERN = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(raw: String): SemanticVersion? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                prerelease = match.groupValues[4].takeIf { it.isNotBlank() }?.split('.') ?: emptyList()
            )
        }
    }
}
