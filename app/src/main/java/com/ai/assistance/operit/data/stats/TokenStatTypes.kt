package com.ai.assistance.operit.data.stats

/**
 * 事件业务分类（阶段 1 固定契约；统计页默认包含全部分类并允许筛选）。
 * 所有实际模型调用都应落入其中一种，包括连接测试等探测调用。
 */
enum class TokenStatCategory {
    CHAT,
    SUBAGENT,
    SUMMARY,
    TITLE,
    MEMORY,
    CHARACTER_GENERATION,
    CONNECTION_TEST,
    OTHER;

    companion object {
        fun fromName(name: String?): TokenStatCategory =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** 事件结束状态：正常完成、取消、超时、失败。 */
enum class TokenStatStatus {
    COMPLETED,
    CANCELLED,
    TIMEOUT,
    FAILED;

    companion object {
        fun fromName(name: String?): TokenStatStatus =
            entries.firstOrNull { it.name == name } ?: FAILED
    }
}

/** Price-resolution provenance kept for calculation tests and diagnostics. */
enum class PricingSource { BUILT_IN, USER, UNKNOWN }
