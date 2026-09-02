package com.ai.limbs.plugins.packager

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

enum class PackagerArtifactType(
    val extension: String,
    val manifestEntry: String,
    val signingProfile: String
) {
    SYSTEM("ailpsys", "system-plugin.json", "system_plugin"),
    PARENT("ailp", "plugin.json", "parent_plugin"),
    CHILD("ailx", "extension.json", "child_extension")
}

data class PackagerInspection(
    val input: File,
    val packageName: String,
    val version: String,
    val displayName: String,
    val type: PackagerArtifactType,
    val manifest: JSONObject,
    val manifestSource: String
)
class PackagerEngine(private val host: InProcessPluginHost) {
    private val context = host.applicationContext
    private val packageManager = context.packageManager

    fun inspect(inputPath: String, manifestPath: String? = null): PackagerInspection {
        val input = requireSharedApk(inputPath)
        val packageInfo = readPackageInfo(input)
        val packageName = packageInfo.packageName
        val version = packageInfo.versionName?.trim().orEmpty()
        require(version.isNotBlank()) { "APK versionName is missing" }

        val official = officialManifest(packageName, version)
        if (official != null) {
            return PackagerInspection(
                input = input,
                packageName = packageName,
                version = version,
                displayName = official.second.optJSONObject("display")?.optString("name").orEmpty(),
                type = official.first,
                manifest = official.second,
                manifestSource = "official_profile"
            )
        }

        val template = resolveTemplate(input, manifestPath)
            ?: error("Unknown APK. Put plugin.json / extension.json / system-plugin.json beside it, or provide a template path.")
        val root = JSONObject(template.readText(Charsets.UTF_8))
        val type = typeForFormat(root.optString("format"))
        val templateVersion = root.optString("version").trim()
        require(templateVersion == version) {
            "Version mismatch: APK=$version, manifest=$templateVersion"
        }
        root.remove("signature")
        root.remove("integrity")
        val displayName = root.optJSONObject("display")?.optString("name")?.trim().orEmpty()
            .ifBlank { packageName }
        return PackagerInspection(
            input = input,
            packageName = packageName,
            version = version,
            displayName = displayName,
            type = type,
            manifest = root,
            manifestSource = template.absolutePath
        )
    }

    suspend fun packageAndVerify(
        inputPath: String,
        manifestPath: String? = null,
        outputDirectory: String? = null
    ): JSONObject {
        val inspection = inspect(inputPath, manifestPath)
        val manifest = JSONObject(inspection.manifest.toString())
        val payloadEntry = manifest.requiredRuntimeEntry()
        val payloadHash = sha256(inspection.input)
        val signerId = signerIdFor(inspection.type)
        val signatureEntry = "META-INF/AILIMBS.SIG"
        manifest.put(
            "integrity",
            JSONObject()
                .put("algorithm", "SHA-256")
                .put("entries", JSONObject().put(payloadEntry, payloadHash))
        )
        manifest.put(
            "signature",
            JSONObject()
                .put("algorithm", "Ed25519")
                .put("signer_id", signerId)
                .put("entry", signatureEntry)
        )
        val manifestBytes = canonicalJson(manifest).toByteArray(Charsets.UTF_8)
        val signing = signingRequest(
            operation = "sign",
            profile = inspection.type.signingProfile,
            data = manifestBytes
        )
        require(signing.optString("status") == "OK") {
            signing.optString("message").ifBlank { "Signing failed" }
        }
        require(signing.optString("signer_id") == signerId) { "Unexpected signer identity" }
        val signature = Base64.getDecoder().decode(signing.getString("signature_base64"))

        val outputDir = resolveOutputDirectory(inspection.input, outputDirectory)
        val outputName = outputName(inspection.displayName, inspection.version, inspection.type)
        val output = File(outputDir, outputName)
        val temporary = File(outputDir, ".$outputName.tmp")
        if (temporary.exists()) temporary.delete()
        ZipOutputStream(FileOutputStream(temporary).buffered()).use { zip ->
            zip.writeEntry(inspection.type.manifestEntry, manifestBytes)
            zip.writeEntry(payloadEntry, inspection.input.readBytes())
            zip.writeEntry(signatureEntry, signature)
        }
        val verification = verifyPackage(
            temporary,
            inspection.type,
            inspection.version,
            inspection.displayName,
            payloadEntry,
            payloadHash,
            manifestBytes,
            signature
        )
        require(verification.optBoolean("verified")) {
            verification.optString("message").ifBlank { "Package verification failed" }
        }
        if (output.exists() && !output.delete()) error("Could not replace existing output")
        if (!temporary.renameTo(output)) {
            temporary.copyTo(output, overwrite = true)
            temporary.delete()
        }
        return verification
            .put("status", "OK")
            .put("output_path", output.absolutePath)
            .put("output_name", output.name)
            .put("package_sha256", sha256(output))
            .put("signer_id", signerId)
            .put("fingerprint_sha256", signing.optString("fingerprint_sha256"))
    }
    fun inspectJson(inputPath: String, manifestPath: String? = null): JSONObject {
        val item = inspect(inputPath, manifestPath)
        return JSONObject()
            .put("status", "OK")
            .put("input_path", item.input.absolutePath)
            .put("package_name", item.packageName)
            .put("display_name", item.displayName)
            .put("version", item.version)
            .put("artifact_type", item.type.name.lowercase())
            .put("output_name", outputName(item.displayName, item.version, item.type))
            .put("manifest_source", item.manifestSource)
            .put("signing_profile", item.type.signingProfile)
            .put("signer_id", signerIdFor(item.type))
    }

