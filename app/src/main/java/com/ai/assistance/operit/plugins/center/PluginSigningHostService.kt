package com.ai.assistance.operit.plugins.center

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal class PluginSigningHostService(context: Context) {
    private val appContext = context.applicationContext
    private val storeRoot = File(appContext.filesDir, "ai_limbs/signing_keys")

    private data class Profile(
        val wireName: String,
        val directoryName: String,
        val signerId: String
    )

    private val profiles = listOf(
        Profile("system_plugin", "system-plugin", "ai-limbs-plugin-center-dev-v1"),
        Profile("parent_plugin", "parent-plugin", "ai-limbs-parent-plugin-dev-v1"),
        Profile("child_extension", "child-extension", "ai-limbs-child-extension-dev-v1")
    ).associateBy { it.wireName }
    fun execute(request: JSONObject): JSONObject {
        return when (request.optString("operation").ifBlank { "status" }) {
            "status" -> status()
            "import" -> importAll()
            "sign" -> signPayload(request)
            "verify" -> verifyPayload(request)
            else -> JSONObject().put("status", "ERROR").put("message", "Unsupported signing operation")
        }
    }

    private fun status(): JSONObject {
        val items = JSONArray()
        profiles.values.forEach { profile ->
            val files = keyFiles(profile)
            items.put(
                JSONObject()
                    .put("profile", profile.wireName)
                    .put("signer_id", profile.signerId)
                    .put("imported", files.first.isFile && files.second.isFile)
                    .put("backup_available", findBackup(profile) != null)
            )
        }
        return JSONObject().put("status", "OK").put("profiles", items)
    }
    private fun importAll(): JSONObject {
        profiles.values.forEach { ensureImported(it) }
        return status()
    }

    private fun signPayload(request: JSONObject): JSONObject {
        val profile = requireProfile(request)
        val data = decodeRequired(request, "data_base64")
        require(data.size <= MAX_SIGN_BYTES) { "Signing payload is too large" }
        val files = ensureImported(profile)
        val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
            PKCS8EncodedKeySpec(readPem(files.first, "PRIVATE KEY"))
        )
        val engine = Signature.getInstance("Ed25519")
        engine.initSign(privateKey)
        engine.update(data)
        val signature = engine.sign()
        return JSONObject()
            .put("status", "OK")
            .put("algorithm", "Ed25519")
            .put("profile", profile.wireName)
            .put("signer_id", profile.signerId)
            .put("signature_base64", Base64.getEncoder().encodeToString(signature))
            .put("fingerprint_sha256", fingerprint(files.second))
    }
    private fun verifyPayload(request: JSONObject): JSONObject {
        val profile = requireProfile(request)
        val data = decodeRequired(request, "data_base64")
        val signature = decodeRequired(request, "signature_base64")
        require(data.size <= MAX_SIGN_BYTES) { "Verification payload is too large" }
        val files = ensureImported(profile)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(readPem(files.second, "PUBLIC KEY"))
        )
        val engine = Signature.getInstance("Ed25519")
        engine.initVerify(publicKey)
        engine.update(data)
        return JSONObject()
            .put("status", "OK")
            .put("verified", engine.verify(signature))
            .put("profile", profile.wireName)
            .put("signer_id", profile.signerId)
            .put("fingerprint_sha256", fingerprint(files.second))
    }

    private fun requireProfile(request: JSONObject): Profile {
        val key = request.optString("profile").trim()
        return profiles[key] ?: error("Unknown signing profile: $key")
    }
    private fun ensureImported(profile: Profile): Pair<File, File> {
        val files = keyFiles(profile)
        if (files.first.isFile && files.second.isFile) return files
        val backup = findBackup(profile)
            ?: error("Signing key backup is missing for ${profile.wireName}")
        val targetDir = files.first.parentFile ?: error("Signing key directory is invalid")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            error("Could not create private signing key directory")
        }
        File(backup, "private.pem").copyTo(files.first, overwrite = true)
        File(backup, "public.pem").copyTo(files.second, overwrite = true)
        harden(files.first, privateFile = true)
        harden(files.second, privateFile = false)
        verifyPair(files)
        return files
    }

    private fun verifyPair(files: Pair<File, File>) {
        val test = "AI Limbs signing key import self-test".toByteArray(Charsets.UTF_8)
        val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
            PKCS8EncodedKeySpec(readPem(files.first, "PRIVATE KEY"))
        )
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(readPem(files.second, "PUBLIC KEY"))
        )
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(test)
        val signature = signer.sign()
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(test)
        require(verifier.verify(signature)) { "Imported signing key pair does not match" }
    }

    private fun keyFiles(profile: Profile): Pair<File, File> {
        val directory = File(storeRoot, profile.directoryName)
        return File(directory, "private.pem") to File(directory, "public.pem")
    }

    private fun findBackup(profile: Profile): File? {
        val roots = listOf(
            File("/storage/emulated/0/Laner/AI-Limbs-Signing-Keys"),
            File("/sdcard/Laner/AI-Limbs-Signing-Keys")
        )
        return roots.asSequence()
            .map { File(it, profile.directoryName) }
            .firstOrNull { File(it, "private.pem").isFile && File(it, "public.pem").isFile }
    }
    private fun harden(file: File, privateFile: Boolean) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        if (privateFile) file.setWritable(true, true)
    }

    private fun decodeRequired(request: JSONObject, name: String): ByteArray {
        val raw = request.optString(name).trim()
        require(raw.isNotBlank()) { "$name is required" }
        return Base64.getDecoder().decode(raw)
    }

    private fun readPem(file: File, label: String): ByteArray {
        val text = file.readText(Charsets.US_ASCII)
        val body = text
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
            .replace(Regex("\\s+"), "")
        require(body.isNotBlank()) { "Invalid PEM file: ${file.name}" }
        return Base64.getDecoder().decode(body)
    }

    private fun fingerprint(publicPem: File): String {
        val der = readPem(publicPem, "PUBLIC KEY")
        return MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val CAPABILITY_ID = "host.private.plugin_signing@1"
        const val PACKAGER_PLUGIN_ID = "plugin.system.packager"
        private const val MAX_SIGN_BYTES = 2 * 1024 * 1024
    }
}
