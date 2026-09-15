package com.ai.limbs.plugins.permission

import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject

internal class PermissionUiStateProvider(
    private val controller: PermissionPageController,
    scope: CoroutineScope
) : InProcessUiStateProvider {
    override val stateJson: StateFlow<String?> =
        controller.state
            .map { it.toUiJson().toString() }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                controller.state.value.toUiJson().toString()
            )

    override suspend fun perform(eventId: String, payloadJson: String): String {
        val payload = runCatching { JSONObject(payloadJson) }.getOrElse {
            error("Permission UI event payload must be a JSON object")
        }
        when (eventId.trim().lowercase()) {
            "refresh" -> controller.refresh()
            "pair" -> controller.pair(
                payload.getInt("port"),
                payload.getString("code")
            )
            "start_adb" -> controller.startAdb(payload.getInt("port"))
            "start_root" -> controller.startRoot()
            "stop" -> controller.stop()
            "select" -> controller.select(payload.getString("backend"))
            else -> error("Unsupported Permission Service UI event: $eventId")
        }
        return controller.state.value.toUiJson().toString()
    }
}
