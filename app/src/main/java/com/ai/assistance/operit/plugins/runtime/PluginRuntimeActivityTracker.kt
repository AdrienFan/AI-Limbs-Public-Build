package com.ai.assistance.operit.plugins.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object PluginRuntimeActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun getCurrentActivity(): Activity? = currentActivity?.get()

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
