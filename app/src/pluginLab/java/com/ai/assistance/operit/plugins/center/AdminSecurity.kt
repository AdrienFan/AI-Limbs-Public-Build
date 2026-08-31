package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class AdminSecuritySnapshot(
    val configured: Boolean,
    val recoveryConfigured: Boolean
)

data class AdminSetupResult(val recoveryKey: String)

class AdminSecurityManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun snapshot(): AdminSecuritySnapshot = AdminSecuritySnapshot(
        configured = hasBundle(PASSWORD_PREFIX),
        recoveryConfigured = hasBundle(RECOVERY_PREFIX)
    )

    fun setup(password: String): AdminSetupResult {
        requirePassword(password)
        check(!snapshot().configured) { "管理员密码已经设置" }
        val master = ByteArray(MASTER_BYTES).also(random::nextBytes)
        val recoveryKey = generateRecoveryKey()
        prefs.edit()
            .putInt(KEY_VERSION, FORMAT_VERSION)
            .putBundle(PASSWORD_PREFIX, wrap(master, password))
            .putBundle(RECOVERY_PREFIX, wrap(master, normalizeRecoveryKey(recoveryKey)))
            .apply()
        master.fill(0)
        return AdminSetupResult(recoveryKey)
    }

    fun verifyPassword(password: String): Boolean {
        val master = unwrap(PASSWORD_PREFIX, password) ?: return false
        master.fill(0)
        return true
    }

    fun changePassword(currentPassword: String, newPassword: String): Boolean {
        requirePassword(newPassword)
        val master = unwrap(PASSWORD_PREFIX, currentPassword) ?: return false
        prefs.edit().putBundle(PASSWORD_PREFIX, wrap(master, newPassword)).apply()
        master.fill(0)
        return true
    }

    fun recoverPassword(recoveryKey: String, newPassword: String): Boolean {
        requirePassword(newPassword)
        val normalized = normalizeRecoveryKey(recoveryKey)
        val master = unwrap(RECOVERY_PREFIX, normalized) ?: return false
        prefs.edit().putBundle(PASSWORD_PREFIX, wrap(master, newPassword)).apply()
        master.fill(0)
        return true
    }

    fun regenerateRecoveryKey(currentPassword: String): String? {
        val master = unwrap(PASSWORD_PREFIX, currentPassword) ?: return null
        val recoveryKey = generateRecoveryKey()
        prefs.edit()
            .putBundle(RECOVERY_PREFIX, wrap(master, normalizeRecoveryKey(recoveryKey)))
            .apply()
        master.fill(0)
        return recoveryKey
    }

    private fun wrap(master: ByteArray, credential: String): CryptoBundle {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = deriveKey(credential, salt)
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return CryptoBundle(salt, iv, cipher.doFinal(master))
    }

    private fun unwrap(prefix: String, credential: String): ByteArray? {
        val bundle = readBundle(prefix) ?: return null
        return runCatching {
            val key = deriveKey(credential, bundle.salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bundle.iv))
            cipher.doFinal(bundle.ciphertext)
        }.getOrNull()
    }

    private fun deriveKey(credential: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(credential.toCharArray(), salt, KDF_ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun generateRecoveryKey(): String {
        val bytes = ByteArray(RECOVERY_BYTES).also(random::nextBytes)
        val hex = bytes.joinToString("") { "%02X".format(it) }
        bytes.fill(0)
        return hex.chunked(4).joinToString("-")
    }

    private fun normalizeRecoveryKey(value: String): String =
        value.filter { it.isLetterOrDigit() }.uppercase()

    private fun requirePassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "管理员密码至少需要 $MIN_PASSWORD_LENGTH 个字符" }
    }

    private fun hasBundle(prefix: String): Boolean =
        prefs.contains("${prefix}_salt") && prefs.contains("${prefix}_iv") && prefs.contains("${prefix}_ct")

    private fun readBundle(prefix: String): CryptoBundle? {
        if (!hasBundle(prefix)) return null
        return runCatching {
            val salt = requireNotNull(prefs.getString("${prefix}_salt", null))
            val iv = requireNotNull(prefs.getString("${prefix}_iv", null))
            val ciphertext = requireNotNull(prefs.getString("${prefix}_ct", null))
            CryptoBundle(
                Base64.decode(salt, Base64.NO_WRAP),
                Base64.decode(iv, Base64.NO_WRAP),
                Base64.decode(ciphertext, Base64.NO_WRAP)
            )
        }.getOrNull()
    }

    private fun android.content.SharedPreferences.Editor.putBundle(prefix: String, bundle: CryptoBundle) =
        putString("${prefix}_salt", Base64.encodeToString(bundle.salt, Base64.NO_WRAP))
            .putString("${prefix}_iv", Base64.encodeToString(bundle.iv, Base64.NO_WRAP))
            .putString("${prefix}_ct", Base64.encodeToString(bundle.ciphertext, Base64.NO_WRAP))

    private data class CryptoBundle(val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        private const val PREFS = "plugin_lab_admin_security"
        private const val KEY_VERSION = "version"
        private const val PASSWORD_PREFIX = "password"
        private const val RECOVERY_PREFIX = "recovery"
        private const val FORMAT_VERSION = 1
        private const val MASTER_BYTES = 32
        private const val RECOVERY_BYTES = 20
        private const val SALT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val KDF_ITERATIONS = 210_000
    }
}
