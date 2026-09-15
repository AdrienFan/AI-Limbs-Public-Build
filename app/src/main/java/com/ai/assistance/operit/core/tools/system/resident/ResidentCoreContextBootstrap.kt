package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.os.Process
import com.ai.assistance.operit.R

/**
 * Builds the standalone app Context for Resident Core without constructing OperitApplication.
 * Context creation is deliberately separate from business-runtime initialization so framework
 * bootstrap can fail before any plugin/business side effect is possible.
 */
internal object ResidentCoreContextBootstrap {
    data class Result(
        val context: Context,
        val resourcePackage: String,
        val opPackageName: String,
        val attributionPackageName: String?,
        val attributionUid: Int?
    )

    fun create(packageName: String): Result {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "Resident Core Context must be created on its main Looper thread"
        }

        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
        val systemContext = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        val applicationInfo = systemContext.packageManager.getApplicationInfo(packageName, 0)
        check(applicationInfo.uid == Process.myUid()) { "Core must run as the app UID" }
        check(applicationInfo.packageName == packageName) { "Core application package mismatch" }

        // createPackageContext on the system Context inherits its base/op package and attribution.
        // That gives our app UID an "android" identity when PowerManager/ConnectivityManager call
        // Binder. Use the framework's root app Context factory, before creating any app services.
        val compatibilityInfo = Class.forName("android.content.res.CompatibilityInfo")
        val defaultCompatibility =
            compatibilityInfo.getField("DEFAULT_COMPATIBILITY_INFO").get(null)
        val loadedApk = activityThread.getDeclaredMethod(
            "getPackageInfoNoCheck", ApplicationInfo::class.java, compatibilityInfo
        ).invoke(thread, applicationInfo, defaultCompatibility)
        val appContext = Class.forName("android.app.ContextImpl").getDeclaredMethod(
            "createAppContext", activityThread, Class.forName("android.app.LoadedApk")
        ).apply { isAccessible = true }.invoke(null, thread, loadedApk) as Context

        // Check the actual ContextImpl used by system-service managers, not just a wrapper getter.
        check(appContext.applicationInfo.uid == Process.myUid()) { "Core Context UID mismatch" }
        check(appContext.packageName == packageName) { "Core Context package mismatch" }
        check(appContext.opPackageName == packageName) {
            "Core operation package mismatch: ${appContext.opPackageName}"
        }
        val attribution = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.attributionSource.also {
                check(it.uid == Process.myUid() && it.packageName == packageName) {
                    "Core attribution mismatch: uid=${it.uid}, package=${it.packageName}"
                }
            }
        } else {
            null
        }

        // Never construct OperitApplication here. Doing so would execute Host startup and may restore
        // a second Plugin Kernel before the owner handoff has happened.
        val standalone = object : ContextWrapper(appContext) {
            override fun getApplicationContext(): Context = this
        }
        val resourcePackage = standalone.resources.getResourcePackageName(R.string.app_name)
        check(resourcePackage == packageName) { "Core resource package mismatch: $resourcePackage" }
        return Result(
            standalone, resourcePackage, appContext.opPackageName,
            attribution?.packageName, attribution?.uid
        )
    }
}
