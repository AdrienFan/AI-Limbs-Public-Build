package com.ailimbs.freecessprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var stateView: TextView
    private lateinit var codeView: TextView
    private lateinit var logView: TextView
    private lateinit var batteryView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContentView(buildUi())
        uiScope.launch { ProbeRuntime.state.collectLatest { renderState(it) } }
        uiScope.launch { ProbeRuntime.logs.collectLatest { logView.text = it.joinToString("\n") } }
    }

    override fun onResume() {
        super.onResume()
        renderBatteryState()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        root.addView(TextView(this).apply {
            textSize = 22f
            text = "AI Limbs Freecess Probe"
        })
        root.addView(TextView(this).apply {
            text = "${BuildConfig.PROBE_LABEL}\npackage=${BuildConfig.APPLICATION_ID}\nuid=${android.os.Process.myUid()}\n" +
                "systemExempted=${BuildConfig.USE_SYSTEM_EXEMPTED}, screenReapply=${BuildConfig.REAPPLY_FGS_ON_SCREEN_OFF}, " +
                "hostSignals=${BuildConfig.USE_HOST_SIGNALS}, suspendDetect=${BuildConfig.USE_SUSPEND_DETECTOR}, forceRebuild=${BuildConfig.FORCE_REBUILD_ON_SUSPEND}"
        })
        batteryView = TextView(this)
        root.addView(batteryView)
        stateView = TextView(this).apply { textSize = 17f }
        root.addView(stateView)
        codeView = TextView(this).apply { textSize = 18f }
        root.addView(codeView)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(button("Start") { startProbe(ProbeService.ACTION_START) })
        row1.addView(button("Stop") { startProbe(ProbeService.ACTION_STOP) })
        row1.addView(button("Re-pair") { startProbe(ProbeService.ACTION_REPAIR) })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row2.addView(button("Open auth") { openAuthorization() })
        row2.addView(button("Battery allowlist") { requestBatteryAllowlist() })
        row2.addView(button("Clear log") { ProbeRuntime.clearLogs() })
        root.addView(row2)

        root.addView(TextView(this).apply { text = "Recent probe log:" })
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        return root
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun startProbe(action: String) {
        val intent = Intent(this, ProbeService::class.java).setAction(action)
        if (action == ProbeService.ACTION_STOP) startService(intent) else startForegroundService(intent)
    }

    private fun openAuthorization() {
        val uri = ProbeRuntime.state.value.verificationUri?.takeIf { it.isNotBlank() } ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
    }

    private fun requestBatteryAllowlist() {
        val uri = Uri.parse("package:$packageName")
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun renderBatteryState() {
        val pm = getSystemService(PowerManager::class.java)
        batteryView.text = "Doze allowlisted=${pm.isIgnoringBatteryOptimizations(packageName)}"
    }

    private fun renderState(state: ProbeState) {
        stateView.text = buildString {
            append("phase=").append(state.phase).append('\n')
            append(state.detail).append('\n')
            append("device=").append(state.deviceId ?: "-").append('\n')
            append("wakeLock=").append(state.wakeLockHeld)
            append("  fgs=").append(state.activeFgsTypesHex).append('\n')
            append("socketSince=").append(state.socketSinceMs ?: "-")
            append("  heartbeat=").append(state.lastHeartbeatAtMs ?: "-").append('\n')
            append("lastSuspendDelta=").append(state.lastSuspendDeltaMs ?: "-")
        }
        codeView.text = if (state.userCode.isNullOrBlank()) "" else "RDC authorization code: ${state.userCode}\n${state.verificationUri.orEmpty()}"
    }
}
