package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context

internal data class AiLimbsHotRankingPolicy(
    val halfLifeDays: Double
) {
    val halfLifeMs: Double
        get() = halfLifeDays * MILLIS_PER_DAY

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}

/**
 * Host-owned source for hot-ranking policy. The Console may bind to this source later;
 * Resolver/Ranker only consume it and never hard-code the decay period.
 */
internal class AiLimbsHotRankingPolicySource(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun current(): AiLimbsHotRankingPolicy {
        val configured =
            prefs.getString(KEY_HALF_LIFE_DAYS, null)
                ?.toDoubleOrNull()
                ?.takeIf(::validHalfLifeDays)
                ?: DEFAULT_HALF_LIFE_DAYS
        return AiLimbsHotRankingPolicy(configured)
    }

    internal fun setHalfLifeDays(days: Double) {
        require(validHalfLifeDays(days)) {
            "Hot ranking half-life must be finite and within $MIN_HALF_LIFE_DAYS..$MAX_HALF_LIFE_DAYS days"
        }
        prefs.edit()
            .putString(KEY_HALF_LIFE_DAYS, days.toString())
            .apply()
    }

    private fun validHalfLifeDays(days: Double): Boolean =
        days.isFinite() &&
            days >= MIN_HALF_LIFE_DAYS &&
            days <= MAX_HALF_LIFE_DAYS

    companion object {
        const val SOURCE_ID = "host:ai_limbs.hot_ranking.half_life_days"
        const val DEFAULT_HALF_LIFE_DAYS = 7.0
        const val MIN_HALF_LIFE_DAYS = 0.25
        const val MAX_HALF_LIFE_DAYS = 3650.0

        private const val PREFS_NAME = "ai_limbs_hot_ranking_policy_v1"
        private const val KEY_HALF_LIFE_DAYS = "half_life_days"
    }
}
