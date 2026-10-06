package com.ai.assistance.operit.core.tools.system.resident

/** Authorization prompts and plugin work need the existing 180 s business request budget. */
internal object ResidentUiProxyTimeout {
    private const val CONTROL_MS = 5_000
    private const val COMMAND_MS = 180_000

    fun forOperation(operation: String): Int = if (operation == "command") COMMAND_MS else CONTROL_MS
}
