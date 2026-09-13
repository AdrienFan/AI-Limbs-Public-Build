package com.ai.assistance.operit.core.tools.system.privilege

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Static Android shell endpoint; runtime and UI remain independently packaged. */
class PrivilegeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        PrivilegeRuntime.initialize(requireNotNull(context))
        return true
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "attach") { "Unsupported privilege handoff" }
        val data = requireNotNull(extras)
        val accepted = PrivilegeRuntime.receive(
            Binder.getCallingUid(), requireNotNull(data.getString("token")),
            requireNotNull(data.getBinder("binder"))
        )
        return Bundle().apply { putBoolean("accepted", accepted) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
