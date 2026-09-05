package com.ai.limbs.plugins.packager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal data class VaultSignature(
    val signature: ByteArray,
    val signerId: String,
    val fingerprintSha256: String
)

/**
 * Development-only signing vault for Packager.
 *
 * Private PEM material is never persisted as plaintext. The selected private key is validated against
 * the formal AI Limbs public key for its package class, encrypted with an Android Keystore AES-GCM key,
 * and stored only below this plugin's dataDir. The clear operation removes both ciphertext and the
 * Keystore wrapping key.
 */
internal class DevelopmentSigningVault(private val dataDir: File) {
    private data class Profile(
        val type: PackagerArtifactType,
        val wireName: String,
        val displayName: String,
        val signerId: String,
        val publicKeyDerBase64: String
    )

    private val profiles = listOf(
        Profile(
            PackagerArtifactType.SYSTEM,
            "system_plugin",
            ".ailpsys",
            "ai-limbs-plugin-center-dev-v1",
            "MCowBQYDK2VwAyEAAp37oPipssz+0yW+8ceya8OC+QAW0YJBoZ0pgv5DIlE="
        ),
        Profile(
            PackagerArtifactType.PARENT,
            "parent_plugin",
            ".ailp",
            "ai-limbs-parent-plugin-dev-v1",
            "MCowBQYDK2VwAyEA9AzxJqAt9ej8A4/1Q6xvP2e7+fpZT/2QoZXEgPy2PD8="
        ),
        Profile(
            PackagerArtifactType.CHILD,
            "child_extension",
            ".ailx",
            "ai-limbs-child-extension-dev-v1",
            "MCowBQYDK2VwAyEAe4kqZuAWMIJxpTB5clNmEMlOzj8qwUP5fh3gGQXRcRY="
        )
    ).associateBy { it.type }

    private val root = File(dataDir, "development-signing-vault")

    fun importPrivateKey(type: PackagerArtifactType, pem: ByteArray): JSONObject {
        require(pem.isNotEmpty()) { "私钥文件为空" }
        require(pem.size <= MAX_PRIVATE_KEY_BYTES) { "私钥文件过大" }
        val profile = requireProfile(type)
        validatePrivateKey(profile, pem)
        if (!root.exists() && !root.mkdirs()) error("无法创建开发签名仓")
        val target = blobFile(profile)
        val temporary = File(root, ".${profile.wireName}-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(encrypt(pem))
            harden(temporary)
            if (target.exists() && !target.delete()) error("无法替换旧签名密钥")
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            harden(target)
        } finally {
            temporary.delete()
        }
        return profileStatus(profile, validate = true)
    }

    fun sign(type: PackagerArtifactType, data: ByteArray): VaultSignature {
        require(data.size <= MAX_SIGN_BYTES) { "Signing payload is too large" }
        val profile = requireProfile(type)
        val pem = loadPrivatePem(profile)
        return try {
            val privateKey = parsePrivateKey(pem)
            val engine = Signature.getInstance("Ed25519")
            engine.initSign(privateKey)
            engine.update(data)
            VaultSignature(engine.sign(), profile.signerId, fingerprint(profile))
        } finally {
            pem.fill(0)
        }
    }

    fun verify(type: PackagerArtifactType, data: ByteArray, signature: ByteArray): Boolean {
        require(data.size <= MAX_SIGN_BYTES) { "Verification payload is too large" }
        val profile = requireProfile(type)
        val engine = Signature.getInstance("Ed25519")
        engine.initVerify(publicKey(profile))
        engine.update(data)
        return engine.verify(signature)
    }

    fun status(): JSONObject {
        val items = JSONArray()
        PackagerArtifactType.values().forEach { type ->
            items.put(profileStatus(requireProfile(type), validate = true))
        }
        return JSONObject()
            .put("status", "OK")
            .put("storage", "plugin_private_android_keystore_aes_gcm")
            .put("profiles", items)
    }

