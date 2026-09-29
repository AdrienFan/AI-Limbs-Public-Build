package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.services.ServiceLifecycleOwner
import com.ai.limbs.plugin.runtime.InProcessOverlayPageProvider
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-owned Android windows. A signed, locally mounted presentation provider owns the content;
 * the Host owns permission, focus, drag position and window lifetime.
 */
internal class HostOverlayWindows(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val windows = linkedMapOf<String, Window>()
    private val preferences = context.getSharedPreferences("plugin_overlay_positions", Context.MODE_PRIVATE)

    private data class Window(
        val owner: String,
        val root: DraggableOverlayRoot,
        val header: TextView,
        val params: WindowManager.LayoutParams,
        val lifecycle: ServiceLifecycleOwner,
        val provider: InProcessOverlayPageProvider
    )

    private inner class DraggableOverlayRoot(
        context: Context,
        private val overlayId: String,
        private val params: WindowManager.LayoutParams
    ) : LinearLayout(context) {
        var expanded = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        // The expanded header disappears when collapsed; intercept only a drag so taps still open chat.
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (expanded) return super.onInterceptTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > touchSlop ||
                        kotlin.math.abs(event.rawY - downY) > touchSlop) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (expanded) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    params.x = (startX + (event.rawX - downX).toInt())
                        .coerceIn(0, (context.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - downY).toInt())
                        .coerceIn(0, (context.resources.displayMetrics.heightPixels - dp(48)).coerceAtLeast(0))
                    manager.updateViewLayout(this, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    preferences.edit().putInt("$overlayId.x", params.x)
                        .putInt("$overlayId.y", params.y).apply()
                    dragging = false
                }
            }
            return true
        }
    }

    suspend fun invoke(ownerPluginId: String, operation: String, parameters: JSONObject): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            when (operation) {
                "create" -> create(ownerPluginId, parameters)
                "update" -> update(ownerPluginId, parameters)
                "remove" -> remove(ownerPluginId, parameters)
                "list" -> JSONObject().put("windows", JSONArray(
                    windows.filterValues { it.owner == ownerPluginId }.keys.toList()
                ))
                else -> error("Unknown overlay operation: $operation")
            }
        }

    private fun create(ownerPluginId: String, spec: JSONObject): JSONObject {
        val id = spec.getString("overlay_id").trim().also { require(it.isNotEmpty()) }
        windows[id]?.let {
            require(ownerPluginId == it.owner ||
                ownerPluginId == InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID) {
                "Overlay is owned by another plugin"
            }
            it.provider.open()
            return JSONObject().put("created", true).put("overlay_id", id).put("existing", true)
        }
        val providerId = spec.getString("provider_id").trim()
        val binding = PluginPlatformKernel.presentationProvidersSnapshot()
            .singleOrNull { it.id == providerId }
            ?: error("Overlay presentation provider unavailable: $providerId")
        require(ownerPluginId == binding.ownerPluginId ||
            ownerPluginId == InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID) {
            "Overlay provider is owned by another plugin"
        }
        require(id.startsWith("${binding.ownerPluginId}.")) {
            "Overlay ID must be namespaced to its provider owner"
        }
        require(binding.metadata["overlay_enabled"] == "true") {
            "Provider has not declared an overlay surface"
        }
        val provider = binding.payload as? InProcessOverlayPageProvider
            ?: error("Overlay provider must be Host-local presentation")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return JSONObject().put("created", false).put("permission_required", true)
        }

        val lifecycle = ServiceLifecycleOwner()
        val params = WindowManager.LayoutParams(
            dp(56), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt("$id.x", context.resources.displayMetrics.widthPixels - dp(72))
            y = preferences.getInt("$id.y", dp(120))
                .coerceIn(0, (context.resources.displayMetrics.heightPixels - dp(48)).coerceAtLeast(0))
            x = x.coerceIn(0, (context.resources.displayMetrics.widthPixels - dp(56)).coerceAtLeast(0))
        }
        val root = DraggableOverlayRoot(context, id, params).apply {
            orientation = LinearLayout.VERTICAL
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
        }
        val header = TextView(context).apply {
            text = "拖动这里移动"
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xff303044.toInt())
            setTextColor(0xffffffff.toInt())
            visibility = View.GONE
        }
        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var rawX = 0f
            private var rawY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        rawX = event.rawX; rawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + (event.rawX - rawX).toInt())
                            .coerceIn(0, (context.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                        params.y = (startY + (event.rawY - rawY).toInt())
                            .coerceIn(0, (context.resources.displayMetrics.heightPixels - dp(48)).coerceAtLeast(0))
                        manager.updateViewLayout(root, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        preferences.edit().putInt("$id.x", params.x).putInt("$id.y", params.y).apply()
                        return true
                    }
                }
                return true
            }
        })
        val sharedUi = object : InProcessSharedUiHost {
            override fun supports(componentId: String): Boolean = false
            override fun createComponent(componentId: String, parametersJson: String): View =
                error("Overlay has no shared component: $componentId")
        }
        root.addView(header)
        val content = provider.createView(context, sharedUi)
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        try {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            manager.addView(root, params)
        } catch (error: Throwable) {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            throw error
        }
        windows[id] = Window(binding.ownerPluginId, root, header, params, lifecycle, provider)
        provider.open()
        return JSONObject().put("created", true).put("overlay_id", id)
    }

    private fun update(ownerPluginId: String, spec: JSONObject): JSONObject {
        val id = spec.getString("overlay_id")
        val window = windows[id] ?: error("Overlay unavailable: $id")
        require(window.owner == ownerPluginId) { "Overlay is owned by another plugin" }
        val expanded = spec.getBoolean("expanded")
        window.root.expanded = expanded
        window.header.visibility = if (expanded) View.VISIBLE else View.GONE
        window.params.width = if (expanded) dp(360).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(24)) else dp(56)
        window.params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (expanded) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.params.x = window.params.x.coerceIn(
            0, (context.resources.displayMetrics.widthPixels - window.params.width).coerceAtLeast(0)
        )
        manager.updateViewLayout(window.root, window.params)
        return JSONObject().put("updated", true).put("overlay_id", id)
    }

    private fun remove(ownerPluginId: String, spec: JSONObject): JSONObject {
        val id = spec.getString("overlay_id")
        val window = windows[id] ?: return JSONObject().put("removed", false)
        require(window.owner == ownerPluginId) { "Overlay is owned by another plugin" }
        windows.remove(id)
        manager.removeView(window.root)
        window.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        return JSONObject().put("removed", true).put("overlay_id", id)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
