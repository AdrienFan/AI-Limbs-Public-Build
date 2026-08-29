package com.ailimbs.freecessprobe

import android.app.Application

class ProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProbeLog.init(this)
        ProbeLog.i("APP", "startup variant=${BuildConfig.PROBE_LABEL} package=$packageName")
    }
}
