package com.ai.assistance.operit.plugins.center

import com.ai.limbs.plugin.runtime.ChildExtensionEntry
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHost

/**
 * Canonical code identity for plugin BUSINESS runtimes.
 *
 * Android Context and plugin code identity are deliberately separate concerns. Resident Core owns
 * a standalone ContextImpl for Binder/system-service identity, but app_process loads AI Limbs
 * business classes through its CLASSPATH loader. Using Context.classLoader as the parent of plugin
 * DexClassLoaders in Resident Core creates a second copy of the plugin ABI and makes an entry that
 * really implements InProcessPluginEntry fail an instanceof/cast check.
 *
 * Every parent/child BUSINESS runtime must therefore delegate AI Limbs ABI and host contract classes
 * to the loader that actually defined the running Core's ABI classes. Host-only presentation loaders
 * remain separate and continue to follow the Android Host Context.
 */
internal object PluginRuntimeClassLoaders {
    fun businessAbi(): ClassLoader {
        val pluginEntryLoader = requireNotNull(InProcessPluginEntry::class.java.classLoader) {
            "InProcessPluginEntry must not be defined by the bootstrap ClassLoader"
        }
        val pluginHostLoader = requireNotNull(InProcessPluginHost::class.java.classLoader)
        val childEntryLoader = requireNotNull(ChildExtensionEntry::class.java.classLoader)
        val childHostLoader = requireNotNull(ChildExtensionHost::class.java.classLoader)

        check(pluginEntryLoader === pluginHostLoader &&
            pluginEntryLoader === childEntryLoader &&
            pluginEntryLoader === childHostLoader) {
            "Plugin BUSINESS ABI classes do not share one defining ClassLoader"
        }
        check(pluginEntryLoader.loadClass(InProcessPluginEntry::class.java.name) ===
            InProcessPluginEntry::class.java) {
            "Canonical plugin ABI loader resolves a duplicate InProcessPluginEntry"
        }
        check(pluginEntryLoader.loadClass(ChildExtensionEntry::class.java.name) ===
            ChildExtensionEntry::class.java) {
            "Canonical plugin ABI loader resolves a duplicate ChildExtensionEntry"
        }
        return pluginEntryLoader
    }
}
