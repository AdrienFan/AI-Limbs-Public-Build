package com.ai.assistance.operit.core.tools.packTool

import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.core.tools.javascript.JsEngine
import java.io.File

data class ManagedToolPkgMountResult(
    val pluginId: String,
    val containerPackageName: String,
    val version: String,
    val managedPath: String,
    val activePackageNames: Set<String>
)

data class ToolPkgWasmModuleBytes(
    val containerPackageName: String,
    val moduleId: String,
    val path: String,
    val bytes: ByteArray
)

internal interface ToolPkgRuntimeHost {
    fun mountManagedToolPkg(
        pluginId: String,
        managedPath: File,
        expectedVersion: String
    ): ManagedToolPkgMountResult

    fun unmountManagedToolPkg(pluginId: String): Boolean
    fun isPackageEnabled(packageName: String): Boolean
    fun getEnabledPackageNames(): List<String>
    fun findPreferredPackageNameForSubpackageId(
        subpackageId: String,
        preferEnabled: Boolean = true
    ): String?

    fun resolveToolPkgSubpackageRuntime(packageName: String): ToolPkgSubpackageRuntime?
    fun getPackageTools(packageName: String): ToolPackage?
    fun getActivePackageStateId(packageName: String): String? = null
    fun getToolPkgContainerRuntime(packageName: String): ToolPkgContainerRuntime?
    fun getMountedContainerRuntimes(): List<ToolPkgContainerRuntime>

    fun getToolPkgExecutionEngine(contextKey: String, containerPackageName: String): JsEngine
    fun acquireToolPkgExecutionEngine(contextKey: String, containerPackageName: String): JsEngine
    fun findToolPkgExecutionEngine(contextKey: String): JsEngine?
    fun releaseToolPkgExecutionEngine(contextKey: String, engine: JsEngine)

    fun getToolPkgMainScript(containerPackageName: String): String?
    fun getToolPkgComposeDslScript(containerPackageName: String, uiModuleId: String? = null): String?
    fun getToolPkgComposeDslScreenPath(containerPackageName: String, uiModuleId: String? = null): String?
    fun getToolPkgResourceOutputFileName(
        packageNameOrSubpackageId: String,
        resourceKey: String,
        preferEnabledContainer: Boolean = true
    ): String?
    fun copyToolPkgResourceToFile(packageName: String, resourceKey: String, destinationFile: File): Boolean
    fun copyToolPkgResourceToFileBySubpackageId(
        subpackageId: String,
        resourceKey: String,
        destinationFile: File,
        preferEnabledContainer: Boolean = true
    ): Boolean
    fun readToolPkgTextResource(
        packageNameOrSubpackageId: String,
        resourcePath: String,
        preferEnabledContainer: Boolean = true
    ): String?
    fun readToolPkgWasmModuleBytes(
        packageNameOrSubpackageId: String,
        moduleId: String,
        exportName: String,
        preferEnabledContainer: Boolean = true
    ): ToolPkgWasmModuleBytes?

    fun getPluginConfigDirPath(pluginId: String): String
}
