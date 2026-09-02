package com.ai.assistance.operit.plugins.system

import com.ai.assistance.operit.plugins.center.PluginPackageVerifier
import com.ai.assistance.operit.plugins.center.SemanticVersion
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

object SystemPluginPackageValidator {
    private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 512L * 1024L
    private const val MAX_SIGNATURE_BYTES = 64L * 1024L
    private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
    private const val MAX_ENTRIES = 4096
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    private val PLUGIN_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{2,127}$")
    private val SYSTEM_ENTRY_CLASS = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")

    fun validateForPluginCenterBootstrap(
        sourcePackage: File,
        originalFileName: String
    ): SystemPluginValidationResult {
        val result = validate(sourcePackage, originalFileName)
        if (result.manifest.role != SystemPluginProtocolV1.ROLE_PLUGIN_CENTER) {
            fail(
                "SYSTEM_ROLE_MISMATCH",
                "Bootstrap slot only accepts system.role=plugin_center"
            )
        }
        return result
    }

    fun validate(
        sourcePackage: File,
        originalFileName: String = sourcePackage.name
    ): SystemPluginValidationResult {
        if (!sourcePackage.isFile) fail("SOURCE_MISSING", "System plugin package does not exist")
        if (!originalFileName.lowercase().endsWith(SystemPluginProtocolV1.PACKAGE_EXTENSION)) {
            fail(
                "PACKAGE_EXTENSION_INVALID",
                "System plugin package must use ${SystemPluginProtocolV1.PACKAGE_EXTENSION}"
            )
        }
        if (sourcePackage.length() > MAX_ARCHIVE_BYTES) {
            fail("PACKAGE_TOO_LARGE", "System plugin archive exceeds 512 MiB")
        }

        return try {
            ZipFile(sourcePackage).use { archive -> validateArchive(archive, sourcePackage) }
        } catch (error: SystemPluginProtocolException) {
            throw error
        } catch (error: Throwable) {
            fail("PACKAGE_ARCHIVE_INVALID", "Invalid .ailpsys archive: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun validateArchive(archive: ZipFile, sourcePackage: File): SystemPluginValidationResult {
        val files = linkedMapOf<String, ZipEntry>()
        var entryCount = 0
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount += 1
            if (entryCount > MAX_ENTRIES) fail("PACKAGE_TOO_MANY_ENTRIES", "Too many archive entries")
            val normalized = safeRelativePath(entry.name.removeSuffix("/"))
            if (files.containsKey(normalized)) fail("PACKAGE_DUPLICATE_ENTRY", "Duplicate entry: $normalized")
            if (!entry.isDirectory) {
                if (entry.size > MAX_ENTRY_BYTES) fail("PACKAGE_ENTRY_TOO_LARGE", "Entry too large: $normalized")
                files[normalized] = entry
            }
        }

        val manifestEntry = files[SystemPluginProtocolV1.MANIFEST_ENTRY]
            ?: fail("MANIFEST_MISSING", "Root ${SystemPluginProtocolV1.MANIFEST_ENTRY} is required")
        val manifestBytes = readBounded(archive, manifestEntry, MAX_MANIFEST_BYTES, "MANIFEST_TOO_LARGE")
        val root = try {
            JSONObject(manifestBytes.toString(Charsets.UTF_8))
        } catch (error: Throwable) {
            fail("MANIFEST_JSON_INVALID", "system-plugin.json is not valid JSON")
        }

        requireString(root, "format").also {
            if (it != SystemPluginProtocolV1.FORMAT) {
                fail("FORMAT_INVALID", "format must be ${SystemPluginProtocolV1.FORMAT}")
            }
        }
        val schemaVersion = requireInt(root, "schema_version")
        if (schemaVersion != SystemPluginProtocolV1.SCHEMA_VERSION) {
            fail("SCHEMA_VERSION_UNSUPPORTED", "Unsupported system plugin schema: $schemaVersion")
        }

        val pluginId = requireString(root, "plugin_id")
        if (!PLUGIN_ID_PATTERN.matches(pluginId) || !pluginId.startsWith("ai_limbs.system.")) {
            fail("PLUGIN_ID_INVALID", "System plugin id must use ai_limbs.system.* namespace")
        }
        val version = requireString(root, "version")
        if (SemanticVersion.parse(version) == null) fail("VERSION_INVALID", "version must be SemVer")

        val displayJson = requireObject(root, "display")
        val displayName = requireString(displayJson, "name")
        val displayDescription = optionalString(displayJson, "description")

        val systemJson = requireObject(root, "system")
        val role = requireString(systemJson, "role")
        if (role !in SystemPluginProtocolV1.supportedRoles) {
            fail("SYSTEM_ROLE_UNSUPPORTED", "Unsupported system role: $role")
        }
        val hostAbiJson = requireObject(systemJson, "host_abi")
        val abiMin = requireInt(hostAbiJson, "min")
        val abiMax = requireInt(hostAbiJson, "max")
        if (abiMin <= 0 || abiMax < abiMin) fail("HOST_ABI_INVALID", "Invalid host ABI range")
        if (SystemPluginProtocolV1.HOST_ABI !in abiMin..abiMax) {
            fail(
                "HOST_ABI_INCOMPATIBLE",
                "Package requires host ABI $abiMin..$abiMax; current is ${SystemPluginProtocolV1.HOST_ABI}"
            )
        }

        val runtimeJson = requireObject(root, "runtime")
        val runtimeKind = requireString(runtimeJson, "kind").lowercase()
        if (runtimeKind !in SystemPluginProtocolV1.supportedRuntimeKinds) {
            fail("RUNTIME_KIND_UNSUPPORTED", "Unsupported system runtime: $runtimeKind")
        }
        val runtimeEntry = safeRelativePath(requireString(runtimeJson, "entry"))
        if (!files.containsKey(runtimeEntry)) fail("RUNTIME_ENTRY_MISSING", "Runtime entry is missing: $runtimeEntry")
        val runtimeEntryClass = runtimeJson.optString("entry_class").trim().ifBlank { null }
        if (runtimeKind == "android_inprocess") {
            val className = runtimeEntryClass
                ?: fail("RUNTIME_ENTRY_CLASS_MISSING", "android_inprocess requires runtime.entry_class")
            if (!SYSTEM_ENTRY_CLASS.matches(className)) {
                fail("RUNTIME_ENTRY_CLASS_INVALID", "Invalid runtime.entry_class: $className")
            }
        }

        val requestedScopes = parseRequestedScopes(root.optJSONObject("permissions"))

        val signatureJson = requireObject(root, "signature")
        val signatureAlgorithm = requireString(signatureJson, "algorithm")
        if (signatureAlgorithm != "Ed25519") fail("SIGNATURE_ALGORITHM_UNSUPPORTED", "V1 requires Ed25519")
        val signerId = requireString(signatureJson, "signer_id")
        val signatureEntryName = safeRelativePath(requireString(signatureJson, "entry"))
        val signatureEntry = files[signatureEntryName]
            ?: fail("SIGNATURE_ENTRY_MISSING", "Signature entry is missing: $signatureEntryName")
        val signatureBytes = readBounded(archive, signatureEntry, MAX_SIGNATURE_BYTES, "SIGNATURE_ENTRY_TOO_LARGE")
        if (signatureBytes.isEmpty()) fail("SIGNATURE_ENTRY_EMPTY", "Signature entry is empty")

        val integrityJson = requireObject(root, "integrity")
        val integrityAlgorithm = requireString(integrityJson, "algorithm")
        if (integrityAlgorithm != "SHA-256") fail("INTEGRITY_ALGORITHM_UNSUPPORTED", "V1 requires SHA-256")
        val expectedHashes = parseIntegrityEntries(requireObject(integrityJson, "entries"))
        val payloadFiles = files.keys - SystemPluginProtocolV1.MANIFEST_ENTRY - signatureEntryName
        if (expectedHashes.keys != payloadFiles) {
            val missing = payloadFiles - expectedHashes.keys
            val extra = expectedHashes.keys - payloadFiles
            fail(
                "INTEGRITY_COVERAGE_INVALID",
                "Integrity map must cover every payload entry exactly; missing=$missing extra=$extra"
            )
        }
        expectedHashes.forEach { (path, expected) ->
            val entry = files[path] ?: fail("INTEGRITY_ENTRY_MISSING", "Missing payload entry: $path")
            val actual = archive.getInputStream(entry).use(::sha256Hex)
            if (!actual.equals(expected, ignoreCase = true)) {
                fail("INTEGRITY_HASH_MISMATCH", "SHA-256 mismatch: $path")
            }
        }
        if (runtimeEntry !in expectedHashes) {
            fail("RUNTIME_NOT_IN_INTEGRITY_MAP", "Runtime entry must be integrity-protected")
        }

        val manifest = SystemPluginManifestV1(
            pluginId = pluginId,
            version = version,
            display = SystemPluginDisplaySpec(displayName, displayDescription),
            role = role,
            hostAbi = SystemPluginHostAbiSpec(abiMin, abiMax),
            runtime = SystemPluginRuntimeSpec(runtimeKind, runtimeEntry, runtimeEntryClass),
            requestedScopes = requestedScopes,
            signature = SystemPluginSignatureSpec(signatureAlgorithm, signerId, signatureEntryName)
        )
        val trustStatus = SystemPluginTrustV1.verify(
            signerId = signerId,
            role = role,
            manifestBytes = manifestBytes,
            signatureBytes = signatureBytes
        )
        return SystemPluginValidationResult(
            manifest = manifest,
            packageSha256 = PluginPackageVerifier.sha256(sourcePackage),
            entryCount = entryCount,
            verifiedPayloadEntries = expectedHashes.size,
            trustStatus = trustStatus
        )
    }

    private fun parseRequestedScopes(permissions: JSONObject?): Set<String> {
        if (permissions == null) return emptySet()
        val array = permissions.optJSONArray("requested_scopes") ?: return emptySet()
        return stringSet(array, "permissions.requested_scopes")
    }

    private fun parseIntegrityEntries(entries: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val rawPath = keys.next()
            val path = safeRelativePath(rawPath)
            val hash = entries.optString(rawPath, "").trim()
            if (!SHA256_PATTERN.matches(hash)) fail("INTEGRITY_HASH_INVALID", "Invalid SHA-256 for $path")
            if (result.put(path, hash.lowercase()) != null) fail("INTEGRITY_DUPLICATE_PATH", "Duplicate integrity path: $path")
        }
        if (result.isEmpty()) fail("INTEGRITY_EMPTY", "integrity.entries must not be empty")
        return result
    }

