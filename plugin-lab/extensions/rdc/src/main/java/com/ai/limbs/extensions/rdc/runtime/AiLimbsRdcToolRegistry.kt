// Source: AI Limbs V0.6.4.7.8 @ 70438d99bb40c147cadc0a4a085deb90d15b347c; runtime host adapters are separate files.
package com.ai.limbs.extensions.rdc.runtime

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
