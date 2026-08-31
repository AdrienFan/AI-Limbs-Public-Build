// Source: AI Limbs V0.6.4.7.8 @ 70438d99bb40c147cadc0a4a085deb90d15b347c; host execution is adapted separately.
package com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class TriggerCmdBridgeConfig(
    val configured: Boolean,
    val secureStorageAvailable: Boolean,
    val computerName: String,
    val computerId: String?
)

internal class TriggerCmdBridgeStorage(context: Context) {
    private val appContext = context.applicationContext
    private val metadataPreferences =
        appContext.getSharedPreferences(METADATA_PREF_FILE, Context.MODE_PRIVATE)
    private val secretPreferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createSecretPreferences()
    }

    fun readConfig(): TriggerCmdBridgeConfig {
        val secretRead = runCatching {
            secretPreferences.getString(KEY_AGENT_TOKEN, null)?.takeIf { it.isNotBlank() }
        }
        return TriggerCmdBridgeConfig(
            configured = secretRead.getOrNull() != null,
            secureStorageAvailable = secretRead.isSuccess,
            computerName = metadataPreferences.getString(KEY_COMPUTER_NAME, null)
                ?.takeIf { it.isNotBlank() }
                ?: defaultComputerName(),
            computerId = metadataPreferences.getString(KEY_COMPUTER_ID, null)
                ?.takeIf { it.isNotBlank() }
        )
    }

    internal fun readAgentToken(): String? =
        runCatching {
            secretPreferences.getString(KEY_AGENT_TOKEN, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    internal fun transportPreferences(): SharedPreferences = metadataPreferences

    fun saveBinding(agentToken: String, computerName: String) {
        val normalizedToken = agentToken.trim()
        require(normalizedToken.isNotBlank()) { "TRIGGERcmd Agent Token is required" }
        val normalizedName = computerName.trim().ifBlank { defaultComputerName() }.take(64)
        secretPreferences.edit().putString(KEY_AGENT_TOKEN, normalizedToken).apply()
        metadataPreferences.edit().putString(KEY_COMPUTER_NAME, normalizedName).apply()
    }

    fun updateComputerName(computerName: String) {
        val normalizedName = computerName.trim().ifBlank { defaultComputerName() }.take(64)
        metadataPreferences.edit().putString(KEY_COMPUTER_NAME, normalizedName).apply()
    }

    fun clearBinding() {
        secretPreferences.edit().remove(KEY_AGENT_TOKEN).apply()
        metadataPreferences.edit()
            .remove(KEY_COMPUTER_ID)
            .remove(KEY_TOKEN_FINGERPRINT)
            .apply()
    }

    private fun createSecretPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            SECRET_PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun defaultComputerName(): String =
        "AI-Limbs-v06475-${Build.MODEL.replace(' ', '-')}".take(64)

    companion object {
        private const val SECRET_PREF_FILE = "ai_limbs_triggercmd_bridge_secret"
        private const val METADATA_PREF_FILE = "ai_limbs_triggercmd_bridge_metadata"
        private const val KEY_AGENT_TOKEN = "agent_token"
        internal const val KEY_COMPUTER_ID = "computer_id"
        internal const val KEY_COMPUTER_NAME = "computer_name"
        internal const val KEY_TOKEN_FINGERPRINT = "token_fingerprint"
    }
}
