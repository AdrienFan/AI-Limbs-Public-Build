package com.ai.limbs.extensions.chatgptprobe

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class ChatGptProbeConfig(
    val configured: Boolean,
    val secureStorageAvailable: Boolean,
    val tunnelId: String,
    val baseUrl: String
)

internal class ChatGptNativeProbeStorage(context: Context) {
    private val appContext = context.applicationContext
    private val metadata = appContext.getSharedPreferences(METADATA_PREF_FILE, Context.MODE_PRIVATE)
    private val secrets: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createSecretPreferences()
    }

    fun readConfig(): ChatGptProbeConfig {
        val keyRead = readApiKeyResult()
        return ChatGptProbeConfig(
            configured = keyRead.getOrNull() != null && readTunnelId().isNotBlank(),
            secureStorageAvailable = keyRead.isSuccess,
            tunnelId = readTunnelId(),
            baseUrl = metadata.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/')
                ?: DEFAULT_BASE_URL
        )
    }

    fun readApiKey(): String? = readApiKeyResult().getOrNull()

    fun saveBinding(apiKey: String, tunnelId: String, baseUrl: String) {
        val normalizedKey = apiKey.trim()
        require(normalizedKey.startsWith("sk-") && normalizedKey.length > 20 && normalizedKey.none(Char::isWhitespace)) {
            "Runtime API Key 格式无效"
        }
        val normalizedTunnel = tunnelId.trim()
        require(normalizedTunnel.startsWith("tunnel_") && normalizedTunnel.length > 16) {
            "Tunnel ID 格式无效"
        }
        val normalizedBase = normalizeBaseUrl(baseUrl)

        secrets.edit().putString(KEY_API_KEY, normalizedKey).apply()
        metadata.edit()
            .putString(KEY_TUNNEL_ID, normalizedTunnel)
            .putString(KEY_BASE_URL, normalizedBase)
            .apply()
    }

    fun clearBinding() {
        secrets.edit().remove(KEY_API_KEY).apply()
        metadata.edit().remove(KEY_TUNNEL_ID).apply()
    }

    private fun readApiKeyResult(): Result<String?> =
        runCatching {
            secrets.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotBlank() }
        }

    private fun readTunnelId(): String =
        metadata.getString(KEY_TUNNEL_ID, null)?.trim().orEmpty()

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        require(normalized.startsWith("https://")) {
            "Control Plane URL 必须使用 https://"
        }
        return normalized
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com"
        private const val SECRET_PREF_FILE = "ai_limbs_chatgpt_probe_secret"
        private const val METADATA_PREF_FILE = "ai_limbs_chatgpt_probe_metadata"
        private const val KEY_API_KEY = "runtime_api_key"
        private const val KEY_TUNNEL_ID = "tunnel_id"
        private const val KEY_BASE_URL = "base_url"
    }
}