    fun scanDownloads(limit: Int = 10): List<File> {
        val root = File("/storage/emulated/0/Download")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .sortedByDescending(File::lastModified)
            .take(limit.coerceIn(1, 20))
            .toList()
    }

    suspend fun signingStatus(import: Boolean): JSONObject = signingRequest(
        operation = if (import) "import" else "status",
        profile = null,
        data = null
    )
    private suspend fun verifyPackage(
        file: File,
        type: PackagerArtifactType,
        expectedVersion: String,
        expectedName: String,
        payloadEntry: String,
        expectedPayloadHash: String,
        expectedManifestBytes: ByteArray,
        expectedSignature: ByteArray
    ): JSONObject {
        val archiveManifest: ByteArray
        val archivePayloadHash: String
        val archiveSignature: ByteArray
        ZipFile(file).use { zip ->
            archiveManifest = zip.getInputStream(
                zip.getEntry(type.manifestEntry) ?: error("Manifest entry is missing")
            ).readBytes()
            archivePayloadHash = sha256(
                zip.getInputStream(zip.getEntry(payloadEntry) ?: error("Payload entry is missing"))
            )
            archiveSignature = zip.getInputStream(
                zip.getEntry("META-INF/AILIMBS.SIG") ?: error("Signature entry is missing")
            ).readBytes()
        }
        require(archiveManifest.contentEquals(expectedManifestBytes)) { "Manifest changed while packaging" }
        require(archivePayloadHash == expectedPayloadHash) { "Payload SHA-256 mismatch" }
        require(archiveSignature.contentEquals(expectedSignature)) { "Signature changed while packaging" }
        val manifest = JSONObject(archiveManifest.toString(Charsets.UTF_8))
        require(manifest.optString("version") == expectedVersion) { "Packaged version mismatch" }
        val display = manifest.optJSONObject("display")?.optString("name")?.trim().orEmpty()
        require(display == expectedName) { "Packaged display name mismatch" }
        val verify = signingRequest(
            operation = "verify",
            profile = type.signingProfile,
            data = archiveManifest,
            signature = archiveSignature
        )
        val verified = verify.optString("status") == "OK" && verify.optBoolean("verified")
        return JSONObject()
            .put("verified", verified)
            .put("manifest_verified", true)
            .put("payload_sha256_verified", true)
            .put("signature_verified", verified)
            .put("artifact_type", type.name.lowercase())
            .put("display_name", expectedName)
            .put("version", expectedVersion)
            .put("message", if (verified) "Package, integrity and Ed25519 signature verified" else verify.optString("message"))
    }

