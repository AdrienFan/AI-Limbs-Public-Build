package com.ai.assistance.operit.core.tools.defaultTool.admin

import android.content.Context
import com.ai.assistance.operit.core.tools.defaultTool.debugger.DebuggerUITools
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel

/** 管理员级别的UI工具，继承调试版本 */
open class AdminUITools(
    context: Context,
    allowAccessibilityFallback: Boolean = true,
    explicitShellPermissionLevel: AndroidPermissionLevel? = null
) : DebuggerUITools(
    context = context,
    allowAccessibilityFallback = allowAccessibilityFallback,
    explicitShellPermissionLevel = explicitShellPermissionLevel
) {
    // ADMIN currently reuses DEBUGGER UI semantics; the shell transport can still be explicit ADMIN.
}
