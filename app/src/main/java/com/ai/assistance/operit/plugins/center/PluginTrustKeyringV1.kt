package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.application.OperitApplication
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class TrustKeyringSignerV1(
    val signerId: String,
    val algorithm: String,
    val publicKeyX509DerBase64: String,
    val publicKeySha256: String,
    val purposes: Set<String>,
    val roles: Set<String>
)

internal data class TrustKeyringSnapshotV1(
    val version: Int,
    val rootSignerId: String,
    val signers: Map<String, TrustKeyringSignerV1>,
    val source: String,
    val keyringSha256: String
)

internal object PluginTrustKeyringV1 {
    const val PURPOSE_SYSTEM_PLUGIN = "system_plugin"
    const val PURPOSE_PARENT_PLUGIN = "parent_plugin"
    const val PURPOSE_CHILD_EXTENSION = "child_extension"

    const val ROOT_SIGNER_ID = "ai-limbs-root-trust-v1"
    const val ROOT_PUBLIC_KEY_X509_DER_BASE64 =
        "MCowBQYDK2VwAyEAZoUumAAeoB9UQP7iZjmN27I/sMu2nb7X2UC6nPk+H80="

    private const val FORMAT = "AIL_TRUST_KEYRING_V1"
    private const val SCHEMA_VERSION = 1
    private const val ASSET_KEYRING = "ai_limbs/trust/official-keyring-v1.json"
    private const val ASSET_SIGNATURE = "ai_limbs/trust/official-keyring-v1.sig"
    private const val ACTIVE_BUNDLE = "keyring-v1.bundle.json"
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val lock = Any()

    fun current(): TrustKeyringSnapshotV1 = synchronized(lock) {
        loadCurrent(applicationContext())
    }