    private suspend fun signingRequest(
        operation: String,
        profile: String?,
        data: ByteArray?,
        signature: ByteArray? = null
    ): JSONObject {
        val request = JSONObject().put("operation", operation)
        if (profile != null) request.put("profile", profile)
        if (data != null) request.put("data_base64", Base64.getEncoder().encodeToString(data))
        if (signature != null) request.put("signature_base64", Base64.getEncoder().encodeToString(signature))
        return JSONObject(host.invokeHostCapability(PRIVATE_SIGNING_CAPABILITY, request.toString()))
    }
    private fun resolveTemplate(input: File, explicitPath: String?): File? {
        if (!explicitPath.isNullOrBlank()) {
            val explicit = requireSharedFile(explicitPath)
            require(explicit.extension.equals("json", ignoreCase = true)) { "Manifest template must be JSON" }
            return explicit
        }
        return listOf("plugin.json", "extension.json", "system-plugin.json")
            .map { File(input.parentFile, it) }
            .firstOrNull(File::isFile)
    }

    private fun typeForFormat(format: String): PackagerArtifactType = when (format) {
        "AIL_SYSTEM_PLUGIN_V1" -> PackagerArtifactType.SYSTEM
        "AIL_PLUGIN_V1" -> PackagerArtifactType.PARENT
        "AIL_EXTENSION_V1" -> PackagerArtifactType.CHILD
        else -> error("Unsupported package format: $format")
    }

    private fun requireSharedApk(path: String): File {
        val file = requireSharedFile(path)
        require(file.extension.equals("apk", ignoreCase = true)) { "Input must be an APK" }
        return file
    }

    private fun requireSharedFile(path: String): File {
        val file = File(path.trim()).canonicalFile
        require(file.isFile) { "File does not exist: ${file.path}" }
        val sharedRoot = File("/storage/emulated/0").canonicalFile
        require(file.path.startsWith(sharedRoot.path + File.separator)) {
            "Packager only reads files from shared storage"
        }
        return file
    }
    private fun resolveOutputDirectory(input: File, raw: String?): File {
        val directory = if (raw.isNullOrBlank()) input.parentFile else File(raw.trim()).canonicalFile
        require(directory != null) { "Output directory is unavailable" }
        val sharedRoot = File("/storage/emulated/0").canonicalFile
        require(directory.path == sharedRoot.path || directory.path.startsWith(sharedRoot.path + File.separator)) {
            "Output must stay in shared storage"
        }
        if (!directory.exists() && !directory.mkdirs()) error("Could not create output directory")
        require(directory.isDirectory) { "Output path is not a directory" }
        return directory
    }

    @Suppress("DEPRECATION")
    private fun readPackageInfo(file: File): PackageInfo {
        val flags = PackageManager.GET_META_DATA
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
        return info ?: error("Android could not parse this APK")
    }

    private fun outputName(name: String, version: String, type: PackagerArtifactType): String {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifBlank { "AI Limbs Plugin" }
        return "$safeName v$version.${type.extension}"
    }
    private fun sha256(file: File): String = file.inputStream().buffered().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(java.util.zip.ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalJson(value.get(index))
        }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
    private fun officialManifest(packageName: String, version: String): Pair<PackagerArtifactType, JSONObject>? =
        OfficialPackageProfiles.resolve(packageName, version)

    private fun signerIdFor(type: PackagerArtifactType): String = when (type) {
        PackagerArtifactType.SYSTEM -> "ai-limbs-plugin-center-dev-v1"
        PackagerArtifactType.PARENT -> "ai-limbs-parent-plugin-dev-v1"
        PackagerArtifactType.CHILD -> "ai-limbs-child-extension-dev-v1"
    }

    private fun JSONObject.requiredRuntimeEntry(): String {
        val value = optJSONObject("runtime")?.optString("entry")?.trim().orEmpty()
        require(value.isNotBlank()) { "runtime.entry is missing" }
        require(!value.startsWith("/") && !value.contains("..") && !value.contains('\\')) {
            "runtime.entry is unsafe"
        }
        return value
    }

    companion object {
        private const val PRIVATE_SIGNING_CAPABILITY = "host.private.plugin_signing@1"
    }
}
