package com.ai.assistance.operit.plugins.toolpkg

import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapter
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapterContext
import com.ai.assistance.operit.plugins.center.PluginRuntimeHandle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Trusted adapter that mounts an immutable ToolPkg payload owned by Plugin Store. */
internal object ToolPkgRuntimeAdapter : PluginRuntimeAdapter {
    override val kind: String = "toolpkg"

    override suspend fun mount(context: PluginRuntimeAdapterContext): PluginRuntimeHandle {
        val managedFile = resolveManagedEntry(context.contentDir, context.manifest.runtime.entry)
        val packageManager =
            PackageManager.getInstance(
                context.appContext,
                AIToolHandler.getInstance(context.appContext)
            )

        val mounted = try {
            withContext(Dispatchers.IO) {
                packageManager.mountManagedToolPkg(
                    pluginId = context.manifest.pluginId,
                    managedPath = managedFile,
                    expectedVersion = context.manifest.version
                )
            }
        } catch (error: Throwable) {
            if (error is PluginInstallException) throw error
            throw PluginInstallException(
                "TOOLPKG_MOUNT_FAILED",
                "Failed to mount managed ToolPkg '${context.manifest.pluginId}': ${error.message}",
                error
            )
        }

        return object : PluginRuntimeHandle {
            override suspend fun stop() {
                val stopped = withContext(Dispatchers.IO) {
                    packageManager.unmountManagedToolPkg(mounted.pluginId)
                }
                if (!stopped) {
                    throw PluginInstallException(
                        "TOOLPKG_UNMOUNT_FAILED",
                        "Failed to unmount managed ToolPkg '${mounted.pluginId}'"
                    )
                }
            }
        }
    }

    internal fun resolveManagedEntry(contentDir: File, rawEntry: String?): File {
        val entry = rawEntry?.trim()?.replace('\\', '/')
            ?: throw PluginInstallException("TOOLPKG_ENTRY_REQUIRED", "ToolPkg runtime.entry is required")
        if (entry.isBlank() || entry.startsWith('/') || DRIVE_PREFIX.containsMatchIn(entry)) {
            throw PluginInstallException("TOOLPKG_ENTRY_INVALID", "Invalid ToolPkg runtime.entry: $rawEntry")
        }
        val segments = entry.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            throw PluginInstallException("TOOLPKG_ENTRY_INVALID", "Invalid ToolPkg runtime.entry: $rawEntry")
        }

        val root = contentDir.canonicalFile
        val candidate = segments.fold(root) { current, segment -> File(current, segment) }.canonicalFile
        val insideRoot = candidate.path.startsWith(root.path + File.separator)
        if (!insideRoot || !candidate.isFile || !candidate.name.endsWith(".toolpkg", ignoreCase = true)) {
            throw PluginInstallException(
                "TOOLPKG_ENTRY_INVALID",
                "ToolPkg runtime.entry must resolve to an internal .toolpkg payload: $entry"
            )
        }
        return candidate
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
}
