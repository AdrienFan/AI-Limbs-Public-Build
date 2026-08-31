package com.ai.assistance.operit.plugins.center

import android.content.Context

data class PluginUsageStats(
    val useCount: Long = 0L,
    val lastUsedAtEpochMs: Long? = null
)

enum class InactivityThresholdMode {
    DAYS,
    TEST_SECONDS
}

data class PluginInactivityPolicySnapshot(
    val enabled: Boolean,
    val mode: InactivityThresholdMode,
    val days: Int,
    val testSeconds: Int,
    val enabledAtEpochMs: Long
) {
    val thresholdMillis: Long
        get() = when (mode) {
            InactivityThresholdMode.DAYS -> days * 86_400_000L
            InactivityThresholdMode.TEST_SECONDS -> testSeconds * 1_000L
        }
}

class PluginUsageStore(context: Context) {
    private val prefs = context.getSharedPreferences("plugin_usage_stats_v1", Context.MODE_PRIVATE)
    private val lock = Any()

    fun recordUse(pluginId: String, atEpochMs: Long = System.currentTimeMillis()) {
        val id = pluginId.trim()
        if (id.isEmpty()) return
        synchronized(lock) {
            val countKey = "count:$id"
            prefs.edit()
                .putLong(countKey, prefs.getLong(countKey, 0L) + 1L)
                .putLong("last:$id", atEpochMs)
                .apply()
        }
    }

    fun markEnabled(pluginId: String, atEpochMs: Long = System.currentTimeMillis()) {
        val id = pluginId.trim()
        if (id.isNotEmpty()) prefs.edit().putLong("enabled_since:$id", atEpochMs).apply()
    }

    fun enabledSince(pluginId: String): Long? =
        prefs.getLong("enabled_since:${pluginId.trim()}", 0L).takeIf { it > 0L }

    fun snapshot(pluginId: String): PluginUsageStats {
        val id = pluginId.trim()
        val last = prefs.getLong("last:$id", 0L).takeIf { it > 0L }
        return PluginUsageStats(
            useCount = prefs.getLong("count:$id", 0L),
            lastUsedAtEpochMs = last
        )
    }
}

class PluginInactivityPolicyStore(context: Context) {
    private val prefs = context.getSharedPreferences("plugin_inactivity_policy_v1", Context.MODE_PRIVATE)

    fun snapshot(): PluginInactivityPolicySnapshot {
        val mode = runCatching {
            InactivityThresholdMode.valueOf(prefs.getString("mode", InactivityThresholdMode.DAYS.name)!!)
        }.getOrDefault(InactivityThresholdMode.DAYS)
        return PluginInactivityPolicySnapshot(
            enabled = prefs.getBoolean("enabled", false),
            mode = mode,
            days = prefs.getInt("days", DEFAULT_DAYS).coerceIn(MIN_DAYS, MAX_DAYS),
            testSeconds = prefs.getInt("test_seconds", DEFAULT_TEST_SECONDS).coerceIn(MIN_TEST_SECONDS, MAX_TEST_SECONDS),
            enabledAtEpochMs = prefs.getLong("enabled_at", 0L)
        )
    }

    fun configure(enabled: Boolean, mode: InactivityThresholdMode, days: Int, testSeconds: Int) {
        require(days in MIN_DAYS..MAX_DAYS) { "未使用天数必须在 $MIN_DAYS-$MAX_DAYS 天之间" }
        require(testSeconds in MIN_TEST_SECONDS..MAX_TEST_SECONDS) { "测试秒数必须在 $MIN_TEST_SECONDS-$MAX_TEST_SECONDS 秒之间" }
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putString("mode", mode.name)
            .putInt("days", days)
            .putInt("test_seconds", testSeconds)
            .putLong("enabled_at", if (enabled) System.currentTimeMillis() else 0L)
            .apply()
    }

    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 3650
        const val DEFAULT_DAYS = 30
        const val MIN_TEST_SECONDS = 5
        const val MAX_TEST_SECONDS = 3600
        const val DEFAULT_TEST_SECONDS = 5
    }
}