    fun requireSigner(
        signerId: String,
        purpose: String,
        role: String? = null
    ): TrustKeyringSignerV1 {
        val normalizedId = signerId.trim()
        val normalizedPurpose = purpose.trim().lowercase()
        val signer = current().signers[normalizedId]
            ?: throw PluginInstallException("TRUST_SIGNER_UNKNOWN", "Signer is not present in the active keyring: $normalizedId")
        if (normalizedPurpose !in signer.purposes) {
            throw PluginInstallException(
                "TRUST_SIGNER_PURPOSE_FORBIDDEN",
                "Signer $normalizedId is not admitted for purpose $normalizedPurpose"
            )
        }
        val normalizedRole = role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedRole != null && normalizedRole !in signer.roles) {
            throw PluginInstallException(
                "TRUST_SIGNER_ROLE_FORBIDDEN",
                "Signer $normalizedId is not admitted for role $normalizedRole"
            )
        }
        return signer
    }

    fun verifyDetached(
        signerId: String,
        purpose: String,
        role: String? = null,
        payload: ByteArray,
        signatureBytes: ByteArray
    ): Boolean {
        val signer = requireSigner(signerId, purpose, role)
        val publicKeyBytes = Base64.getDecoder().decode(signer.publicKeyX509DerBase64)
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        return verifier.verify(signatureBytes)
    }

    fun installSignedKeyring(
        keyringBytes: ByteArray,
        signatureBytes: ByteArray
    ): TrustKeyringSnapshotV1 = synchronized(lock) {
        verifyRootSignature(keyringBytes, signatureBytes)
        val candidate = parse(keyringBytes, "candidate")
        val current = loadCurrent(applicationContext())
        if (candidate.version < current.version) {
            throw PluginInstallException(
                "TRUST_KEYRING_ROLLBACK_FORBIDDEN",
                "Keyring version ${candidate.version} is older than active version ${current.version}"
            )
        }
        if (candidate.version == current.version) {
            if (candidate.keyringSha256 == current.keyringSha256) return@synchronized current
            throw PluginInstallException(
                "TRUST_KEYRING_VERSION_CONFLICT",
                "Keyring version ${candidate.version} differs from the already active version"
            )
        }
        val bundle = JSONObject()
            .put("keyring_base64", Base64.getEncoder().encodeToString(keyringBytes))
            .put("signature_base64", Base64.getEncoder().encodeToString(signatureBytes))
            .toString()
        writeActiveBundle(applicationContext(), bundle)
        loadCurrent(applicationContext())
    }

    fun statusJson(): JSONObject {
        val snapshot = current()
        return JSONObject()
            .put("format", FORMAT)
            .put("schema_version", SCHEMA_VERSION)
            .put("version", snapshot.version)
            .put("source", snapshot.source)
            .put("keyring_sha256", snapshot.keyringSha256)
            .put("root_signer_id", ROOT_SIGNER_ID)
            .put("root_public_key_sha256", sha256(Base64.getDecoder().decode(ROOT_PUBLIC_KEY_X509_DER_BASE64)))
            .put("trusted_signers", JSONArray().apply {
                snapshot.signers.values.sortedBy { it.signerId }.forEach { signer ->
                    put(JSONObject()
                        .put("signer_id", signer.signerId)
                        .put("algorithm", signer.algorithm)
                        .put("public_key_sha256", signer.publicKeySha256)
                        .put("purposes", JSONArray(signer.purposes.sorted()))
                        .put("roles", JSONArray(signer.roles.sorted())))
                }
            })
    }

    private fun loadCurrent(context: Context): TrustKeyringSnapshotV1 {
        val active = File(trustDir(context), ACTIVE_BUNDLE)
        if (active.isFile) {
            return try {
                val root = JSONObject(active.readText(Charsets.UTF_8))
                val keyringBytes = Base64.getDecoder().decode(root.getString("keyring_base64"))
                val signatureBytes = Base64.getDecoder().decode(root.getString("signature_base64"))
                verifyRootSignature(keyringBytes, signatureBytes)
                parse(keyringBytes, "active")
            } catch (error: PluginInstallException) {
                throw error
            } catch (error: Throwable) {
                throw PluginInstallException(
                    "TRUST_KEYRING_ACTIVE_INVALID",
                    error.message ?: "Active trust keyring is invalid",
                    error
                )
            }
        }
        val keyringBytes = context.assets.open(ASSET_KEYRING).use { it.readBytes() }
        val signatureBytes = context.assets.open(ASSET_SIGNATURE).use { it.readBytes() }
        verifyRootSignature(keyringBytes, signatureBytes)
        return parse(keyringBytes, "bootstrap_asset")
    }

    private fun verifyRootSignature(keyringBytes: ByteArray, signatureBytes: ByteArray) {
        val rootKeyBytes = Base64.getDecoder().decode(ROOT_PUBLIC_KEY_X509_DER_BASE64)
        val rootKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(rootKeyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(rootKey)
        verifier.update(keyringBytes)
        if (!verifier.verify(signatureBytes)) {
            throw PluginInstallException(
                "TRUST_KEYRING_ROOT_SIGNATURE_INVALID",
                "Trust keyring root signature verification failed"
            )
        }
    }

    private fun parse(bytes: ByteArray, source: String): TrustKeyringSnapshotV1 {
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (error: Throwable) {
            throw PluginInstallException("TRUST_KEYRING_JSON_INVALID", "Trust keyring is not valid JSON", error)
        }
        if (root.optString("format") != FORMAT || root.optInt("schema_version", -1) != SCHEMA_VERSION) {
            throw PluginInstallException("TRUST_KEYRING_FORMAT_INVALID", "Unsupported trust keyring format/schema")
        }
        val version = root.optInt("version", -1)
        if (version <= 0) throw PluginInstallException("TRUST_KEYRING_VERSION_INVALID", "Trust keyring version must be positive")
        val rootSignerId = root.optString("root_signer_id").trim()
        if (rootSignerId != ROOT_SIGNER_ID) {
            throw PluginInstallException("TRUST_KEYRING_ROOT_MISMATCH", "Trust keyring declares an unexpected root signer")
        }
        val array = root.optJSONArray("signers")
            ?: throw PluginInstallException("TRUST_KEYRING_SIGNERS_MISSING", "Trust keyring signers are required")
        val signers = linkedMapOf<String, TrustKeyringSignerV1>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw PluginInstallException("TRUST_KEYRING_SIGNER_INVALID", "Signer entry $index is not an object")
            val signerId = item.optString("signer_id").trim()
            val algorithm = item.optString("algorithm").trim()
            val publicKey = item.optString("public_key_x509_der_base64").trim()
            val fingerprint = item.optString("public_key_sha256").trim().lowercase()
            if (signerId.isBlank() || algorithm != "Ed25519" || publicKey.isBlank() || !SHA256.matches(fingerprint)) {
                throw PluginInstallException("TRUST_KEYRING_SIGNER_INVALID", "Signer entry $index is incomplete or unsupported")
            }
            val publicKeyBytes = try {
                Base64.getDecoder().decode(publicKey)
            } catch (error: Throwable) {
                throw PluginInstallException("TRUST_KEYRING_PUBLIC_KEY_INVALID", "Signer $signerId has invalid Base64 public key", error)
            }
            try {
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyBytes))
            } catch (error: Throwable) {
                throw PluginInstallException("TRUST_KEYRING_PUBLIC_KEY_INVALID", "Signer $signerId has invalid Ed25519 public key", error)
            }
            if (sha256(publicKeyBytes) != fingerprint) {
                throw PluginInstallException("TRUST_KEYRING_FINGERPRINT_MISMATCH", "Signer $signerId public key fingerprint does not match")
            }
            val purposes = stringSet(item.optJSONArray("purposes"), "purposes", signerId)
            if (purposes.isEmpty()) {
                throw PluginInstallException("TRUST_KEYRING_PURPOSE_MISSING", "Signer $signerId must declare at least one purpose")
            }
            val roles = stringSet(item.optJSONArray("roles"), "roles", signerId)
            val signer = TrustKeyringSignerV1(
                signerId = signerId,
                algorithm = algorithm,
                publicKeyX509DerBase64 = publicKey,
                publicKeySha256 = fingerprint,
                purposes = purposes,
                roles = roles
            )
            if (signers.put(signerId, signer) != null) {
                throw PluginInstallException("TRUST_KEYRING_SIGNER_DUPLICATE", "Duplicate signer_id: $signerId")
            }
        }
        if (signers.isEmpty()) {
            throw PluginInstallException("TRUST_KEYRING_EMPTY", "Trust keyring must contain at least one signer")
        }
        return TrustKeyringSnapshotV1(
            version = version,
            rootSignerId = rootSignerId,
            signers = signers,
            source = source,
            keyringSha256 = sha256(bytes)
        )
    }

    private fun stringSet(array: JSONArray?, field: String, signerId: String): Set<String> {
        if (array == null) return emptySet()
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim().lowercase()
            if (value.isBlank() || !result.add(value)) {
                throw PluginInstallException(
                    "TRUST_KEYRING_FIELD_INVALID",
                    "Signer $signerId has invalid or duplicate $field entry"
                )
            }
        }
        return result
    }

    private fun writeActiveBundle(context: Context, content: String) {
        val dir = trustDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw PluginInstallException("TRUST_KEYRING_STORE_UNAVAILABLE", "Could not create trust keyring directory")
        }
        val target = File(dir, ACTIVE_BUNDLE)
        val temp = File(dir, "$ACTIVE_BUNDLE.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw PluginInstallException("TRUST_KEYRING_STORE_FAILED", "Could not replace active trust keyring")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw PluginInstallException("TRUST_KEYRING_STORE_FAILED", "Could not activate trust keyring")
        }
    }

    private fun trustDir(context: Context): File = File(context.filesDir, "ai_limbs/trust")

    private fun applicationContext(): Context =
        OperitApplication.instance.applicationContext

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
