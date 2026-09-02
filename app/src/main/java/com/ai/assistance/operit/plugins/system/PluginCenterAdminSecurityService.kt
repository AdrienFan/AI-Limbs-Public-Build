package com.ai.assistance.operit.plugins.system

import com.ai.assistance.operit.plugins.center.AdminAuthFrequency
import com.ai.assistance.operit.plugins.center.AdminSecurityManager
import com.ai.assistance.operit.plugins.center.PluginInstallException
import org.json.JSONObject

internal class KernelAdminSecurityJsonServiceV1(
    private val admin: AdminSecurityManager
) : SystemJsonServiceV1 {
    override suspend fun call(
        operation: String,
        parameters: JSONObject
    ): JSONObject = when (operation.trim()) {
        "status" -> admin.snapshot().let { snapshot ->
            JSONObject()
                .put("configured", snapshot.configured)
                .put("recovery_configured", snapshot.recoveryConfigured)
                .put("auth_frequency", snapshot.authFrequency.name)
                .put("authorization_required", admin.authorizationRequired())
        }
        "setup" -> JSONObject()
            .put("recovery_key", admin.setup(parameters.requireText("password")).recoveryKey)
        "verify" -> JSONObject()
            .put("valid", admin.verifyPassword(parameters.requireText("password")))
        "change_password" -> JSONObject().put(
            "changed",
            admin.changePassword(
                parameters.requireText("current_password"),
                parameters.requireText("new_password")
            )
        )
        "recover_password" -> JSONObject().put(
            "recovered",
            admin.recoverPassword(
                parameters.requireText("recovery_key"),
                parameters.requireText("new_password")
            )
        )
        "regenerate_recovery_key" -> {
            val key = admin.regenerateRecoveryKey(parameters.requireText("password"))
            JSONObject()
                .put("changed", key != null)
                .put("recovery_key", key ?: JSONObject.NULL)
        }
        "change_auth_frequency" -> {
            val frequency = runCatching {
                AdminAuthFrequency.valueOf(parameters.requireText("frequency"))
            }.getOrElse {
                throw PluginInstallException(
                    "ADMIN_AUTH_FREQUENCY_INVALID",
                    "Invalid administrator authentication frequency"
                )
            }
            JSONObject().put(
                "changed",
                admin.changeAuthFrequency(
                    parameters.requireText("password"),
                    frequency
                )
            )
        }
        else -> throw PluginInstallException(
            "ADMIN_SECURITY_OPERATION_UNKNOWN",
            "Unknown administrator security operation: $operation"
        )
    }
}

private fun JSONObject.requireText(key: String): String =
    optString(key).trim().takeIf { it.isNotEmpty() }
        ?: throw PluginInstallException("FIELD_MISSING", "$key is required")
