package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.data.model.ToolParameterSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared recovery protocol for capability discovery.
 *
 * Resolver and Policy Engine both use this contract so unknown addresses always lead back to the
 * same canonical capability.search surface instead of manufacturing transport-specific advice.
 */
internal object AiLimbsCapabilityDiscoveryProtocol {
    const val SEARCH_INVOKE_ID = "capability.search"

    fun searchNextAction(
        queryExample: String,
        scope: String? = null,
        transportInvocation: (String, JSONObject) -> JSONObject
    ): JSONObject {
        val exampleParameters =
            JSONObject().put("query", queryExample).apply {
                scope?.trim()?.ifBlank { null }?.let { put("scope", it) }
            }
        val registration =
            requireNotNull(
                AiLimbsCoreCapabilityRegistry.registrationForInvokeName(SEARCH_INVOKE_ID)
            ) {
                "Resolver capability is not registered: $SEARCH_INVOKE_ID"
            }
        val entry = registration.catalogEntry

        return JSONObject()
            .put("type", "CAPABILITY_SEARCH")
            .put(
                "capability",
                JSONObject()
                    .put("name", SEARCH_INVOKE_ID)
                    .put(
                        "parameters",
                        JSONObject(exampleParameters.toString())
                    )
            )
            .put("parameters", parametersJson(entry.parameters))
            .put("schema", schemaJson(entry))
            .put(
                "transport_invocation",
                transportInvocation(SEARCH_INVOKE_ID, exampleParameters)
            )
    }

    private fun parametersJson(
        parameters: List<ToolParameterSchema>
    ): JSONArray =
        JSONArray().apply {
            parameters.forEach { parameter ->
                put(
                    JSONObject()
                        .put("name", parameter.name)
                        .put("type", parameter.type)
                        .put("description", parameter.description)
                        .put("required", parameter.required)
                        .put(
                            "default",
                            parameter.default ?: JSONObject.NULL
                        )
                )
            }
        }

    private fun schemaJson(entry: ToolCatalogEntry): JSONObject {
        entry.inputSchema?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
        }

        val properties = JSONObject()
        val required = JSONArray()
        entry.parameters.forEach { parameter ->
            properties.put(
                parameter.name,
                JSONObject()
                    .put("type", parameter.type)
                    .put("description", parameter.description)
                    .apply {
                        parameter.default?.let { put("default", it) }
                    }
            )
            if (parameter.required) required.put(parameter.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
    }
}
