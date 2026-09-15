package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.content.ContextWrapper
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
        val resourcePackage: String
    )

    fun create(packageName: String): Result {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "Resident Core Context must be created on its main Looper thread"
        }

        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredMethod("systemMain").invoke(null)
        val systemContext = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        val packageContext = systemContext.createPackageContext(packageName, 0)
        check(packageContext.applicationInfo.uid == Process.myUid()) { "Core must run as the app UID" }

        // Never construct OperitApplication here. Doing so would execute Host startup and may restore
        // a second Plugin Kernel before the owner handoff has happened.
        val standalone = object : ContextWrapper(packageContext) {
            override fun getApplicationContext(): Context = this
        }
        val resourcePackage = standalone.resources.getResourcePackageName(R.string.app_name)
        return Result(standalone, resourcePackage)
    }
}
