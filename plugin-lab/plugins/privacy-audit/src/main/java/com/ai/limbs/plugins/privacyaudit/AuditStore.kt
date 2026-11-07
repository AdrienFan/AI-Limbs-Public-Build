package com.ai.limbs.plugins.privacyaudit

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Plugin-private durable state, including explicit coverage failures. No cloud SDK. */
internal class AuditStore(dir: File) : AutoCloseable {
    private val db: SQLiteDatabase
    init {
        dir.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(File(dir, "privacy-audit-v1.db"), null)
        // WAL is configured through Android's supported API, not execSQL(PRAGMA ...).
        db.enableWriteAheadLogging()
        // This PRAGMA returns a row: use a query and verify privacy-safe deletion is enabled.
        db.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                "Unable to enable SQLite secure_delete for the local audit database"
            }
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS apps(k TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL, prev TEXT NOT NULL, hash TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS events_at ON events(at)")
    }
    @Synchronized fun get(key: String): String? = db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    @Synchronized fun put(key: String, value: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply {put("k",key);put("v",value)}, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun app(key: String): JSONObject? = db.rawQuery("SELECT body FROM apps WHERE k=?", arrayOf(key)).use {
        if(it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    @Synchronized fun apps(): List<JSONObject> = db.rawQuery("SELECT body FROM apps ORDER BY k", null).use {
        buildList {while(it.moveToNext()) add(JSONObject(it.getString(0)))}
    }
    @Synchronized fun saveApp(value: JSONObject) {
        db.insertWithOnConflict("apps", null, ContentValues().apply {put("k",value.getString("key"));put("body",value.toString())}, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun event(kind: String, body: JSONObject, at: Long = System.currentTimeMillis()) {
        db.beginTransaction()
        try {
            val previous=db.rawQuery("SELECT hash FROM events ORDER BY seq DESC LIMIT 1",null).use {if(it.moveToFirst()) it.getString(0) else get("anchor").orEmpty()}
            val data=body.toString()
            db.insertOrThrow("events",null,ContentValues().apply {
                put("at",at);put("kind",kind);put("body",data);put("prev",previous);put("hash",hash(previous,at,kind,data))
            })
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun history(limit: Int, before: Long = Long.MAX_VALUE): JSONObject {
        val list=JSONArray()
        db.rawQuery("SELECT seq,at,kind,body,prev,hash FROM events WHERE seq<? ORDER BY seq DESC LIMIT ?",arrayOf(before.toString(),limit.coerceIn(1,200).toString())).use {
            while(it.moveToNext()) list.put(JSONObject().put("seq",it.getLong(0)).put("at",it.getLong(1)).put("kind",it.getString(2))
                .put("data",JSONObject(it.getString(3))).put("prev",it.getString(4)).put("hash",it.getString(5)))
        }
        return JSONObject().put("events",list).put("next_before",if(list.length()>0) list.getJSONObject(list.length()-1).getLong("seq") else JSONObject.NULL)
    }
    @Synchronized fun verify(): JSONObject {
        var previous=get("anchor").orEmpty();var count=0
        db.rawQuery("SELECT seq,at,kind,body,prev,hash FROM events ORDER BY seq",null).use {
            while(it.moveToNext()) {
                if(it.getString(4)!=previous || it.getString(5)!=hash(previous,it.getLong(1),it.getString(2),it.getString(3)))
                    return JSONObject().put("valid",false).put("failed_seq",it.getLong(0))
                previous=it.getString(5);count++
            }
        }
        return JSONObject().put("valid",true).put("records",count).put("head",previous)
            .put("scope","仅验证保留区间的本地哈希链；持有数据库写权限者可重写整条链，不能证明未被篡改")
    }
    @Synchronized fun prune(days: Int) {
        val cutoff=System.currentTimeMillis()-days.coerceIn(7,90)*86_400_000L
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events WHERE at<? OR seq NOT IN (SELECT seq FROM events ORDER BY seq DESC LIMIT 5000)",arrayOf(cutoff))
            val anchor=db.rawQuery("SELECT prev FROM events ORDER BY seq LIMIT 1",null).use {if(it.moveToFirst())it.getString(0) else ""}
            put("anchor",anchor)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun clearHistory() {
        db.beginTransaction()
        try {db.delete("events",null,null);put("anchor","");db.setTransactionSuccessful()}finally{db.endTransaction()}
        event("history_cleared",JSONObject().put("reason","用户主动清除；原证据区间已不保留"))
    }
    @Synchronized override fun close() = db.close()
    private fun hash(previous: String, at: Long, kind: String, body: String): String =
        MessageDigest.getInstance("SHA-256").digest("$previous\n$at\n$kind\n$body".toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
}

internal object AuditJson {
    fun detail(d: AuditCore.Detail): JSONObject = JSONObject().put("version",d.version).put("system",d.system)
        .put("installed",d.installed).put("suspended",d.suspended).put("hidden",d.hidden).put("enabled",d.enabled)
        .put("requested",JSONArray(d.requested.toList())).put("granted",JSONArray(d.granted.toList())).put("protected_roles",JSONArray(d.protectedRoles.toList()))
    fun detail(j: JSONObject): AuditCore.Detail = AuditCore.Detail().apply {
        version=j.getString("version");system=j.getBoolean("system");installed=j.getBoolean("installed");suspended=j.getBoolean("suspended")
        hidden=j.getBoolean("hidden");enabled=j.getInt("enabled")
        requested.addAll(strings(j.getJSONArray("requested")));granted.addAll(strings(j.getJSONArray("granted")));protectedRoles.addAll(strings(j.getJSONArray("protected_roles")))
    }
    fun strings(a: JSONArray): List<String> = (0 until a.length()).map(a::getString)
    fun traffic(t: AuditCore.Traffic): JSONObject = JSONObject().put("rx",t.rx).put("tx",t.tx).put("bg_rx",t.bgRx).put("bg_tx",t.bgTx)
    fun traffic(j: JSONObject): AuditCore.Traffic = AuditCore.Traffic(j.getLong("rx"),j.getLong("tx"),j.getLong("bg_rx"),j.getLong("bg_tx"))
    fun app(j: JSONObject): AuditCore.App = AuditCore.App(j.getString("package"),j.getInt("user"),j.getInt("uid")).apply {
        launcher=if(j.isNull("launcher"))null else j.getBoolean("launcher");backgroundObserved=j.optBoolean("background_observed");foregroundObserved=j.optBoolean("foreground_observed")
    }
}
