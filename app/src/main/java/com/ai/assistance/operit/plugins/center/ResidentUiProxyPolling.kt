package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Host framework requests must progress while presentation snapshots wait for Core lifecycle locks.
 * Keeping both loops under one parent preserves cancellation when the Host client is retired.
 */
internal fun CoroutineScope.launchResidentUiProxyLoops(
    refreshPresentation: suspend CoroutineScope.() -> Unit,
    executeHostComponents: suspend CoroutineScope.() -> Unit
): Job = launch {
    launch(block = executeHostComponents)
    launch(block = refreshPresentation)
}
