package com.ai.assistance.operit.core.tools.system.privilege

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.core.tools.system.ShizukuConnectionInfo
import com.ai.assistance.operit.util.AppLogger
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import java.util.UUID

/** Host owns backend selection and Binder identity. No raw Binder crosses the plugin ABI. */
internal object PrivilegeRuntime {
    private const val OWNER = "plugin.system.permission_service"
    private const val TAG = "PermissionService"
    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    @Volatile private var binder: IBinder? = null
    @Volatile private var serverUid = -1
    @Volatile private var selected = false
    private var death: IBinder.DeathRecipient? = null
    private val client = object : IShizukuApplication.Stub() {
        override fun bindApplication(data: Bundle?) = Unit
        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) = Unit
        override fun showPermissionConfirmation(uid: Int, pid: Int, packageName: String?, code: Int) {
            throw SecurityException("AI Limbs Host owns plugin authorization")
        }
    }

    @Synchronized fun initialize(context: Context) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("ai_limbs_privilege_runtime_v1", Context.MODE_PRIVATE)
        selected = prefs.getString("backend", "shizuku") == "ai_limbs"
    }

    fun isSelected(): Boolean = selected

    fun connection(): ShizukuConnectionInfo? {
        val current = binder ?: return null
        if (!current.isBinderAlive) return null
        return ShizukuConnectionInfo(serverUid, current)
    }

    /** Called by the private bootstrap offer after validating the Core launch/session/UID/PID. */
    @Synchronized internal fun exportResidentConnection(): Bundle {
        check(selected) { "Resident requires the selected AI Limbs permission backend" }
        val current = checkNotNull(connection()) { "Permission backend is not connected" }
        val launchToken = checkNotNull(prefs.getString("launch_token", null)) {
            "Permission backend launch permission was revoked"
        }
        return Bundle().apply {
            putBinder("backend", current.binder)
            putInt("backend_uid", current.uid)
            putString("backend_token", launchToken)
        }
    }

    @Synchronized fun invoke(context: Context, owner: String, operation: String, args: JSONObject): JSONObject {
        initialize(context)
        require(owner == OWNER) { "Only the permission service owner can manage its runtime" }
        return when (operation) {
            "status" -> status()
            "pair" -> JSONObject().put("allowed", true)
            "prepare" -> {
                check(connection() == null) { "权限服务已在运行，请先停止再启动" }
                val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                check(prefs.edit().putString("launch_token", token)
                    .putLong("launch_deadline", System.currentTimeMillis() + 180_000L)
                    .putBoolean("active", false).commit()) { "Could not save launch permission" }
                JSONObject().put("token", token).put("package_name", app.packageName)
                    .put("host_uid", Process.myUid()).put("user_id", Process.myUid() / 100000)
                    .put("authority", app.packageName + ".privilege")
            }
            "stop" -> {
                check(prefs.edit().remove("launch_token").putBoolean("active", false).commit())
                val current = connection()
                try {
                    if (current != null) IShizukuService.Stub.asInterface(current.binder).exit()
                } catch (error: android.os.DeadObjectException) {
                    AppLogger.i(TAG, "Permission server exited")
                } finally {
                    clearBinder()
                    ShizukuAuthorizer.onPrivilegeBackendChanged()
                }
                status()
            }
            "select" -> {
                val backend = args.getString("backend")
                require(backend == "ai_limbs" || backend == "shizuku")
                if (backend == "ai_limbs") check(connection() != null) { "权限服务尚未连接" }
                check(prefs.edit().putString("backend", backend).commit())
                selected = backend == "ai_limbs"
                ShizukuAuthorizer.onPrivilegeBackendChanged()
                status()
            }
            else -> error("Unknown privileged runtime operation: $operation")
        }
    }

    /** Kernel teardown cannot depend on an already-revoked plugin scope. */
    fun revokeOwner(
        context: Context, owner: String,
        handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff? = null
    ) {
        if (owner != OWNER) return
        if (handoff != null) {
            handoff.verify(checkNotNull(connection()) { "Cannot retain a disconnected permission backend" })
            AppLogger.i(TAG, "Permission backend retained for the acknowledged Resident handoff")
            return
        }
        invoke(context, owner, "stop", JSONObject())
    }

    @Synchronized fun receive(callingUid: Int, token: String, incoming: IBinder): Boolean {
        require(callingUid == 0 || callingUid == 2000) { "Binder handoff requires root or adb" }
        val expected = prefs.getString("launch_token", null) ?: return false
        if (token != expected) return false
        if (!prefs.getBoolean("active", false) &&
            System.currentTimeMillis() > prefs.getLong("launch_deadline", 0L)) return false
        return attachConnection(callingUid, incoming, persistActive = true)
    }

    /** The private bootstrap exchange already verified this transfer. Never rewrite Host preferences
     * from the second process: a stale SharedPreferences cache could resurrect a revoked token. */
    @Synchronized internal fun adoptResidentConnection(context: Context, uid: Int, incoming: IBinder) {
        initialize(context)
        require(uid == 0 || uid == 2000) { "Resident backend requires root or adb" }
        check(attachConnection(uid, incoming, persistActive = false))
    }

    private fun attachConnection(callingUid: Int, incoming: IBinder, persistActive: Boolean): Boolean {
        if (binder == incoming && incoming.isBinderAlive) return true
        val service = IShizukuService.Stub.asInterface(incoming)
        val uid = service.uid
        require(uid == callingUid) { "Permission server UID mismatch" }
        require(service.version == 13) { "Unsupported permission server protocol" }
        service.attachApplication(client, Bundle().apply {
            putString("shizuku:attach-package-name", app.packageName)
            putInt("shizuku:attach-api-version", 13)
        })
        require(service.checkSelfPermission()) { "Host registration was not accepted" }
        clearBinder()
        val recipient = IBinder.DeathRecipient {
            synchronized(this) {
                if (binder == incoming) {
                    clearBinder()
                    AppLogger.w(TAG, "Permission server disconnected")
                    ShizukuAuthorizer.onPrivilegeBackendChanged()
                }
            }
        }
        incoming.linkToDeath(recipient, 0)
        binder = incoming
        death = recipient
        serverUid = uid
        if (persistActive) check(prefs.edit().putBoolean("active", true).commit())
        AppLogger.i(TAG, "Permission server connected, uid=$uid")
        ShizukuAuthorizer.onPrivilegeBackendChanged()
        return true
    }

    private fun clearBinder() {
        val current = binder
        val recipient = death
        binder = null
        death = null
        serverUid = -1
        if (current != null && recipient != null) {
            try { current.unlinkToDeath(recipient, 0) }
            catch (error: Exception) { AppLogger.w(TAG, "Binder death registration already removed: " + error.message) }
        }
    }

    private fun status(): JSONObject = JSONObject()
        .put("available", true).put("running", connection() != null)
        .put("backend", if (selected) "ai_limbs" else "shizuku")
        .put("uid", serverUid).put("api", 1)
}
