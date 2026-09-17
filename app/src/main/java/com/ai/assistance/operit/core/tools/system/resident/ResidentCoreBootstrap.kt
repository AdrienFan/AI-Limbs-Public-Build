package com.ai.assistance.operit.core.tools.system.resident

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bootstrap for an app_process that has no AMS ApplicationThread registration.
 * An explicit same-app broadcast delivers only an offer Binder. Credentials are fetched later
 * over Binder, where the Host verifies the Core's actual calling UID and PID. */
internal object ResidentCoreBootstrap {
    private const val DESCRIPTOR = "ai_limbs.resident.bootstrap.v1"
    private const val OFFER = IBinder.FIRST_CALL_TRANSACTION
    private const val FETCH = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val ERROR = IBinder.FIRST_CALL_TRANSACTION + 2

    fun requestBackend(context: Context, launchId: String, sessionId: String): Bundle {
        val done = CountDownLatch(1)
        val accepting = AtomicBoolean(true)
        val response = AtomicReference<Bundle?>(null)
        val failure = AtomicReference<Exception?>(null)
        val inbox = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != OFFER && code != ERROR) return super.onTransact(code, data, reply, flags)
                require(reply != null && flags and IBinder.FLAG_ONEWAY == 0) { "Bootstrap requires an acknowledgement" }
                check(Binder.getCallingUid() == Process.myUid()) { "Bootstrap offer UID mismatch" }
                data.enforceInterface(DESCRIPTOR)
                check(data.readString() == launchId && data.readString() == sessionId) { "Stale bootstrap offer" }
                check(accepting.compareAndSet(true, false)) { "Bootstrap offer expired or was already consumed" }
                try {
                    if (code == ERROR) error(requireNotNull(data.readString()))
                    val offer = requireNotNull(data.readStrongBinder())
                    val answer = transact(offer, FETCH, launchId, sessionId) { }
                    response.set(answer)
                    requireNotNull(reply).writeNoException()
                } catch (error: Exception) {
                    failure.set(error)
                    requireNotNull(reply).writeException(IllegalStateException(error.toString()))
                } finally {
                    done.countDown()
                }
                return true
            }
        }
        try {
            val intent = Intent(context.packageName + ".action.RESIDENT_CORE_BIND")
                .setComponent(ComponentName(context.packageName, ResidentCoreBootstrapReceiver::class.java.name))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtras(Bundle().apply {
                    putString("launch_id", launchId)
                    putString("session_id", sessionId)
                    putBinder("inbox", inbox)
                })
            broadcastFromStandaloneProcess(intent)
            check(done.await(6L, TimeUnit.SECONDS)) { "Host did not acknowledge Core bootstrap within 6000ms" }
            failure.get()?.let { throw it }
            return checkNotNull(response.get()) { "Core bootstrap returned no backend" }
        } finally {
            accepting.set(false)
        }
    }

    /** Runs on the receiver worker, with the pending broadcast kept alive by goAsync(). */
    fun offerBackend(context: Context, intent: Intent) {
        val input = requireNotNull(intent.extras)
        val launch = requireNotNull(input.getString("launch_id"))
        val session = requireNotNull(input.getString("session_id"))
        val inbox = requireNotNull(input.getBinder("inbox"))
        require(launch.length in 1..64 && session.length in 1..64)
        try {
            requireLaunch(context, launch)
            val core = ResidentCoreController.requestCore(context, "status", session)
            val corePid = core.getInt("pid")
            check(corePid != Process.myPid() && core.getString("launch_id") == launch) { "Bootstrap Core identity mismatch" }
            val used = AtomicBoolean(false)
            val offer = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != FETCH) return super.onTransact(code, data, reply, flags)
                    require(reply != null && flags and IBinder.FLAG_ONEWAY == 0) { "Bootstrap fetch requires an acknowledgement" }
                    check(Binder.getCallingUid() == Process.myUid() && Binder.getCallingPid() == corePid) {
                        "Only the socket-authenticated Core may fetch the permission backend"
                    }
                    data.enforceInterface(DESCRIPTOR)
                    check(data.readString() == launch && data.readString() == session)
                    check(used.compareAndSet(false, true)) { "Core bootstrap offer was already consumed" }
                    requireLaunch(context, launch)
                    PrivilegeRuntime.initialize(context)
                    val result = PrivilegeRuntime.exportResidentConnection().apply {
                        putString("launch_id", launch)
                        putString("session_id", session)
                    }
                    requireNotNull(reply).writeNoException()
                    reply.writeBundle(result)
                    return true
                }
            }
            transact(inbox, OFFER, launch, session) { writeStrongBinder(offer) }
        } catch (error: Exception) {
            try { transact(inbox, ERROR, launch, session) { writeString(error.toString().take(512)) } }
            catch (deliveryError: Exception) { System.err.println("Core bootstrap rejection could not be delivered: $deliveryError") }
            throw error
        }
    }

    private fun requireLaunch(context: Context, launch: String) {
        val request = File(context.filesDir, "ai_limbs/resident_core/launch.request")
        check(request.isFile && request.length() in 1L..64L && request.readText() == launch) {
            "Core launch permission was revoked"
        }
    }

    private fun transact(
        endpoint: IBinder, code: Int, launch: String, session: String, payload: Parcel.() -> Unit
    ): Bundle {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeString(launch)
            request.writeString(session)
            request.payload()
            check(endpoint.transact(code, request, reply, 0)) { "Core bootstrap transaction was rejected" }
            reply.readException()
            return if (code == FETCH) requireNotNull(reply.readBundle(ResidentCoreBootstrap::class.java.classLoader)) else Bundle.EMPTY
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private fun broadcastFromStandaloneProcess(intent: Intent) {
        val activityManager = Class.forName("android.app.ActivityManager")
            .getDeclaredMethod("getService").invoke(null)
        val contract = Class.forName("android.app.IActivityManager")
        val name = if (Build.VERSION.SDK_INT >= 30) "broadcastIntentWithFeature" else "broadcastIntent"
        val method = contract.methods.single { it.name == name }
        val applicationThread = Class.forName("android.app.IApplicationThread")
        val intentReceiver = Class.forName("android.content.IIntentReceiver")
        val intType = Int::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!
        val types = mutableListOf<Class<*>>(applicationThread)
        val arguments = mutableListOf<Any?>(null)
        if (Build.VERSION.SDK_INT >= 30) { types += String::class.java; arguments.add(null) }
        types.addAll(listOf(Intent::class.java, String::class.java, intentReceiver, intType,
            String::class.java, Bundle::class.java, Array<String>::class.java))
        arguments.addAll(listOf(intent, null, null, 0, null, null, null))
        // AOSP added excluded-permission and excluded-package arrays over successive releases.
        // Select the observed ABI once; never retry a rejected broadcast with different privileges.
        val filters = method.parameterTypes.size - types.size - 5
        require(filters in 0..2 && (Build.VERSION.SDK_INT >= 30 || filters == 0)) { "Unsupported broadcast ABI" }
        repeat(filters) { types += Array<String>::class.java; arguments.add(null) }
        types.addAll(listOf(intType, Bundle::class.java, booleanType, booleanType, intType))
        arguments.addAll(listOf(-1, null, false, false, Process.myUid() / 100000))
        check(method.parameterTypes.contentEquals(types.toTypedArray())) { "Unexpected broadcast signature" }
        val result = try { method.invoke(activityManager, *arguments.toTypedArray()) as Int }
        catch (error: InvocationTargetException) { throw error.targetException }
        check(result == 0) { "Core bootstrap broadcast rejected: $result" }
    }
}
