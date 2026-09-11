package com.ai.limbs.plugins.extensionhub

import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

class ExtensionHubEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val service = ExtensionHubAdmissionService(host)
        host.registerProvider(
            InProcessSystemIds.EXTENSION_HUB_PROVIDER,
            service,
            mapOf(
                "format" to ExtensionPackage.FORMAT,
                "package_extension" to ExtensionPackage.SUFFIX,
                "role" to "admission_only"
            )
        )
        return InProcessPluginHandle { }
    }
}

private object ExtensionPackage {
    const val FORMAT = "AIL_EXTENSION_V1"
    const val SCHEMA = 1
    const val SUFFIX = ".ailx"
    const val MANIFEST = "extension.json"
}

private data class ExtensionIntegritySpec(
    val algorithm: String,
    val entries: Map<String, String>
)

private data class ExtensionSignatureSpec(
    val algorithm: String,
    val signerId: String,
    val entry: String
)

private data class ExtensionManifest(
    val extensionId: String,
    val version: String,
    val target: ChildExtensionTarget,
    val entry: String,
    val entryClass: String,
    val requestedCapabilities: Set<String>,
    val roles: Set<String>,
    val integrity: ExtensionIntegritySpec,
    val signature: ExtensionSignatureSpec
)

private class ExtensionHubAdmissionService(
    private val host: InProcessPluginHost
) : ExtensionHubService {
    private val delegatedGateway = requireNotNull(
        host.services.resolve(DELEGATED_GATEWAY_SERVICE, DELEGATED_GATEWAY_API)
    ) { "Plugin Center delegated gateway is unavailable" }.also { binding ->
        check(binding.ownerPluginId == PLUGIN_CENTER_PLUGIN_ID) {
            "Delegated gateway is not owned by Plugin Center: ${binding.ownerPluginId}"
        }
    }

    private val stagingRoot = File(host.cacheDir, "extension_admission")

    override suspend fun install(
        packageFile: File,
        expectedParentPluginId: String?,
        expectedPoint: String?
    ): ChildExtensionSnapshot {
        require(packageFile.isFile) { "Extension package is missing" }
        require(packageFile.name.lowercase().endsWith(ExtensionPackage.SUFFIX)) {
            "Expected ${ExtensionPackage.SUFFIX} package"
        }
        stagingRoot.mkdirs()
        val stage = File(stagingRoot, UUID.randomUUID().toString()).apply {
            require(mkdirs()) { "Could not create child admission staging directory" }
        }
        try {
            val admittedPackage = File(stage, "candidate${ExtensionPackage.SUFFIX}")
            packageFile.copyTo(admittedPackage)
            require(admittedPackage.setReadOnly()) {
                "Could not make admitted child package read-only"
            }
            val extracted = File(stage, "verified").apply {
                require(mkdirs()) { "Could not create child verification directory" }
            }
            val manifest = verifyAndExtract(admittedPackage, extracted)
            expectedParentPluginId?.let { expected ->
                require(manifest.target.parentPluginId == expected) {
                    "Extension targets ${manifest.target.parentPluginId}, expected $expected"
                }
            }
            expectedPoint?.let { expected ->
                require(manifest.target.point == expected) {
                    "Extension targets ${manifest.target.point}, expected $expected"
                }
            }
            return host.childExtensions.installAdmitted(
                admittedPackage,
                expectedParentPluginId,
                expectedPoint
            )
        } finally {
            stage.deleteRecursively()
        }
    }

    private suspend fun verifyAndExtract(
        packageFile: File,
        destination: File
    ): ExtensionManifest {
        var manifestBytes: ByteArray? = null
        val executableEntries = linkedSetOf<String>()
        val fileEntries = linkedSetOf<String>()
        val seen = linkedSetOf<String>()
        var entryCount = 0
        var expandedBytes = 0L

        ZipFile(packageFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                require(entryCount <= MAX_ENTRY_COUNT) { "Too many .ailx entries" }
                val name = safePath(entry.name.removeSuffix("/"))
                require(seen.add(name)) { "Duplicate .ailx entry: $name" }
                if (entry.isDirectory) {
                    safeFile(destination, name).mkdirs()
                    continue
                }
                fileEntries += name
                if (isExecutablePayload(name)) executableEntries += name
                val outputFile = safeFile(destination, name)
                outputFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            expandedBytes += read
                            require(expandedBytes <= MAX_EXPANDED_BYTES) {
                                ".ailx expands too large"
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (name == ExtensionPackage.MANIFEST) {
                    manifestBytes = outputFile.readBytes()
                }
            }
        }

        val rawManifest = manifestBytes ?: error(
            "Root ${ExtensionPackage.MANIFEST} is required"
        )
        val manifest = parseManifest(rawManifest.toString(Charsets.UTF_8))
        require(executableEntries == setOf(manifest.entry)) {
            ".ailx may contain only its declared APK executable"
        }
        require(manifest.signature.entry in fileEntries) {
            "Child extension signature entry is missing: ${manifest.signature.entry}"
        }
        val payloadEntries = fileEntries - setOf(
            ExtensionPackage.MANIFEST,
            manifest.signature.entry
        )
        require(payloadEntries == manifest.integrity.entries.keys) {
            "Child extension integrity map must cover every payload entry exactly"
        }
        manifest.integrity.entries.forEach { (relative, expectedDigest) ->
            val file = safeFile(destination, relative)
            require(file.isFile) { "Child extension integrity entry is missing: $relative" }
            require(sha256(file) == expectedDigest) {
                "Child extension integrity mismatch: $relative"
            }
        }
        val signatureFile = safeFile(destination, manifest.signature.entry)
        require(signatureFile.isFile) { "Child extension signature file is missing" }
        verifyChildPublisher(manifest, rawManifest, signatureFile.readBytes())
        require(safeFile(destination, manifest.entry).isFile) {
            "Child runtime APK is missing"
        }
        return manifest
    }

    private suspend fun verifyChildPublisher(
        manifest: ExtensionManifest,
        manifestBytes: ByteArray,
        signatureBytes: ByteArray
    ) {
        val parameters = JSONObject()
            .put("signer_id", manifest.signature.signerId)
            .put("payload_base64", Base64.getEncoder().encodeToString(manifestBytes))
            .put("signature_base64", Base64.getEncoder().encodeToString(signatureBytes))
        val result = JSONObject(
            delegatedGateway.invoke(
                "verify_child_publisher",
                parameters.toString()
            )
        )
        require(result.optBoolean("trusted", false)) {
            "Child extension publisher signature verification failed: ${manifest.signature.signerId}"
        }
    }

    private fun parseManifest(raw: String): ExtensionManifest {
        val root = JSONObject(raw)
        require(root.getString("format") == ExtensionPackage.FORMAT) {
            "Expected ${ExtensionPackage.FORMAT}"
        }
        require(root.getInt("schema_version") == ExtensionPackage.SCHEMA) {
            "Unsupported extension schema"
        }
        val extensionId = root.getString("extension_id").trim()
        require(ID_PATTERN.matches(extensionId)) { "Invalid extension_id" }
        val version = root.getString("version").trim()
        require(SEMVER.matches(version)) { "Invalid extension version" }
        val displayName = root.getJSONObject("display").getString("name").trim()
        require(displayName.isNotBlank()) { "display.name is required" }
        val targetObject = root.getJSONObject("target")
        val parentPluginId = targetObject.getString("plugin_id").trim()
        require(ID_PATTERN.matches(parentPluginId)) { "Invalid target plugin_id" }
        val point = targetObject.getString("extension_point").trim()
        require(ID_PATTERN.matches(point)) { "Invalid extension_point" }
        val apiVersion = targetObject.getInt("api")
        require(apiVersion > 0) { "target.api must be positive" }

        val runtime = root.getJSONObject("runtime")
        require(runtime.getString("kind") == "android_child") {
            "Child runtime.kind must be android_child"
        }
        val entry = safePath(runtime.getString("entry"))
        require(entry.lowercase().endsWith(".apk")) { "Child runtime entry must be APK" }
        val entryClass = runtime.getJSONObject("config")
            .getString("entry_class")
            .trim()
        require(CLASS_PATTERN.matches(entryClass)) { "Invalid entry_class" }

        val requestedCapabilities = root.optJSONObject("permissions")
            ?.optJSONArray("host_capabilities")
            ?.strings()
            ?: emptySet()
        requestedCapabilities.forEach { capabilityId ->
            require(HOST_CAPABILITY_ID_PATTERN.matches(capabilityId)) {
                "Invalid host capability id: $capabilityId"
            }
        }
        val roles = root.optJSONArray("roles")?.strings() ?: emptySet()
        roles.forEach { role ->
            require(ID_PATTERN.matches(role)) { "Invalid extension role: $role" }
        }

        val integrityObject = root.optJSONObject("integrity")
            ?: error("Child extension integrity block is required")
        val integrityAlgorithm = integrityObject.optString("algorithm").trim()
        require(integrityAlgorithm == "SHA-256") {
            "Child extension integrity algorithm must be SHA-256"
        }
        val entriesObject = integrityObject.optJSONObject("entries")
            ?: error("Child extension integrity.entries is required")
        val integrityEntries = linkedMapOf<String, String>()
        val entryKeys = entriesObject.keys()
        while (entryKeys.hasNext()) {
            val rawKey = entryKeys.next()
            val relative = safePath(rawKey)
            require(relative != ExtensionPackage.MANIFEST) {
                "extension.json must not appear in integrity.entries"
            }
            val digest = entriesObject.getString(rawKey).trim().lowercase()
            require(SHA256_PATTERN.matches(digest)) {
                "Invalid SHA-256 digest for $relative"
            }
            require(integrityEntries.put(relative, digest) == null) {
                "Duplicate integrity entry: $relative"
            }
        }
        require(integrityEntries.isNotEmpty()) {
            "Child extension integrity.entries must not be empty"
        }
        require(entry in integrityEntries) {
            "Child runtime APK must be covered by integrity.entries"
        }

        val signatureObject = root.optJSONObject("signature")
            ?: error("Child extension publisher signature is required")
        val signatureAlgorithm = signatureObject.optString("algorithm").trim()
        require(signatureAlgorithm == "Ed25519") {
            "Child extension signature algorithm must be Ed25519"
        }
        val signerId = signatureObject.optString("signer_id").trim()
        require(ID_PATTERN.matches(signerId)) { "Invalid child extension signer_id" }
        val signatureEntry = safePath(signatureObject.optString("entry"))
        require(signatureEntry != ExtensionPackage.MANIFEST) {
            "Child extension signature entry cannot be extension.json"
        }
        require(signatureEntry !in integrityEntries) {
            "Child extension signature entry must not be hashed by integrity.entries"
        }

        return ExtensionManifest(
            extensionId = extensionId,
            version = version,
            target = ChildExtensionTarget(parentPluginId, point, apiVersion),
            entry = entry,
            entryClass = entryClass,
            requestedCapabilities = requestedCapabilities,
            roles = roles,
            integrity = ExtensionIntegritySpec(integrityAlgorithm, integrityEntries),
            signature = ExtensionSignatureSpec(
                signatureAlgorithm,
                signerId,
                signatureEntry
            )
        )
    }

    private fun isExecutablePayload(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".apk") ||
            lower.endsWith(".dex") ||
            lower.endsWith(".jar") ||
            lower.endsWith(".so") ||
            lower.endsWith(".class")
    }

    private fun safePath(raw: String): String {
        val value = raw.trim()
        require(value.isNotBlank() && !value.startsWith("/") && !value.contains('\\')) {
            "Unsafe child package path: $raw"
        }
        val parts = value.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.contains(':') }) {
            "Unsafe child package path: $raw"
        }
        return parts.joinToString("/")
    }

    private fun safeFile(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, safePath(relative)).canonicalFile
        require(file.path.startsWith(canonicalRoot.path + File.separator)) {
            "Child package path escapes staging root: $relative"
        }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONArray.strings(): Set<String> = buildSet {
        for (index in 0 until length()) {
            add(getString(index).trim())
        }
    }

    companion object {
        private const val MAX_ENTRY_COUNT = 256
        private const val MAX_EXPANDED_BYTES = 128L * 1024L * 1024L
        private const val PLUGIN_CENTER_PLUGIN_ID = "ai_limbs.system.plugin_center"
        private const val DELEGATED_GATEWAY_SERVICE = "system.plugin_center.delegated_gateway"
        private const val DELEGATED_GATEWAY_API = 1

        private val ID_PATTERN = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
        private val HOST_CAPABILITY_ID_PATTERN =
            Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:@[1-9][0-9]*)?$")
        private val SEMVER = Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$"
        )
        private val CLASS_PATTERN = Regex(
            "^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+$"
        )
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
