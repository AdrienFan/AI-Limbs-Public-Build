package com.ai.assistance.operit.plugins.center

import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ChildUiContributionSnapshot
import com.ai.limbs.plugin.runtime.InProcessChildExtensionRuntime
import org.json.JSONArray
import org.json.JSONObject

/** Internal lifecycle/control boundary so BUSINESS can move child code out of Resident Core. */
internal interface ChildExtensionRuntimeOwner {
    suspend fun start()
    suspend fun stop()
    fun bound(ownerPluginId: String, grantedScopes: Set<String>): InProcessChildExtensionRuntime
    suspend fun exportBackups(extensionIds: Collection<String>, treeUriRaw: String): List<String>
    fun loggingSnapshots(): List<ChildExtensionSnapshot>
    fun loggingBackupSnapshots(): List<ChildExtensionBackupSnapshot>
    fun loggingUiContributions(): List<ChildUiContributionSnapshot>
    fun loggingCanonicalDescriptors(): List<CanonicalChildDescriptor>
    fun residentPresentationDescriptors(): JSONArray
    suspend fun awaitEnabledPointReady(point: String, timeoutMs: Long = 5_000L)
    suspend fun awaitBusinessChildrenReady(timeoutMs: Long = 10_000L): JSONObject
    suspend fun invokePresentationCommand(extensionId: String, command: String, parameters: JSONObject): JSONObject
}
