package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.plugins.center.PluginCapabilityGateway
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvoker
import com.ai.assistance.operit.plugins.center.PluginCapabilityInvokerFactory
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Local executor registry inside ail_plugin_runtime; delegated Host calls always return to Core. */
internal class PluginWorkerCapabilityGateway(
    private val coreClient: PluginRuntimeCoreClient
) : PluginCapabilityGateway, PluginCapabilityInvokerFactory {
    private data class Entry(
        val ownerId: String,
        val version: String?,
        val spec: PluginCapabilitySpec
    )

    private val versions = ConcurrentHashMap<String, String>()
    private val capabilities = ConcurrentHashMap<String, Entry>()

    fun bindTopLevel(ownerId: String, version: String): AutoCloseable {
        require(ownerId.isNotBlank() && version.isNotBlank())
        check(versions.putIfAbsent(ownerId, version) == null) { "Worker owner already bound: $ownerId" }
        return AutoCloseable {
            versions.remove(ownerId, version)
            capabilities.entries.removeIf { it.value.ownerId == ownerId }
        }
    }

    override fun register(
        ownerPluginId: String,
        capabilityId: String,
        capability: PluginCapabilitySpec
    ): AutoCloseable {
        val id = capabilityId.trim().lowercase()
        require(id.isNotBlank()) { "Capability id is blank" }
        val entry = Entry(ownerPluginId, versions[ownerPluginId], capability)
        check(capabilities.putIfAbsent(id, entry) == null) { "Capability already registered in worker: $id" }
        return AutoCloseable { capabilities.remove(id, entry) }
    }

    override suspend fun invokeDelegated(
        ownerPluginId: String,
        grantedScopes: Set<String>,
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject {
        val version = versions[ownerPluginId]
            ?: throw PluginInstallException("PLUGIN_WORKER_OWNER_UNKNOWN", "No worker version bound for $ownerPluginId")
        return coreClient.invokeDelegated(ownerPluginId, version, capabilityId, JSONObject(parameters.toString()))
    }

    override fun create(ownerPluginId: String, grantedScopes: Set<String>): PluginCapabilityInvoker =
        PluginCapabilityInvoker { capabilityId, parameters ->
            invokeDelegated(ownerPluginId, grantedScopes, capabilityId, parameters)
        }

    suspend fun executeLocal(ownerId: String, capabilityId: String, parameters: JSONObject): JSONObject {
        val id = capabilityId.trim().lowercase()
        val entry = capabilities[id]
            ?: throw PluginInstallException("CAPABILITY_NOT_ACTIVE", "Worker capability is not active: $id")
        check(entry.ownerId == ownerId) { "Worker capability owner mismatch: $id" }
        return entry.spec.executor.execute(JSONObject(parameters.toString()))
    }

    fun descriptors(ownerId: String? = null): JSONArray = JSONArray().apply {
        capabilities.entries.sortedBy { it.key }.forEach { (id, entry) ->
            if (ownerId == null || entry.ownerId == ownerId) put(descriptor(id, entry))
        }
    }

    private fun descriptor(id: String, entry: Entry): JSONObject {
        val spec = entry.spec
        val parameters = JSONArray().apply {
            spec.parameters.forEach { p ->
                put(JSONObject()
                    .put("name", p.name)
                    .put("type", p.type)
                    .put("description", p.description)
                    .put("required", p.required)
                    .put("default", p.default ?: JSONObject.NULL))
            }
        }
        return JSONObject()
            .put("owner_id", entry.ownerId)
            .put("owner_version", entry.version ?: JSONObject.NULL)
            .put("id", id)
            .put("display_name", spec.displayName)
            .put("description", spec.description)
            .put("invoke_aliases", JSONArray(spec.invokeAliases))
            .put("keywords", JSONArray(spec.keywords))
            .put("parameters", parameters)
            .put("suggested_params_json", spec.suggestedParamsJson ?: JSONObject.NULL)
            .put("input_schema", spec.inputSchema ?: JSONObject.NULL)
            .put("effect", spec.effect.name)
            .put("domain", spec.domain.name)
            .put("work_receipts", JSONArray(spec.workContextRequiredReceipts.map { it.name }))
    }
}
