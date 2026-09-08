package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.view.canvas

/**
 * Lightweight tab model used by CanvasTerminalView for in-surface tab rendering.
 */
data class TerminalTabRenderItem(
    val id: String,
    val title: String,
    val canClose: Boolean
)
