package com.ai.assistance.operit.core.tools.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Permission tokens are Host-defined; plugins never supply arbitrary Android permissions. */
internal object HostPermissionBroker {
    private val requestLock = Mutex()
    private val pending = ConcurrentHashMap<String, Request>()
    internal const val REQUEST_ID = "host_permission_request_id"
    private data class Request(
        val owner: String,
        val token: String,
        val result: CompletableDeferred<JSONObject>
    )

    private fun androidPermission(token: String): String = when (token) {
        "camera" -> Manifest.permission.CAMERA
        "microphone" -> Manifest.permission.RECORD_AUDIO
        else -> error("Unsupported Host permission token: $token")
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("host_permission_broker", Context.MODE_PRIVATE)

    fun check(context: Context, owner: String, token: String): JSONObject {
        require(owner.isNotBlank()) { "Permission request requires a plugin owner" }
        val permission = androidPermission(token)
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val blocked = !granted && preferences(context).getBoolean("$token.blocked", false)
        return JSONObject()
            .put("owner_plugin_id", owner)
            .put("permission", token)
            .put("android_permission", permission)
            .put("permission_granted", granted)
            .put("can_request_again", !blocked)
            .put("state", if (granted) "GRANTED" else if (blocked) "BLOCKED" else "NEEDS_PERMISSION")
    }

    suspend fun invoke(context: Context, owner: String, operation: String, parameters: JSONObject): JSONObject {
        val token = parameters.getString("permission")
        return when (operation) {
            "check" -> check(context, owner, token)
            "request" -> requestLock.withLock {
                val state = check(context, owner, token)
                if (state.getBoolean("permission_granted") || !state.getBoolean("can_request_again")) {
                    state
                } else {
                    val id = UUID.randomUUID().toString()
                    val result = CompletableDeferred<JSONObject>()
                    pending[id] = Request(owner, token, result)
                    try {
                        // Only a Host Activity can trigger Android consent; Core keeps plugin policy authority.
                        withContext(Dispatchers.Main) {
                            context.startActivity(Intent(context, HostPermissionBrokerActivity::class.java)
                                .putExtra(REQUEST_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        withTimeout(90_000L) { result.await() }
                    } finally {
                        pending.remove(id)
                    }
                }
            }
            "open_settings" -> {
                check(context, owner, token)
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                JSONObject().put("opened", true).put("permission", token).put("owner_plugin_id", owner)
            }
            else -> error("Unsupported Host permission operation: $operation")
        }
    }

    internal fun permissionFor(id: String): String? = pending[id]?.let { androidPermission(it.token) }

    internal fun complete(context: Context, id: String, granted: Boolean, canRequestAgain: Boolean) {
        val request = pending.remove(id) ?: return
        preferences(context).edit().putBoolean("${request.token}.blocked", !granted && !canRequestAgain).apply()
        request.result.complete(check(context, request.owner, request.token)
            .put("state", if (granted) "GRANTED" else if (canRequestAgain) "DENIED" else "BLOCKED"))
    }

    internal fun cancel(context: Context, id: String) {
        val request = pending.remove(id) ?: return
        request.result.complete(check(context, request.owner, request.token).put("state", "CANCELLED"))
    }
}

/** Not exported, no raw permission in the Intent, and no noHistory flag that can close consent prematurely. */
class HostPermissionBrokerActivity : ComponentActivity() {
    private var requestId = ""
    private var delivered = false
    private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permission = HostPermissionBroker.permissionFor(requestId)
        delivered = true
        if (permission != null) {
            HostPermissionBroker.complete(this, requestId, granted,
                granted || shouldShowRequestPermissionRationale(permission))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        requestId = intent.getStringExtra(HostPermissionBroker.REQUEST_ID).orEmpty()
        val permission = HostPermissionBroker.permissionFor(requestId)
        if (permission == null) {
            finish()
            return
        }
        if (savedInstanceState == null) launcher.launch(permission)
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && !delivered && requestId.isNotEmpty()) {
            HostPermissionBroker.cancel(this, requestId)
        }
        super.onDestroy()
    }
}
