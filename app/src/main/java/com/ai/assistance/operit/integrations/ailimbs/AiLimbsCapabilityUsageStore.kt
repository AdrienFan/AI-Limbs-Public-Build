package com.ai.assistance.operit.integrations.ailimbs

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

internal data class AiLimbsCapabilityUsageStats(
    val invokeId: String,
    val useCount: Long = 0L,
    val lastUsedAtEpochMs: Long? = null
)

/**
 * Durable capability-level execution counters.
 *
 * The canonical invoke_id is the storage key. Capability metadata remains registry-owned and is
 * resolved from the current catalog when the data is consumed. SQLite is used so Legacy Host and
 * Resident Core can safely hand off BUSINESS ownership without process-local preference caches
 * overwriting newer counters.
 */
internal class AiLimbsCapabilityUsageStore(context: Context) {
    private val database = CapabilityUsageDatabase(context.applicationContext)

    fun recordSuccessfulExecution(
        invocation: AiLimbsNormalizedInvocation,
        result: JSONObject,
        atEpochMs: Long = System.currentTimeMillis()
    ): AiLimbsCapabilityUsageStats? {
        val invokeId = AiLimbsCapabilityUsagePolicy.trackedInvokeId(invocation, result) ?: return null
        return recordSuccess(invokeId, atEpochMs)
    }

    fun recordSuccess(
        invokeId: String,
        atEpochMs: Long = System.currentTimeMillis()
    ): AiLimbsCapabilityUsageStats {
        val id = invokeId.trim()
        if (id.isEmpty()) return AiLimbsCapabilityUsageStats(invokeId = "")

        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            val seed =
                ContentValues().apply {
                    put(COLUMN_INVOKE_ID, id)
                    put(COLUMN_USE_COUNT, 0L)
                    put(COLUMN_LAST_USED_AT_MS, 0L)
                }
            db.insertWithOnConflict(TABLE_USAGE, null, seed, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL(
                """
                UPDATE $TABLE_USAGE
                SET $COLUMN_USE_COUNT =
                    CASE
                        WHEN $COLUMN_USE_COUNT < ? THEN $COLUMN_USE_COUNT + 1
                        ELSE $COLUMN_USE_COUNT
                    END,
                    $COLUMN_LAST_USED_AT_MS = ?
                WHERE $COLUMN_INVOKE_ID = ?
                """.trimIndent(),
                arrayOf(Long.MAX_VALUE, atEpochMs, id)
            )
            val count =
                DatabaseUtils.longForQuery(
                    db,
                    "SELECT $COLUMN_USE_COUNT FROM $TABLE_USAGE WHERE $COLUMN_INVOKE_ID = ?",
                    arrayOf(id)
                )
            db.setTransactionSuccessful()
            AiLimbsCapabilityUsageStats(
                invokeId = id,
                useCount = count,
                lastUsedAtEpochMs = atEpochMs
            )
        } finally {
            db.endTransaction()
        }
    }

    fun snapshot(invokeId: String): AiLimbsCapabilityUsageStats {
        val id = invokeId.trim()
        if (id.isEmpty()) return AiLimbsCapabilityUsageStats(invokeId = "")

        val db = database.readableDatabase
        db.query(
            TABLE_USAGE,
            arrayOf(COLUMN_USE_COUNT, COLUMN_LAST_USED_AT_MS),
            "$COLUMN_INVOKE_ID = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return AiLimbsCapabilityUsageStats(invokeId = id)
            return AiLimbsCapabilityUsageStats(
                invokeId = id,
                useCount = cursor.getLong(0),
                lastUsedAtEpochMs = cursor.getLong(1).takeIf { it > 0L }
            )
        }
    }

    fun snapshots(): List<AiLimbsCapabilityUsageStats> {
        val db = database.readableDatabase
        return db.query(
            TABLE_USAGE,
            arrayOf(COLUMN_INVOKE_ID, COLUMN_USE_COUNT, COLUMN_LAST_USED_AT_MS),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        AiLimbsCapabilityUsageStats(
                            invokeId = cursor.getString(0),
                            useCount = cursor.getLong(1),
                            lastUsedAtEpochMs = cursor.getLong(2).takeIf { it > 0L }
                        )
                    )
                }
            }
        }
    }

    private class CapabilityUsageDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_USAGE (
                    $COLUMN_INVOKE_ID TEXT PRIMARY KEY NOT NULL,
                    $COLUMN_USE_COUNT INTEGER NOT NULL DEFAULT 0,
                    $COLUMN_LAST_USED_AT_MS INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "ai_limbs_capability_usage_v1.db"
        const val DATABASE_VERSION = 1
        const val TABLE_USAGE = "capability_usage"
        const val COLUMN_INVOKE_ID = "invoke_id"
        const val COLUMN_USE_COUNT = "use_count"
        const val COLUMN_LAST_USED_AT_MS = "last_used_at_ms"
    }
}

internal object AiLimbsCapabilityUsagePolicy {
    fun trackedInvokeId(
        invocation: AiLimbsNormalizedInvocation,
        result: JSONObject
    ): String? {
        if (result.has("success") && !result.optBoolean("success", false)) return null

        val invokeId =
            when (val route = invocation.route) {
                is AiLimbsCapabilityRoute.Plugin ->
                    route.registration.catalogEntry.targetToolName
                is AiLimbsCapabilityRoute.HostTool ->
                    route.targetName
                is AiLimbsCapabilityRoute.Core ->
                    return null
            }.trim()

        if (invokeId.isEmpty() || invokeId in CONTROL_PLANE_FORWARDED_CAPABILITIES) return null
        return invokeId
    }

    private val CONTROL_PLANE_FORWARDED_CAPABILITIES =
        setOf(
            "ai_limbs.bridge.reconnect"
        )
}
