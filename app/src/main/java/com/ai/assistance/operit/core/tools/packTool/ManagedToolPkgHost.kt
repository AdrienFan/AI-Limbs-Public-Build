package com.ai.assistance.operit.core.tools.packTool

import android.content.Context
import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.core.tools.javascript.JsEngine
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Minimal ToolPkg host used by Plugin Lab managed plugins. */
internal class ManagedToolPkgHost private constructor(
    private val context: Context
) : ToolPkgRuntimeHost {
    companion object {
        private const val TAG = "ManagedToolPkgHost"
        private const val TOOLPKG_RUNTIME_COMPOSE_DSL = "compose_dsl"

        @Volatile
        private var instance: ManagedToolPkgHost? = null

        fun getInstance(context: Context): ManagedToolPkgHost =
            instance ?: synchronized(this) {
                instance ?: ManagedToolPkgHost(context.applicationContext).also { instance = it }
            }
    }
    private data class ManagedMount(
        val pluginId: String,
        val loadResult: ToolPkgLoadResult,
        val activePackageNames: Set<String>
    )

    private val toolPkgManager = ToolPkgManager(context)
    private val mounts = ConcurrentHashMap<String, ManagedMount>()
    private val packages = ConcurrentHashMap<String, ToolPackage>()

    override fun mountManagedToolPkg(
        pluginId: String,
        managedPath: File,
        expectedVersion: String
    ): ManagedToolPkgMountResult {
        val normalizedPluginId = pluginId.trim()
        require(normalizedPluginId.isNotBlank()) { "Managed ToolPkg plugin id is required" }
        require(managedPath.isFile) { "Managed ToolPkg file not found: ${managedPath.absolutePath}" }
        require(managedPath.name.endsWith(".toolpkg", ignoreCase = true)) {
            "Managed ToolPkg payload must use .toolpkg"
        }
        check(mounts[normalizedPluginId] == null) {
            "Managed ToolPkg is already mounted: $normalizedPluginId"
        }

        val registrationEngine = JsEngine(context)
        val loadResult = try {
            ToolPkgLoader.loadManagedToolPkgFromFile(
                file = managedPath,
                jsEngine = registrationEngine,
                parseJsPackage = LegacyJsToolPackageParser::parse,
                reportPackageLoadError = { key, error ->
                    AppLogger.e(TAG, "ToolPkg load error [$key]: $error")
                }
            )
        } finally {
            registrationEngine.destroy()
        }

        val runtime = loadResult.containerRuntime
        require(runtime.sourceType == ToolPkgSourceType.MANAGED_PLUGIN) {
            "ToolPkg source is not managed-plugin"
        }
        require(runtime.packageName == normalizedPluginId) {
            "ToolPkg id '${runtime.packageName}' does not match plugin '$normalizedPluginId'"
        }
        val normalizedExpectedVersion = expectedVersion.trim()
        if (normalizedExpectedVersion.isNotBlank()) {
            require(runtime.version.trim() == normalizedExpectedVersion) {
                "ToolPkg version '${runtime.version}' does not match plugin '$normalizedExpectedVersion'"
            }
        }
        require(toolPkgManager.canRegisterToolPkg(loadResult, packages)) {
            "ToolPkg package id conflicts with an active managed package"
        }
        val activePackageNames = buildSet {
            add(runtime.packageName)
            runtime.subpackages
                .filter(ToolPkgSubpackageRuntime::enabledByDefault)
                .forEach { add(it.packageName) }
        }

        toolPkgManager.registerToolPkg(loadResult)
        packages[runtime.packageName] = loadResult.containerPackage
        loadResult.subpackagePackages.forEach { packages[it.name] = it }
        mounts[normalizedPluginId] =
            ManagedMount(normalizedPluginId, loadResult, activePackageNames)

        AppLogger.i(TAG, "Mounted managed ToolPkg $normalizedPluginId ${runtime.version}")
        return ManagedToolPkgMountResult(
            pluginId = normalizedPluginId,
            containerPackageName = runtime.packageName,
            version = runtime.version,
            managedPath = managedPath.canonicalPath,
            activePackageNames = activePackageNames
        )
    }
    override fun unmountManagedToolPkg(pluginId: String): Boolean {
        val normalized = pluginId.trim()
        val mount = mounts.remove(normalized) ?: return false
        val runtime = mount.loadResult.containerRuntime
        toolPkgManager.destroyToolPkgExecutionEngines(runtime.packageName)
        toolPkgManager.unregisterToolPkg(runtime.packageName)
        packages.remove(runtime.packageName)
        runtime.subpackages.forEach { packages.remove(it.packageName) }
        AppLogger.i(TAG, "Unmounted managed ToolPkg $normalized")
        return true
    }

    override fun isPackageEnabled(packageName: String): Boolean {
        val target = packageName.trim()
        if (target.isBlank()) return false
        return mounts.values.any { it.activePackageNames.contains(target) }
    }

    override fun getEnabledPackageNames(): List<String> =
        mounts.values
            .flatMap { it.activePackageNames }
            .distinct()
            .sorted()

    override fun getMountedContainerRuntimes(): List<ToolPkgContainerRuntime> =
        toolPkgManager.getToolPkgContainerRuntimes().sortedBy { it.packageName }
    override fun findPreferredPackageNameForSubpackageId(
        subpackageId: String,
        preferEnabled: Boolean
    ): String? {
        val target = subpackageId.trim()
        if (target.isBlank()) return null
        resolveToolPkgSubpackageRuntime(target)?.let { return it.packageName }
        val matches = toolPkgManager.subpackageByPackageNameInternal.values
            .filter { it.subpackageId.equals(target, ignoreCase = true) }
        if (matches.isEmpty()) return null
        return if (preferEnabled) {
            matches.firstOrNull { isPackageEnabled(it.packageName) }?.packageName
                ?: matches.first().packageName
        } else {
            matches.first().packageName
        }
    }

    override fun resolveToolPkgSubpackageRuntime(packageName: String): ToolPkgSubpackageRuntime? =
        toolPkgManager.subpackageByPackageNameInternal[packageName.trim()]

    override fun getPackageTools(packageName: String): ToolPackage? = packages[packageName.trim()]

    override fun getToolPkgContainerRuntime(packageName: String): ToolPkgContainerRuntime? =
        toolPkgManager.getToolPkgContainerRuntime(packageName.trim())
    override fun getToolPkgExecutionEngine(
        contextKey: String,
        containerPackageName: String
    ): JsEngine = toolPkgManager.getToolPkgExecutionEngine(contextKey, containerPackageName)

    override fun acquireToolPkgExecutionEngine(
        contextKey: String,
        containerPackageName: String
    ): JsEngine = toolPkgManager.acquireToolPkgExecutionEngine(contextKey, containerPackageName)

    override fun findToolPkgExecutionEngine(contextKey: String): JsEngine? =
        toolPkgManager.findToolPkgExecutionEngine(contextKey)

    override fun releaseToolPkgExecutionEngine(contextKey: String, engine: JsEngine) {
        toolPkgManager.releaseToolPkgExecutionEngine(contextKey, engine)
    }

    override fun getToolPkgMainScript(containerPackageName: String): String? {
        val runtime = getToolPkgContainerRuntime(containerPackageName) ?: return null
        if (!isPackageEnabled(runtime.packageName) || runtime.mainEntry.isBlank()) return null
        return readEntryBytes(runtime, runtime.mainEntry)?.toString(StandardCharsets.UTF_8)
    }
    override fun getToolPkgComposeDslScript(
        containerPackageName: String,
        uiModuleId: String?
    ): String? {
        val runtime = getToolPkgContainerRuntime(containerPackageName) ?: return null
        if (!isPackageEnabled(runtime.packageName)) return null
        val module = findComposeModule(runtime, uiModuleId) ?: return null
        return readEntryBytes(runtime, module.screen)?.toString(StandardCharsets.UTF_8)
    }

    override fun getToolPkgComposeDslScreenPath(
        containerPackageName: String,
        uiModuleId: String?
    ): String? {
        val runtime = getToolPkgContainerRuntime(containerPackageName) ?: return null
        if (!isPackageEnabled(runtime.packageName)) return null
        return findComposeModule(runtime, uiModuleId)?.screen?.trim()?.ifBlank { null }
    }

    private fun findComposeModule(
        runtime: ToolPkgContainerRuntime,
        uiModuleId: String?
    ): ToolPkgUiModuleRuntime? =
        runtime.uiModules.firstOrNull { module ->
            module.runtime.equals(TOOLPKG_RUNTIME_COMPOSE_DSL, ignoreCase = true) &&
                (uiModuleId.isNullOrBlank() || module.id.equals(uiModuleId, ignoreCase = true))
        }
    override fun getToolPkgResourceOutputFileName(
        packageNameOrSubpackageId: String,
        resourceKey: String,
        preferEnabledContainer: Boolean
    ): String? {
        val runtime = resolveContainer(packageNameOrSubpackageId, preferEnabledContainer) ?: return null
        val resource = runtime.resources.firstOrNull {
            it.key.equals(resourceKey.trim(), ignoreCase = true)
        } ?: return null
        return resource.path.substringAfterLast('/').ifBlank { resource.key }
    }

    override fun copyToolPkgResourceToFile(
        packageName: String,
        resourceKey: String,
        destinationFile: File
    ): Boolean {
        val runtime = resolveContainer(packageName, true) ?: return false
        return copyResource(runtime, resourceKey, destinationFile)
    }

    override fun copyToolPkgResourceToFileBySubpackageId(
        subpackageId: String,
        resourceKey: String,
        destinationFile: File,
        preferEnabledContainer: Boolean
    ): Boolean {
        val runtime = resolveContainer(subpackageId, preferEnabledContainer) ?: return false
        return copyResource(runtime, resourceKey, destinationFile)
    }
    private fun copyResource(
        runtime: ToolPkgContainerRuntime,
        resourceKey: String,
        destinationFile: File
    ): Boolean {
        val resource = runtime.resources.firstOrNull {
            it.key.equals(resourceKey.trim(), ignoreCase = true)
        } ?: return false
        destinationFile.parentFile?.mkdirs()
        if (!ToolPkgArchiveParser.isDirectoryResourceMime(resource.mime)) {
            val bytes = readEntryBytes(runtime, resource.path) ?: return false
            destinationFile.writeBytes(bytes)
            return true
        }

        return runCatching {
            ZipFile(File(runtime.sourcePath)).use { archive ->
                val index = ToolPkgArchiveParser.buildZipEntryIndex(archive)
                val prefix = resource.path.trimEnd('/') + "/"
                ZipOutputStream(destinationFile.outputStream().buffered()).use { output ->
                    index.entryNames
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                        .sorted()
                        .forEach { path ->
                            val relative = path.removePrefix(prefix)
                            if (relative.isBlank()) return@forEach
                            val bytes = ToolPkgArchiveParser.readZipEntryBytes(archive, index, path)
                                ?: return@forEach
                            output.putNextEntry(ZipEntry(relative))
                            output.write(bytes)
                            output.closeEntry()
                        }
                }
            }
            true
        }.getOrElse { error ->
            AppLogger.e(TAG, "Failed to export ToolPkg directory resource", error)
            false
        }
    }

    override fun readToolPkgTextResource(
        packageNameOrSubpackageId: String,
        resourcePath: String,
        preferEnabledContainer: Boolean
    ): String? {
        val runtime = resolveContainer(packageNameOrSubpackageId, preferEnabledContainer) ?: return null
        val normalizedPath = ToolPkgArchiveParser.normalizeResourcePath(resourcePath) ?: return null
        return readEntryBytes(runtime, normalizedPath)?.toString(StandardCharsets.UTF_8)
    }

    override fun readToolPkgWasmModuleBytes(
        packageNameOrSubpackageId: String,
        moduleId: String,
        exportName: String,
        preferEnabledContainer: Boolean
    ): ToolPkgWasmModuleBytes? {
        val runtime = resolveContainer(packageNameOrSubpackageId, preferEnabledContainer) ?: return null
        val normalizedModuleId = moduleId.trim()
        val normalizedExport = exportName.trim()
        val module = runtime.wasmModules.firstOrNull {
            it.id.equals(normalizedModuleId, ignoreCase = true)
        } ?: return null
        if (module.exports.isNotEmpty() &&
            module.exports.none { it.equals(normalizedExport, ignoreCase = true) }
        ) return null
        val bytes = readEntryBytes(runtime, module.path) ?: return null
        return ToolPkgWasmModuleBytes(
            containerPackageName = runtime.packageName,
            moduleId = module.id,
            path = module.path,
            bytes = bytes
        )
    }

    override fun getPluginConfigDirPath(pluginId: String): String {
        val normalized = pluginId.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (normalized.isBlank()) return ""
        val dir = File(context.filesDir, "plugin_config/$normalized")
        dir.mkdirs()
        return dir.absolutePath
    }
    private fun resolveContainer(
        packageNameOrSubpackageId: String,
        preferEnabled: Boolean
    ): ToolPkgContainerRuntime? {
        val target = packageNameOrSubpackageId.trim()
        if (target.isBlank()) return null
        getToolPkgContainerRuntime(target)?.let { runtime ->
            if (!preferEnabled || isPackageEnabled(runtime.packageName)) return runtime
        }
        resolveToolPkgSubpackageRuntime(target)?.let { subpackage ->
            val runtime = getToolPkgContainerRuntime(subpackage.containerPackageName)
            if (runtime != null && (!preferEnabled || isPackageEnabled(runtime.packageName))) {
                return runtime
            }
        }
        val matches = toolPkgManager.subpackageByPackageNameInternal.values
            .filter { it.subpackageId.equals(target, ignoreCase = true) }
        val ordered = if (preferEnabled) {
            matches.sortedByDescending { isPackageEnabled(it.containerPackageName) }
        } else matches
        return ordered.firstNotNullOfOrNull { getToolPkgContainerRuntime(it.containerPackageName) }
    }

    private fun readEntryBytes(runtime: ToolPkgContainerRuntime, rawPath: String): ByteArray? {
        if (runtime.sourceType != ToolPkgSourceType.MANAGED_PLUGIN) return null
        val sourceFile = File(runtime.sourcePath)
        if (!sourceFile.isFile) return null
        return runCatching {
            ZipFile(sourceFile).use { archive ->
                val index = ToolPkgArchiveParser.buildZipEntryIndex(archive)
                ToolPkgArchiveParser.readZipEntryBytes(archive, index, rawPath)
            }
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed reading ToolPkg entry ${runtime.packageName}:$rawPath", error)
        }.getOrNull()
    }
}
