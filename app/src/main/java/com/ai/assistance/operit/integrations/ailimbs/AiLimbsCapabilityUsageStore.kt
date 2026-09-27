package com.ai.assistance.operit.integrations.ailimbs

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.pow
import org.json.JSONObject

internal data class AiLimbsCapabilityUsageStats(
    val invokeId: String,
    val useCount: Long = 0L,
    val lastUsedAtEpochMs: Long? = null
)

internal data class AiLimbsCapabilityUsageBucket(
    val invokeId: String,
    val dayStartEpochMs: Long,
    val successCount: Long,
    val lastUsedAtEpochMs: Long
)

/**
 * Durable capability-level execution history.
 *
 * Aggregate counters are permanent audit facts. Daily buckets are the re-rankable history used by
 * the hot list, allowing the Host to change the decay half-life without rewriting usage history.
 * SQLite keeps Legacy Host and Resident Core process handoff safe.
 */
internal class AiLimbsCapabilityUsageStore(context: Context) {
    private val database = CapabilityUsageDatabase(context.applicationContext)

    fun recordSuccessfulExecution(
        invocation: AiLimbsNormalizedInvocation,
        result: JSONObject,
        atEpochMs: Long = System.currentTimeMillis()
    ): AiLimbsCapabilityUsageStats? {
        val invokeId =
            AiLimbsCapabilityUsagePolicy.trackedInvokeId(invocation, result)
                ?: return null
        return recordSuccess(invokeId, atEpochMs)
    }

