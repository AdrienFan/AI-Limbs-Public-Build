package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.core.tools.system.shell.ShellProcess
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences

/** 向后兼容的Shell命令执行工具类 通过权限级别委托到相应的Shell执行器 */
class AndroidShellExecutor {
    companion object {
        private const val TAG = "AndroidShellExecutor"
        private var context: Context? = null
        private val preferredPermissionLevelCacheLock = Any()
        @Volatile private var hasCachedPreferredPermissionLevel = false
        @Volatile private var cachedPreferredPermissionLevel: AndroidPermissionLevel? = null

        /**
         * 设置全局上下文引用
         * @param appContext 应用上下文
         */
        fun setContext(appContext: Context) {
            context = appContext.applicationContext
        }

        fun clearPreferredPermissionLevelCache() {
            synchronized(preferredPermissionLevelCacheLock) {
                cachedPreferredPermissionLevel = null
                hasCachedPreferredPermissionLevel = false
            }
        }

        private fun getPreferredPermissionLevelCached(): AndroidPermissionLevel? {
            if (hasCachedPreferredPermissionLevel) {
                return cachedPreferredPermissionLevel
            }

            synchronized(preferredPermissionLevelCacheLock) {
                if (!hasCachedPreferredPermissionLevel) {
                    cachedPreferredPermissionLevel =
                            androidPermissionPreferences.getPreferredPermissionLevel()
                    hasCachedPreferredPermissionLevel = true
                }
                return cachedPreferredPermissionLevel
            }
        }

        private fun getPermissionLevelLabel(level: AndroidPermissionLevel): String {
            return when (level) {
                AndroidPermissionLevel.STANDARD -> "STANDARD"
                AndroidPermissionLevel.ACCESSIBILITY -> "ACCESSIBILITY"
                AndroidPermissionLevel.DEBUGGER -> "DEBUGGER"
                AndroidPermissionLevel.ADMIN -> "ADMIN"
                AndroidPermissionLevel.ROOT -> "ROOT"
            }
        }

        private fun buildStrictUnavailableReason(
            level: AndroidPermissionLevel,
            executorAvailable: Boolean,
            permStatus: ShellExecutor.PermissionStatus
        ): String {
            val reasons = mutableListOf<String>()

            if (!executorAvailable) {
                reasons += "executor unavailable"
            }
            if (!permStatus.granted) {
                reasons += permStatus.reason.trim().ifEmpty { "permission not granted" }
            }

            val reasonText = reasons.distinct().joinToString("; ").ifBlank { "unknown reason" }
            return "Current ${getPermissionLevelLabel(level)} unavailable: $reasonText"
        }

        private fun isProviderTransportFailure(result: ShellExecutor.CommandResult): Boolean {
            if (result.success) return false
            val text = (result.stderr + "\n" + result.stdout).lowercase()
            return listOf(
                "service not available",
                "remote exception",
                "failed to create process",
                "dead object",
                "binder",
                "not connected",
                "connection reset",
                "connection refused"
            ).any { marker -> text.contains(marker) }
        }

        /**
         * 封装执行命令的函数
         * @param command 要执行的命令
         * @return 命令执行结果
         */
        suspend fun executeShellCommand(command: String): CommandResult {
            return executeShellCommand(command, null)
        }

        suspend fun executeShellCommand(command: String, identityOverride: ShellIdentity?): CommandResult {
            val ctx = context ?: return CommandResult(false, "", "Context not initialized")
            val identity = identityOverride ?: ShellIdentity.DEFAULT
            val policy = PermissionPolicyRuntime.read(ctx)
            val coexistEnabled = policy.coexistEnabled
            val levels =
                PermissionRoutingPolicy.shellCandidates(
                    coexistEnabled = coexistEnabled,
                    legacyPreferred = policy.legacyPreferred
                )

            val unavailableReasons = mutableListOf<String>()
            var lastInfrastructureFailure: CommandResult? = null

            for (level in levels) {
                val executor = ShellExecutorFactory.getExecutor(ctx, level)
                val permStatus = executor.hasPermission()
                val executorAvailable = executor.isAvailable()

                if (!executorAvailable || !permStatus.granted) {
                    unavailableReasons +=
                        buildStrictUnavailableReason(level, executorAvailable, permStatus)
                    continue
                }

                try {
                    val result = executor.executeCommand(command, identity)
                    val commandResult =
                        CommandResult(
                            result.success,
                            result.stdout,
                            result.stderr,
                            result.exitCode
                        )

                    if (
                        coexistEnabled &&
                        !result.success &&
                        isProviderTransportFailure(result)
                    ) {
                        lastInfrastructureFailure = commandResult
                        AppLogger.w(
                            TAG,
                            "Coexist shell provider $level failed at transport level; trying next provider"
                        )
                        continue
                    }

                    if (coexistEnabled) {
                        AppLogger.d(TAG, "Coexist shell command executed via $level")
                    }
                    return commandResult
                } catch (error: Exception) {
                    if (!coexistEnabled) {
                        return CommandResult(false, "", error.message ?: error.javaClass.simpleName, -1)
                    }
                    unavailableReasons +=
                        "$level execution exception: " +
                            (error.message ?: error.javaClass.simpleName)
                    AppLogger.w(TAG, "Coexist shell provider $level threw; trying next provider", error)
                }
            }

            lastInfrastructureFailure?.let { return it }

            val reason =
                unavailableReasons.distinct().joinToString(" | ")
                    .ifBlank { "No coexist shell provider is available" }
            return CommandResult(false, "", reason, -1)
        }

        suspend fun startShellProcess(command: String): ShellProcess {
            val ctx = context ?: throw IllegalStateException("Context not initialized")
            val policy = PermissionPolicyRuntime.read(ctx)
            val coexistEnabled = policy.coexistEnabled
            val levels =
                PermissionRoutingPolicy.shellCandidates(
                    coexistEnabled = coexistEnabled,
                    legacyPreferred = policy.legacyPreferred
                )

            val reasons = mutableListOf<String>()
            for (level in levels) {
                val executor = ShellExecutorFactory.getExecutor(ctx, level)
                val status = executor.hasPermission()
                val available = executor.isAvailable()
                if (!available || !status.granted) {
                    reasons += buildStrictUnavailableReason(level, available, status)
                    continue
                }
                try {
                    if (coexistEnabled) {
                        AppLogger.d(TAG, "Coexist shell process starting via $level")
                    }
                    return executor.startProcess(command)
                } catch (error: Exception) {
                    if (!coexistEnabled) throw error
                    reasons +=
                        "$level start exception: " +
                            (error.message ?: error.javaClass.simpleName)
                }
            }

            throw SecurityException(
                reasons.distinct().joinToString(" | ")
                    .ifBlank { "No coexist shell provider is available" }
            )
        }
    }

    /** 命令执行结果数据类 */
    data class CommandResult(
            val success: Boolean,
            val stdout: String,
            val stderr: String = "",
            val exitCode: Int = -1
    )
}

enum class ShellIdentity {
    DEFAULT,
    APP,
    ROOT,
    SHELL
}
