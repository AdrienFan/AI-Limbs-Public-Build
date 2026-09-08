package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.Pty
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalRuntimeAssets

internal class UbuntuSubsystem private constructor(
    val pageProvider: UbuntuSubsystemPageProvider,
    internal val terminal: TerminalManager,
    private val hostListenerSync: UbuntuSubsystemHostListenerSync,
    private val processCapability: UbuntuSubsystemProcessCapability,
) {
    suspend fun close() {
        hostListenerSync.close()
        processCapability.shutdown()
        terminal.prepareForMaintenance()
    }

    companion object {
        fun mount(host: InProcessPluginHost): UbuntuSubsystem {
            val nativeRuntime = UbuntuSubsystemNativeRuntimeInstaller.prepare(host)
            TerminalRuntimeAssets.configure(host.runtimeEntryFile)
            Pty.configureNativeLibrary(nativeRuntime.ptyLibrary)
            val runtimeContext = UbuntuSubsystemRuntimeContext(
                base = host.applicationContext,
                pluginId = host.pluginId,
                pluginDataDir = host.dataDir,
                pluginCacheDir = host.cacheDir,
                nativeLibraryDir = nativeRuntime.directory,
            )
            val terminal = TerminalManager.getInstance(runtimeContext)
            val hostListenerSync = UbuntuSubsystemHostListenerSync(host)
            val pageProvider = UbuntuSubsystemPageProvider(
                host = host,
                terminal = terminal,
                nativeLibraryDir = nativeRuntime.directory,
            )

            UbuntuSubsystemCapabilities.register(host, terminal)
            UbuntuSubsystemFileSystemCapability.register(host, terminal)
            val processCapability = UbuntuSubsystemProcessCapability(host.scope, terminal)
            processCapability.register(host)

            return UbuntuSubsystem(
                pageProvider = pageProvider,
                terminal = terminal,
                hostListenerSync = hostListenerSync,
                processCapability = processCapability,
            )
        }
    }
}
