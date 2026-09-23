package com.ai.assistance.operit.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.provider.Settings
import com.ai.assistance.ailimbs.accessibility.AiLimbsAccessibilityBridgeService
import com.ai.assistance.ailimbs.accessibility.IAiLimbsAccessibilityService
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.coroutines.resume

/**
 * AI Limbs UI 层次结构与无障碍操作入口。
 *
 * build67 起绑定主 APK 内置的 AI Limbs 无障碍桥，不再依赖独立 Provider APK。
 */
object UIHierarchyManager {
    private const val TAG = "UIHierarchyManager"
    private const val BIND_SERVICE_TIMEOUT_MS = 3000L

    @Volatile
    private var accessibilityService: IAiLimbsAccessibilityService? = null

    private val _isBound = MutableStateFlow(false)
    val isBound = _isBound.asStateFlow()

    private val bindingMutex = Mutex()

    @Volatile
    private var connectionContinuation: ((Boolean) -> Unit)? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            AppLogger.d(TAG, "AI Limbs 无障碍桥已连接")
            accessibilityService = IAiLimbsAccessibilityService.Stub.asInterface(service)
            _isBound.value = accessibilityService != null
            connectionContinuation?.invoke(_isBound.value)
            connectionContinuation = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            AppLogger.w(TAG, "AI Limbs 无障碍桥已断开")
            accessibilityService = null
            _isBound.value = false
            connectionContinuation?.invoke(false)
            connectionContinuation = null
        }

        override fun onBindingDied(name: ComponentName?) {
            AppLogger.w(TAG, "AI Limbs 无障碍桥绑定已失效")
            accessibilityService = null
            _isBound.value = false
            connectionContinuation?.invoke(false)
            connectionContinuation = null
        }

        override fun onNullBinding(name: ComponentName?) {
            AppLogger.e(TAG, "AI Limbs 无障碍桥返回空 Binder")
            accessibilityService = null
            _isBound.value = false
            connectionContinuation?.invoke(false)
            connectionContinuation = null
        }
    }

    /**
     * 兼容旧调用点：build67 已没有独立 Provider APK，直接打开系统无障碍设置。
     */
    fun launchProviderInstall(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogger.e(TAG, "打开无障碍设置失败", it) }
    }

    fun isUpdateNeeded(context: Context): Boolean = false

    fun isProviderAppInstalled(context: Context): Boolean = true
    private suspend fun ensureBound(context: Context): Boolean {
        if (_isBound.value && accessibilityService != null) return true
        return bindToService(context)
    }

    suspend fun bindToService(context: Context): Boolean = bindingMutex.withLock {
        if (_isBound.value && accessibilityService != null) {
            return@withLock true
        }

        val appContext = context.applicationContext
        val intent = Intent(appContext, AiLimbsAccessibilityBridgeService::class.java)

        val result = withTimeoutOrNull(BIND_SERVICE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                connectionContinuation = { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
                continuation.invokeOnCancellation {
                    connectionContinuation = null
                }
                try {
                    val bound = appContext.bindService(
                        intent,
                        serviceConnection,
                        Context.BIND_AUTO_CREATE
                    )
                    if (!bound) {
                        connectionContinuation = null
                        if (continuation.isActive) continuation.resume(false)
                    }
                } catch (error: Exception) {
                    AppLogger.e(TAG, "绑定 AI Limbs 无障碍桥失败", error)
                    connectionContinuation = null
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }

        if (result == null) {
            AppLogger.e(TAG, "绑定 AI Limbs 无障碍桥超时")
            connectionContinuation = null
            accessibilityService = null
            _isBound.value = false
            runCatching { appContext.unbindService(serviceConnection) }
            return@withLock false
        }

        result
    }

    fun unbindFromService(context: Context) {
        if (!_isBound.value && accessibilityService == null) return
        runCatching {
            context.applicationContext.unbindService(serviceConnection)
        }.onFailure {
            AppLogger.w(TAG, "解绑 AI Limbs 无障碍桥失败: " + it.message)
        }
        accessibilityService = null
        _isBound.value = false
    }
    suspend fun getUIHierarchy(context: Context): String {
        val service = readyService(context) ?: return ""
        return remote("获取 UI 层次结构", "") { service.uiHierarchy }
    }

    fun extractWindowInfo(xmlHierarchy: String): Pair<String?, String?> {
        if (xmlHierarchy.isEmpty()) return null to null
        return try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val parser = factory.newPullParser().apply {
                setInput(StringReader(xmlHierarchy))
            }
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    return parser.getAttributeValue(null, "package") to null
                }
                eventType = parser.next()
            }
            null to null
        } catch (error: Exception) {
            AppLogger.e(TAG, "解析 UI 层次结构失败", error)
            null to null
        }
    }

    suspend fun performClick(context: Context, x: Int, y: Int): Boolean {
        val service = readyService(context) ?: return false
        return remote("点击", false) { service.performClick(x, y) }
    }

    suspend fun performLongPress(context: Context, x: Int, y: Int): Boolean {
        val service = readyService(context) ?: return false
        return remote("长按", false) { service.performLongPress(x, y) }
    }

    suspend fun performSwipe(
        context: Context,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long
    ): Boolean {
        val service = readyService(context) ?: return false
        return remote("滑动", false) {
            service.performSwipe(startX, startY, endX, endY, duration)
        }
    }
    suspend fun performGlobalAction(context: Context, actionId: Int): Boolean {
        val service = readyService(context) ?: return false
        return remote("全局操作", false) { service.performGlobalAction(actionId) }
    }

    suspend fun findFocusedNodeId(context: Context): String? {
        val service = readyService(context) ?: return null
        return remote<String?>("读取焦点节点", null) { service.findFocusedNodeId() }
    }

    suspend fun setTextOnNode(
        context: Context,
        nodeId: String,
        text: String
    ): Boolean {
        val service = readyService(context) ?: return false
        return remote("输入文本", false) { service.setTextOnNode(nodeId, text) }
    }

    suspend fun takeScreenshot(
        context: Context,
        path: String,
        format: String
    ): Boolean {
        val service = readyService(context) ?: return false
        return remote("无障碍截图", false) { service.takeScreenshot(path, format) }
    }

    suspend fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = readyService(context) ?: return false
        return remote("读取无障碍状态", false) {
            service.isAccessibilityServiceEnabled
        }
    }

    suspend fun getCurrentActivityName(context: Context): String? {
        val service = readyService(context) ?: return null
        return remote<String?>("读取当前 Activity", null) {
            service.currentActivityName
        }
    }
    private suspend fun readyService(context: Context): IAiLimbsAccessibilityService? {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "AI Limbs 无障碍桥不可用")
            return null
        }
        return accessibilityService
    }

    private inline fun <T> remote(
        operation: String,
        fallback: T,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (error: RemoteException) {
            AppLogger.e(TAG, "$operation 失败：Binder 已断开", error)
            accessibilityService = null
            _isBound.value = false
            fallback
        } catch (error: Exception) {
            AppLogger.e(TAG, "$operation 失败", error)
            fallback
        }
    }
}
