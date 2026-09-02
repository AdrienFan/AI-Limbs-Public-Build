package com.ai.assistance.operit.plugins.system

import com.ai.assistance.operit.plugins.center.PluginInstallException
import org.json.JSONObject

internal class KernelSelfMaintenanceJsonServiceV1(
    private val controller: SystemPluginController
) : SystemJsonServiceV1 {
    override suspend fun call(operation: String, parameters: JSONObject): JSONObject = when (operation.trim()) {
        "status" -> controller.snapshot().toJson()
        "online_update_status" -> JSONObject()
            .put("available", false)
            .put("enabled", false)
            .put("reason", "ONLINE_UPDATE_NOT_CONFIGURED")
        "stage_upgrade" -> {
            val uri = parameters.optString("uri").trim()
            val name = parameters.optString("name").trim().ifBlank { null }
            if (uri.isEmpty()) {
                throw PluginInstallException("SYSTEM_UPGRADE_SOURCE_MISSING", "Upgrade requires uri")
            }
            val validation = controller.stageUpgradeFromUri(uri, name)
            JSONObject()
                .put("accepted", true)
                .put("target_version", validation.manifest.version)
                .put("requires_ui_reload", true)
                .put("return_surface", "toolbox")
        }
        "repair" -> {
            controller.requestRepair()
            JSONObject()
                .put("accepted", true)
                .put("requires_ui_reload", true)
                .put("return_surface", "toolbox")
        }
        "rollback" -> {
            controller.requestRollback()
            JSONObject()
                .put("accepted", true)
                .put("requires_ui_reload", true)
                .put("return_surface", "toolbox")
        }
        else -> throw PluginInstallException(
            "SYSTEM_MAINTENANCE_OPERATION_UNKNOWN",
            "Unknown Plugin Center maintenance operation: $operation"
        )
    }

    private fun SystemPluginMaintenanceSnapshot.toJson(): JSONObject = JSONObject()
        .put("installed", installed)
        .put("active_version", activeVersion ?: JSONObject.NULL)
        .put("current_backup_version", currentBackupVersion ?: JSONObject.NULL)
        .put("previous_backup_version", previousBackupVersion ?: JSONObject.NULL)
        .put("can_repair", currentBackupVersion != null)
        .put("can_rollback", previousBackupVersion != null)
        .put("online_update_enabled", false)
}