    private fun stringSet(array: JSONArray, field: String): Set<String> {
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index, "").trim()
            if (value.isBlank()) fail("FIELD_INVALID", "$field contains an empty value")
            if (!result.add(value)) fail("FIELD_DUPLICATE", "$field contains duplicate value: $value")
        }
        return result
    }

    private fun safeRelativePath(raw: String): String {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') || value.contains('\\')) {
            fail("PACKAGE_PATH_INVALID", "Unsafe package path: $raw")
        }
        val parts = value.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains(':') }) {
            fail("PACKAGE_PATH_INVALID", "Unsafe package path: $raw")
        }
        return parts.joinToString("/")
    }

    private fun readBounded(archive: ZipFile, entry: ZipEntry, max: Long, code: String): ByteArray {
        archive.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > max) fail(code, "Archive entry exceeds allowed size: ${entry.name}")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireObject(parent: JSONObject, key: String): JSONObject =
        parent.optJSONObject(key) ?: fail("FIELD_MISSING", "$key must be an object")

    private fun requireString(parent: JSONObject, key: String): String =
        parent.optString(key, "").trim().takeIf { it.isNotBlank() }
            ?: fail("FIELD_MISSING", "$key is required")

    private fun optionalString(parent: JSONObject, key: String): String? =
        parent.optString(key, "").trim().takeIf { it.isNotBlank() }

    private fun requireInt(parent: JSONObject, key: String): Int {
        if (!parent.has(key)) fail("FIELD_MISSING", "$key is required")
        return try {
            parent.getInt(key)
        } catch (error: Throwable) {
            fail("FIELD_INVALID", "$key must be an integer")
        }
    }

    private fun fail(code: String, message: String): Nothing =
        throw SystemPluginProtocolException(code, message)
}
