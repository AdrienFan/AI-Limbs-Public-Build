package com.ai.assistance.operit.data.mcp.plugins

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.systemenvironment.SystemEnvironmentClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MCP 共享系统环境会话管理器
 * 
 * 用于在 MCPStarter 和 MCPDeployer 之间共享同一个系统环境会话
 * 避免重复创建会话，提高资源利用效率
 */
object MCPSharedSession {
    
    private const val TAG = "MCPSharedSession"
    private const val SESSION_NAME = "mcp-shared"
    
    @Volatile
    private var sharedSessionId: String? = null
    private val mutex = Mutex()
    
    /**
     * 获取或创建共享的系统环境会话
     * 
     * @param context Android上下文
     * @return 会话ID，如果创建失败返回null
     */
    suspend fun getOrCreateSharedSession(context: Context): String? {
        // Always resolve by stable session name. The active System Environment provider reuses an
        // existing session and recreates it after provider/runtime restart, so Base never inspects
        // provider-private session state.
        return mutex.withLock {
            val sessionId = runCatching {
                SystemEnvironmentClient.createSession(SESSION_NAME)
            }.onFailure { error ->
                AppLogger.e(TAG, "Failed to resolve shared System Environment session", error)
            }.getOrNull()

            if (sessionId != null) {
                sharedSessionId = sessionId
                AppLogger.d(TAG, "Resolved shared MCP System Environment session: $sessionId")
            }
            sessionId
        }
    }
    
    /**
     * 获取当前共享会话ID（如果存在）
     */
    fun getCurrentSessionId(): String? = sharedSessionId
    
    /**
     * 清除共享会话引用
     * 注意：这不会实际关闭会话，只是清除引用
     */
    suspend fun clearSession() {
        mutex.withLock {
            AppLogger.d(TAG, "Clearing shared session reference: $sharedSessionId")
            sharedSessionId = null
        }
    }
    
    /**
     * 检查共享会话是否存在
     */
    fun hasActiveSession(): Boolean = sharedSessionId != null
} 