    fun recordSuccess(
        invokeId: String,
        atEpochMs: Long = System.currentTimeMillis()
    ): AiLimbsCapabilityUsageStats {
        val id = invokeId.trim()
        if (id.isEmpty()) return AiLimbsCapabilityUsageStats(invokeId = "")
        val dayStartEpochMs = utcDayStart(atEpochMs)

        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            seedUsage(db, id)
            db.execSQL(
                """
                UPDATE $TABLE_USAGE
                SET $COLUMN_USE_COUNT =
                    CASE
                        WHEN $COLUMN_USE_COUNT < ? THEN $COLUMN_USE_COUNT + 1
                        ELSE $COLUMN_USE_COUNT
                    END,
                    $COLUMN_LAST_USED_AT_MS =
                    CASE
                        WHEN $COLUMN_LAST_USED_AT_MS < ? THEN ?
                        ELSE $COLUMN_LAST_USED_AT_MS
                    END
                WHERE $COLUMN_INVOKE_ID = ?
                """.trimIndent(),
                arrayOf(Long.MAX_VALUE, atEpochMs, atEpochMs, id)
            )

            seedDailyBucket(db, id, dayStartEpochMs)
            db.execSQL(
                """
                UPDATE $TABLE_DAILY
                SET $COLUMN_SUCCESS_COUNT =
                    CASE
                        WHEN $COLUMN_SUCCESS_COUNT < ? THEN $COLUMN_SUCCESS_COUNT + 1
                        ELSE $COLUMN_SUCCESS_COUNT
                    END,
                    $COLUMN_LAST_USED_AT_MS =
                    CASE
                        WHEN $COLUMN_LAST_USED_AT_MS < ? THEN ?
                        ELSE $COLUMN_LAST_USED_AT_MS
                    END
                WHERE $COLUMN_INVOKE_ID = ? AND $COLUMN_DAY_START_MS = ?
                """.trimIndent(),
                arrayOf(
                    Long.MAX_VALUE,
                    atEpochMs,
                    atEpochMs,
                    id,
                    dayStartEpochMs
                )
            )

            val count =
                DatabaseUtils.longForQuery(
                    db,
                    "SELECT $COLUMN_USE_COUNT FROM $TABLE_USAGE WHERE $COLUMN_INVOKE_ID = ?",
                    arrayOf(id)
                )
            val lastUsed =
                DatabaseUtils.longForQuery(
                    db,
                    "SELECT $COLUMN_LAST_USED_AT_MS FROM $TABLE_USAGE WHERE $COLUMN_INVOKE_ID = ?",
                    arrayOf(id)
                )
            db.setTransactionSuccessful()
            AiLimbsCapabilityUsageStats(
                invokeId = id,
                useCount = count,
                lastUsedAtEpochMs = lastUsed.takeIf { it > 0L }
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
            if (!cursor.moveToFirst()) {
                return AiLimbsCapabilityUsageStats(invokeId = id)
            }
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
                            lastUsedAtEpochMs =
                                cursor.getLong(2).takeIf { it > 0L }
                        )
                    )
                }
            }
        }
    }

    fun usageBuckets(): List<AiLimbsCapabilityUsageBucket> {
        val db = database.readableDatabase
        return db.query(
            TABLE_DAILY,
            arrayOf(
                COLUMN_INVOKE_ID,
                COLUMN_DAY_START_MS,
                COLUMN_SUCCESS_COUNT,
                COLUMN_LAST_USED_AT_MS
            ),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        AiLimbsCapabilityUsageBucket(
                            invokeId = cursor.getString(0),
                            dayStartEpochMs = cursor.getLong(1),
                            successCount = cursor.getLong(2),
                            lastUsedAtEpochMs = cursor.getLong(3)
                        )
                    )
                }
            }
        }
    }

    private fun seedUsage(db: SQLiteDatabase, invokeId: String) {
        val values =
            ContentValues().apply {
                put(COLUMN_INVOKE_ID, invokeId)
                put(COLUMN_USE_COUNT, 0L)
                put(COLUMN_LAST_USED_AT_MS, 0L)
            }
        db.insertWithOnConflict(
            TABLE_USAGE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun seedDailyBucket(
        db: SQLiteDatabase,
        invokeId: String,
        dayStartEpochMs: Long
    ) {
        val values =
            ContentValues().apply {
                put(COLUMN_INVOKE_ID, invokeId)
                put(COLUMN_DAY_START_MS, dayStartEpochMs)
                put(COLUMN_SUCCESS_COUNT, 0L)
                put(COLUMN_LAST_USED_AT_MS, 0L)
            }
        db.insertWithOnConflict(
            TABLE_DAILY,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun utcDayStart(atEpochMs: Long): Long =
        atEpochMs - Math.floorMod(atEpochMs, MILLIS_PER_DAY)

    private class CapabilityUsageDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            createUsageTable(db)
            createDailyTable(db)
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            if (oldVersion < 2) {
                createDailyTable(db)
            }
        }

        private fun createUsageTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_USAGE (
                    $COLUMN_INVOKE_ID TEXT PRIMARY KEY NOT NULL,
                    $COLUMN_USE_COUNT INTEGER NOT NULL DEFAULT 0,
                    $COLUMN_LAST_USED_AT_MS INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        private fun createDailyTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DAILY (
                    $COLUMN_INVOKE_ID TEXT NOT NULL,
                    $COLUMN_DAY_START_MS INTEGER NOT NULL,
                    $COLUMN_SUCCESS_COUNT INTEGER NOT NULL DEFAULT 0,
                    $COLUMN_LAST_USED_AT_MS INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY ($COLUMN_INVOKE_ID, $COLUMN_DAY_START_MS)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS $INDEX_DAILY_INVOKE
                ON $TABLE_DAILY ($COLUMN_INVOKE_ID)
                """.trimIndent()
            )
        }
    }

    private companion object {
        const val DATABASE_NAME = "ai_limbs_capability_usage_v1.db"
        const val DATABASE_VERSION = 2
        const val TABLE_USAGE = "capability_usage"
        const val TABLE_DAILY = "capability_usage_daily"
        const val INDEX_DAILY_INVOKE = "idx_capability_usage_daily_invoke"
        const val COLUMN_INVOKE_ID = "invoke_id"
        const val COLUMN_USE_COUNT = "use_count"
        const val COLUMN_LAST_USED_AT_MS = "last_used_at_ms"
        const val COLUMN_DAY_START_MS = "day_start_ms"
        const val COLUMN_SUCCESS_COUNT = "success_count"
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

internal data class AiLimbsDiscoverableCapability(
    val capabilityId: String,
    val displayName: String,
    val invokeId: String,
    val role: AiLimbsCapabilityRole = AiLimbsCapabilityRole.BUSINESS
)

internal data class AiLimbsHotCapability(
    val capabilityId: String,
    val displayName: String,
    val invokeId: String,
    val useCount: Long,
    val heatScore: Double,
    val lastUsedAtEpochMs: Long?
)

internal object AiLimbsHotCapabilityRanker {
    fun rank(
        usageStats: List<AiLimbsCapabilityUsageStats>,
        usageBuckets: List<AiLimbsCapabilityUsageBucket>,
        discoverableCapabilities: List<AiLimbsDiscoverableCapability>,
        halfLifeMs: Double,
        nowEpochMs: Long = System.currentTimeMillis(),
        limit: Int
    ): List<AiLimbsHotCapability> {
        if (
            limit <= 0 ||
                usageBuckets.isEmpty() ||
                discoverableCapabilities.isEmpty() ||
                !halfLifeMs.isFinite() ||
                halfLifeMs <= 0.0
        ) {
            return emptyList()
        }

        val statsByInvokeId =
            usageStats
                .distinctBy { it.invokeId }
                .associateBy { it.invokeId }
        val heatByInvokeId = linkedMapOf<String, Double>()

        usageBuckets.forEach { bucket ->
            if (bucket.successCount <= 0L) return@forEach
            val ageMs =
                (nowEpochMs - bucket.lastUsedAtEpochMs)
                    .coerceAtLeast(0L)
                    .toDouble()
            val weight = 0.5.pow(ageMs / halfLifeMs)
            heatByInvokeId[bucket.invokeId] =
                (heatByInvokeId[bucket.invokeId] ?: 0.0) +
                    bucket.successCount.toDouble() * weight
        }

        return discoverableCapabilities
            .asSequence()
            .filter { it.role == AiLimbsCapabilityRole.BUSINESS }
            .distinctBy { it.invokeId }
            .mapNotNull { capability ->
                val heat = heatByInvokeId[capability.invokeId] ?: return@mapNotNull null
                if (heat <= 0.0) return@mapNotNull null
                val stats = statsByInvokeId[capability.invokeId]
                AiLimbsHotCapability(
                    capabilityId = capability.capabilityId,
                    displayName = capability.displayName,
                    invokeId = capability.invokeId,
                    useCount = stats?.useCount ?: 0L,
                    heatScore = heat,
                    lastUsedAtEpochMs = stats?.lastUsedAtEpochMs
                )
            }
            .sortedWith(
                compareByDescending<AiLimbsHotCapability> { it.heatScore }
                    .thenByDescending {
                        it.lastUsedAtEpochMs ?: Long.MIN_VALUE
                    }
                    .thenBy { it.invokeId }
            )
            .take(limit)
            .toList()
    }
}

internal object AiLimbsCapabilityUsagePolicy {
    fun trackedInvokeId(
        invocation: AiLimbsNormalizedInvocation,
        result: JSONObject
    ): String? {
        if (result.has("success") && !result.optBoolean("success", false)) {
            return null
        }

        val invokeId =
            when (val route = invocation.route) {
                is AiLimbsCapabilityRoute.Plugin ->
                    route.registration.catalogEntry.targetToolName
                is AiLimbsCapabilityRoute.HostTool ->
                    route.targetName
                is AiLimbsCapabilityRoute.Core ->
                    return null
            }.trim()

        if (
            invokeId.isEmpty() ||
                invokeId in CONTROL_PLANE_FORWARDED_CAPABILITIES
        ) {
            return null
        }
        return invokeId
    }

    private val CONTROL_PLANE_FORWARDED_CAPABILITIES =
        setOf("ai_limbs.bridge.reconnect")
}
