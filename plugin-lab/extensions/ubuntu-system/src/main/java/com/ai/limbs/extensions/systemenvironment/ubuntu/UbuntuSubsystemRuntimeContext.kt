package com.ai.limbs.extensions.systemenvironment.ubuntu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Presents the vendored Ubuntu/Terminal runtime with plugin-owned storage and native paths
 * while preserving the real AI Limbs package/data identity used by legacy PRoot bind mapping.
 */
internal class UbuntuSubsystemRuntimeContext(
    base: Context,
    private val pluginId: String,
    private val pluginDataDir: File,
    private val pluginCacheDir: File,
    nativeLibraryDir: File
) : ContextWrapper(base) {
    private val preferenceCache = ConcurrentHashMap<String, SharedPreferences>()
    private val runtimeApplicationInfo = ApplicationInfo(base.applicationInfo).apply {
        this.nativeLibraryDir = nativeLibraryDir.absolutePath
    }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = pluginDataDir
    override fun getCacheDir(): File = pluginCacheDir
    override fun getApplicationInfo(): ApplicationInfo = runtimeApplicationInfo

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val isolatedName = "ailp_${sanitize(pluginId)}_$name"
        return preferenceCache.getOrPut(isolatedName) {
            baseContext.getSharedPreferences(isolatedName, mode)
        }
    }

    private fun sanitize(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_')
        }
    }
}
