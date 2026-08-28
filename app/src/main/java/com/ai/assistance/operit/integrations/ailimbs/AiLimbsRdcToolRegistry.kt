package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject

internal typealias AiLimbsRdcToolHandler = suspend (JSONObject) -> JSONObject

internal class AiLimbsRdcToolRegistry {
    private val handlers = linkedMapOf<String, AiLimbsRdcToolHandler>()

    fun register(
        name: String,
        handler: AiLimbsRdcToolHandler
    ): AiLimbsRdcToolRegistry {
        check(name.isNotBlank()) { "RDC tool name must not be blank" }
        check(name !in handlers) { "RDC tool is already registered: $name" }
        handlers[name] = handler
        return this
    }

    fun register(
        names: Iterable<String>,
        handler: AiLimbsRdcToolHandler
    ): AiLimbsRdcToolRegistry {
        names.forEach { register(it, handler) }
        return this
    }

    suspend fun executeOrNull(name: String, args: JSONObject): JSONObject? =
        handlers[name]?.invoke(args)

    val names: Set<String>
        get() = handlers.keys.toSet()
}
