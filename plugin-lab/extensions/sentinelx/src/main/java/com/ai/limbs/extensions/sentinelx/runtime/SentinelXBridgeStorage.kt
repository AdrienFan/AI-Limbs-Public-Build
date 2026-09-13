package com.ai.limbs.extensions.sentinelx.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class SentinelXBridgeConfig(
    val configured: Boolean,
    val secureStorageAvailable: Boolean,
    val hostId: String,
    val hubUrl: String,
    val deviceName: String
)

internal class SentinelXBridgeStorage(context: Context) {
    private val appContext = context.applicationContext
    private val metadata = appContext.getSharedPreferences(METADATA_PREF_FILE, Context.MODE_PRIVATE)
    private val secrets: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createSecretPreferences()
    }

    fun readConfig(): SentinelXBridgeConfig {
        val tokenRead = runCatching { readTokenUnsafe() }
        return SentinelXBridgeConfig(
            configured = tokenRead.getOrNull() != null,
            secureStorageAvailable = tokenRead.isSuccess,
            hostId = ensureHostId(),
            hubUrl = metadata.getString(KEY_HUB_URL, null)?.trim()?.trimEnd('/')
                ?: DEFAULT_HUB_URL,
            deviceName = metadata.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
                ?: defaultDeviceName()
        )
    }

    internal fun readToken(): String? = runCatching { readTokenUnsafe() }.getOrNull()

    fun saveBinding(token: String, hubUrl: String, deviceName: String) {
        val normalizedToken = token.trim()
        val existing = readTokenUnsafe()
        val tokenToStore = normalizedToken.ifBlank { existing.orEmpty() }
        require(tokenToStore.count { it == '.' } == 2) { "SentinelX enrollment token 必须是完整 JWT" }

        val normalizedHub = normalizeHubUrl(hubUrl)
        val normalizedName = deviceName.trim().ifBlank { defaultDeviceName() }.take(64)
        secrets.edit().putString(KEY_TOKEN, tokenToStore).apply()
        metadata.edit()
            .putString(KEY_HUB_URL, normalizedHub)
            .putString(KEY_DEVICE_NAME, normalizedName)
            .apply()
    }

    fun updateConnectionSettings(hubUrl: String, deviceName: String) {
        val normalizedHub = normalizeHubUrl(hubUrl)
        val normalizedName = deviceName.trim().ifBlank { defaultDeviceName() }.take(64)
        metadata.edit()
            .putString(KEY_HUB_URL, normalizedHub)
            .putString(KEY_DEVICE_NAME, normalizedName)
            .apply()
    }

    fun clearBinding() {
        secrets.edit().remove(KEY_TOKEN).apply()
    }

    fun regenerateHostId(): String {
        clearBinding()
        val hostId = newHostId()
        metadata.edit().putString(KEY_HOST_ID, hostId).apply()
        return hostId
    }

    fun enrollmentUrl(): String {
        val config = readConfig()
        val encoded = URLEncoder.encode(config.hostId, StandardCharsets.UTF_8.name())
        return "${config.hubUrl}/auth/dashboard/enroll?host_id=$encoded"
    }

    private fun readTokenUnsafe(): String? =
        secrets.getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun ensureHostId(): String {
        metadata.getString(KEY_HOST_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = newHostId()
        metadata.edit().putString(KEY_HOST_ID, generated).apply()
        return generated
    }

    private fun normalizeHubUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "SentinelX Hub URL 必须以 http:// 或 https:// 开头"
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

    private fun defaultDeviceName(): String =
        "AI-Limbs-${Build.MODEL.replace(' ', '-')}".take(64)

    private fun newHostId(): String =
        "host_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    companion object {
        const val DEFAULT_HUB_URL = "https://mcp.sentinelx.app"
        private const val SECRET_PREF_FILE = "ai_limbs_sentinelx_bridge_secret"
        private const val METADATA_PREF_FILE = "ai_limbs_sentinelx_bridge_metadata"
        private const val KEY_TOKEN = "identity_token"
        private const val KEY_HOST_ID = "host_id"
        private const val KEY_HUB_URL = "hub_url"
        private const val KEY_DEVICE_NAME = "device_name"
    }
}
