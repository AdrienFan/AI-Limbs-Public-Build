package com.ai.assistance.operit.ui.pluginlab

import android.app.Application
import com.ai.assistance.operit.plugins.center.PluginCenterKernel
import com.ai.assistance.operit.util.AppLogger

class PluginLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.bindContext(this)
        AppLogger.resetLogFile()
        PluginCenterKernel.initialize(this)
        AppLogger.i("PluginLab", "Plugin Lab process started")
    }
}
