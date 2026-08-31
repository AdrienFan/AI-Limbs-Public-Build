package com.ai.assistance.operit.ui.pluginlab

import android.app.Application
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.plugins.runtime.PluginRuntimeActivityTracker

class PluginLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PluginRuntimeActivityTracker.initialize(this)
        AppLogger.bindContext(this)
        AppLogger.resetLogFile()
        AppLogger.i("PluginLab", "Plugin Lab process started")
    }
}