    fun clearAll(): JSONObject {
        var removed = 0
        profiles.values.forEach { profile ->
            val file = blobFile(profile)
            if (file.exists() && file.delete()) removed += 1
        }
        root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        if (root.exists()) root.delete()
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) keyStore.deleteEntry(KEYSTORE_ALIAS)
        return JSONObject()
            .put("status", "OK")
            .put("removed_profiles", removed)
            .put("message", "开发签名仓已清除")
    }

    fun signerId(type: PackagerArtifactType): String = requireProfile(type).signerId

    private fun profileStatus(profile: Profile, validate: Boolean): JSONObject {
        val file = blobFile(profile)
        var valid = false
        var message = if (file.isFile) "已配置" else "未配置"
        if (file.isFile && validate) {
            runCatching {
                val pem = loadPrivatePem(profile)
                try { validatePrivateKey(profile, pem) } finally { pem.fill(0) }
            }.onSuccess {
                valid = true
                message = "已配置并通过正式公钥自检"
            }.onFailure { error ->
                message = "签名仓无效：${error.message ?: error::class.java.simpleName}"
            }
        }
        return JSONObject()
            .put("profile", profile.wireName)
            .put("artifact", profile.displayName)
            .put("signer_id", profile.signerId)
            .put("configured", file.isFile)
            .put("valid", valid)
            .put("fingerprint_sha256", fingerprint(profile))
            .put("message", message)
    }

    private fun validatePrivateKey(profile: Profile, pem: ByteArray) {
        val privateKey = parsePrivateKey(pem)
        val test = "AI Limbs Packager development signing vault self-test".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(test)
        val signature = signer.sign()
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey(profile))
        verifier.update(test)
        require(verifier.verify(signature)) {
            "选择的私钥与 AI Limbs 正式 ${profile.displayName} 公钥不匹配"
        }
    }

    private fun parsePrivateKey(pem: ByteArray): PrivateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
        PKCS8EncodedKeySpec(readPem(pem, "PRIVATE KEY"))
    )

    private fun publicKey(profile: Profile): PublicKey = KeyFactory.getInstance("Ed25519").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(profile.publicKeyDerBase64))
    )

    private fun fingerprint(profile: Profile): String {
        val der = Base64.getDecoder().decode(profile.publicKeyDerBase64)
        return MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }
    }

    private fun readPem(pem: ByteArray, label: String): ByteArray {
        val text = pem.toString(Charsets.US_ASCII)
        val body = text
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
            .replace(Regex("\\s+"), "")
        require(body.isNotBlank()) { "不是有效的 PKCS#8 $label PEM" }
        return Base64.getDecoder().decode(body)
    }

    private fun loadPrivatePem(profile: Profile): ByteArray {
        val file = blobFile(profile)
        require(file.isFile) { "${profile.displayName} 私钥尚未导入开发签名仓" }
        return decrypt(file.readBytes())
    }

    private fun blobFile(profile: Profile): File = File(root, "${profile.wireName}.blob")

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(createIfMissing = true))
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size in 8..32) { "Unexpected AES-GCM IV length" }
        return ByteBuffer.allocate(2 + iv.size + ciphertext.size)
            .put(BLOB_VERSION.toByte())
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 2) { "签名仓数据损坏" }
        val buffer = ByteBuffer.wrap(blob)
        val version = buffer.get().toInt() and 0xff
        require(version == BLOB_VERSION) { "不支持的签名仓版本" }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 8..32 && buffer.remaining() > ivSize) { "签名仓 IV 无效" }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(createIfMissing = false), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun wrappingKey(createIfMissing: Boolean): SecretKey {
        val keyStore = androidKeyStore()
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        require(createIfMissing) { "Android Keystore 包装密钥不存在；请清除签名仓后重新导入私钥" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun harden(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun requireProfile(type: PackagerArtifactType): Profile =
        profiles[type] ?: error("Unknown signing profile: $type")

    private companion object {
        const val KEYSTORE_ALIAS = "ai_limbs.packager.development_signing_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val BLOB_VERSION = 1
        const val MAX_PRIVATE_KEY_BYTES = 64 * 1024
        const val MAX_SIGN_BYTES = 2 * 1024 * 1024
    }
}
