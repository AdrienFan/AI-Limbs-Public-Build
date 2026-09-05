package com.ai.limbs.plugins.packager

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
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

data class PackagerSource(
    val source: String,
    val name: String,
    val lastModified: Long
)

private data class PreparedApk(val file: File, val sourceName: String, val temporary: Boolean)

private data class PackagerInspection(
    val source: String,
    val sourceName: String,
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
    private val resolver = context.contentResolver
    private val signingVault = DevelopmentSigningVault(host.dataDir)

    fun inspectJson(inputSource: String, manifestSource: String? = null): JSONObject {
        val prepared = prepareApk(inputSource)
        return try {
            inspectionJson(inspectPrepared(inputSource, prepared, manifestSource))
        } finally {
            prepared.cleanup()
        }
    }

    suspend fun packageAndVerify(
        inputPath: String,
        manifestPath: String? = null,
        outputDirectory: String? = null
    ): JSONObject {
        val prepared = prepareApk(inputPath)
        try {
            val inspection = inspectPrepared(inputPath, prepared, manifestPath)
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
            val signing = signingVault.sign(inspection.type, manifestBytes)
            require(signing.signerId == signerId) { "Unexpected signer identity" }
            val signature = signing.signature

            val outputDir = resolveOutputDirectory(inspection.input, inspection.source, outputDirectory)
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
                .put("fingerprint_sha256", signing.fingerprintSha256)
        } finally {
            prepared.cleanup()
        }
    }

    /**
     * Scans a user-selected directory. Shared-storage paths remain supported for compatibility;
     * Storage Access Framework tree URIs are the preferred UI source and are treated as opaque input.
     */
    fun scanFolder(source: String, limit: Int = 100): List<PackagerSource> {
        val normalized = source.trim()
        require(normalized.isNotBlank()) { "Folder source is missing" }
        return if (normalized.startsWith("content://")) {
            scanDocumentTree(Uri.parse(normalized), limit.coerceIn(1, 200))
        } else {
            val root = requireSharedDirectory(normalized)
            root.walkTopDown()
                .maxDepth(5)
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .map { PackagerSource(it.absolutePath, it.name, it.lastModified()) }
                .sortedByDescending(PackagerSource::lastModified)
                .take(limit.coerceIn(1, 200))
                .toList()
        }
    }

    fun signingStatus(): JSONObject = signingVault.status()

    fun importSigningKey(type: PackagerArtifactType, source: String): JSONObject {
        val pem = readBinarySource(source)
        return try {
            signingVault.importPrivateKey(type, pem)
        } finally {
            pem.fill(0)
        }
    }

    fun clearSigningKeys(): JSONObject = signingVault.clearAll()

    private fun inspectPrepared(
        source: String,
        prepared: PreparedApk,
        manifestSource: String?
    ): PackagerInspection {
        val packageInfo = readPackageInfo(prepared.file)
        val packageName = packageInfo.packageName
        val version = packageInfo.versionName?.trim().orEmpty()
        require(version.isNotBlank()) { "APK versionName is missing" }

        val official = officialManifest(packageName, version)
        val template = resolveTemplate(prepared.file, source, manifestSource)
        if (template != null) {
            val root = JSONObject(template.second)
            val type = typeForFormat(root.optString("format"))
            val templateVersion = root.optString("version").trim()
            require(templateVersion == version) { "Version mismatch: APK=$version, manifest=$templateVersion" }
            validateOfficialIdentity(packageName, type, root, official)
            root.remove("signature")
            root.remove("integrity")
            val displayName = root.optJSONObject("display")?.optString("name")?.trim().orEmpty().ifBlank { packageName }
            return PackagerInspection(
                source = source,
                sourceName = prepared.sourceName,
                input = prepared.file,
                packageName = packageName,
                version = version,
                displayName = displayName,
                type = type,
                manifest = root,
                manifestSource = template.first
            )
        }

        if (official != null && official.first == PackagerArtifactType.SYSTEM) {
            return PackagerInspection(
                source = source,
                sourceName = prepared.sourceName,
                input = prepared.file,
                packageName = packageName,
                version = version,
                displayName = official.second.optJSONObject("display")?.optString("name").orEmpty(),
                type = official.first,
                manifest = official.second,
                manifestSource = "legacy_system_profile"
            )
        }
        if (official != null) {
            error("Official plugin APK is missing its embedded manifest. Rebuild it with the Plugin Lab manifest embedding convention or provide the manifest explicitly.")
        }
        error("Unknown APK. Embed a plugin manifest in the APK or provide a Manifest template.")
    }

    private fun validateOfficialIdentity(
        packageName: String,
        type: PackagerArtifactType,
        manifest: JSONObject,
        official: Pair<PackagerArtifactType, JSONObject>?
    ) {
        if (official == null) return
        require(type == official.first) {
            "Official APK type mismatch: package=$packageName expected=${official.first.name.lowercase()} actual=${type.name.lowercase()}"
        }
        val identityKey = if (type == PackagerArtifactType.CHILD) "extension_id" else "plugin_id"
        val expectedIdentity = official.second.optString(identityKey).trim()
        val actualIdentity = manifest.optString(identityKey).trim()
        require(expectedIdentity.isNotBlank() && actualIdentity == expectedIdentity) {
            "Official APK identity mismatch: package=$packageName expected=$expectedIdentity actual=$actualIdentity"
        }
    }

    private fun inspectionJson(item: PackagerInspection): JSONObject = JSONObject()
        .put("status", "OK")
        .put("input_path", item.source)
        .put("source_name", item.sourceName)
        .put("package_name", item.packageName)
        .put("display_name", item.displayName)
        .put("version", item.version)
        .put("artifact_type", item.type.name.lowercase())
        .put("artifact_extension", ".${item.type.extension}")
        .put("output_name", outputName(item.displayName, item.version, item.type))
        .put("manifest_source", item.manifestSource)
        .put("signing_profile", item.type.signingProfile)
        .put("signer_id", signerIdFor(item.type))

    private fun prepareApk(source: String): PreparedApk {
        val normalized = source.trim()
        require(normalized.isNotBlank()) { "APK source is missing" }
        if (!normalized.startsWith("content://")) {
            val file = requireSharedApk(normalized)
            return PreparedApk(file, file.name, temporary = false)
        }
        val uri = Uri.parse(normalized)
        val displayName = queryDisplayName(uri).ifBlank { "selected.apk" }
        require(displayName.endsWith(".apk", ignoreCase = true)) { "Selected document is not an APK: $displayName" }
        val temporary = File(context.cacheDir, "packager-input-${UUID.randomUUID()}.apk")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not read selected APK" }
            temporary.outputStream().buffered().use(input::copyTo)
        }
        return PreparedApk(temporary, displayName, temporary = true)
    }

    private fun PreparedApk.cleanup() {
        if (temporary) file.delete()
    }

    private fun resolveTemplate(input: File, inputSource: String, explicitSource: String?): Pair<String, String>? {
        if (!explicitSource.isNullOrBlank()) {
            return explicitSource.trim() to readTextSource(explicitSource.trim())
        }
        resolveEmbeddedTemplate(input)?.let { return it }
        if (inputSource.trim().startsWith("content://")) return null
        return listOf("plugin.json", "extension.json", "system-plugin.json")
            .map { File(input.parentFile, it) }
            .firstOrNull(File::isFile)
            ?.let { it.absolutePath to it.readText(Charsets.UTF_8) }
    }

    private fun resolveEmbeddedTemplate(input: File): Pair<String, String>? {
        ZipFile(input).use { zip ->
            for (entryName in listOf("assets/plugin.json", "assets/extension.json", "assets/system-plugin.json")) {
                val entry = zip.getEntry(entryName) ?: continue
                val body = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                return "apk:$entryName" to body
            }
        }
        return null
    }

    private fun readBinarySource(source: String): ByteArray {
        val normalized = source.trim()
        require(normalized.isNotBlank()) { "私钥来源为空" }
        return if (normalized.startsWith("content://")) {
            resolver.openInputStream(Uri.parse(normalized)).use { input ->
                requireNotNull(input) { "无法读取选择的私钥文件" }
                input.readBytes()
            }
        } else {
            requireSharedFile(normalized).readBytes()
        }
    }

    private fun readTextSource(source: String): String {
        val normalized = source.trim()
        return if (normalized.startsWith("content://")) {
            resolver.openInputStream(Uri.parse(normalized)).use { input ->
                requireNotNull(input) { "Could not read Manifest template" }
                input.readBytes().toString(Charsets.UTF_8)
            }
        } else {
            requireSharedFile(normalized).readText(Charsets.UTF_8)
        }
    }

    private fun scanDocumentTree(treeUri: Uri, limit: Int): List<PackagerSource> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val results = mutableListOf<PackagerSource>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        data class Child(val id: String, val name: String, val mime: String, val modified: Long)

        fun walk(parentId: String, depth: Int) {
            if (depth > 5 || results.size >= limit) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val children = mutableListOf<Child>()
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex).orEmpty()
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                    children += Child(id, name, mime, modified)
                }
            }
            for (child in children) {
                if (results.size >= limit) break
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(child.id, depth + 1)
                } else if (child.name.endsWith(".apk", ignoreCase = true)) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                    results += PackagerSource(uri.toString(), child.name, child.modified)
                }
            }
        }

        walk(rootId, 0)
        return results.sortedByDescending(PackagerSource::lastModified)
    }

    private fun queryDisplayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

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
            archiveManifest = zip.getInputStream(zip.getEntry(type.manifestEntry) ?: error("Manifest entry is missing")).readBytes()
            archivePayloadHash = sha256(zip.getInputStream(zip.getEntry(payloadEntry) ?: error("Payload entry is missing")))
            archiveSignature = zip.getInputStream(zip.getEntry("META-INF/AILIMBS.SIG") ?: error("Signature entry is missing")).readBytes()
        }
        require(archiveManifest.contentEquals(expectedManifestBytes)) { "Manifest changed while packaging" }
        require(archivePayloadHash == expectedPayloadHash) { "Payload SHA-256 mismatch" }
        require(archiveSignature.contentEquals(expectedSignature)) { "Signature changed while packaging" }
        val manifest = JSONObject(archiveManifest.toString(Charsets.UTF_8))
        require(manifest.optString("version") == expectedVersion) { "Packaged version mismatch" }
        val display = manifest.optJSONObject("display")?.optString("name")?.trim().orEmpty()
        require(display == expectedName) { "Packaged display name mismatch" }
        val verified = signingVault.verify(type, archiveManifest, archiveSignature)
        return JSONObject()
            .put("verified", verified)
            .put("manifest_verified", true)
            .put("payload_sha256_verified", true)
            .put("signature_verified", verified)
            .put("artifact_type", type.name.lowercase())
            .put("display_name", expectedName)
            .put("version", expectedVersion)
            .put("message", if (verified) "Package, integrity and Ed25519 signature verified" else "Ed25519 signature verification failed")
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
        require(file.path.startsWith(sharedRoot.path + File.separator)) { "Packager only reads files from shared storage" }
        return file
    }

    private fun requireSharedDirectory(path: String): File {
        val directory = File(path.trim()).canonicalFile
        require(directory.isDirectory) { "Directory does not exist: ${directory.path}" }
        val sharedRoot = File("/storage/emulated/0").canonicalFile
        require(directory.path == sharedRoot.path || directory.path.startsWith(sharedRoot.path + File.separator)) {
            "Packager only scans shared storage"
        }
        return directory
    }

    private fun resolveOutputDirectory(input: File, inputSource: String, raw: String?): File {
        val directory = when {
            !raw.isNullOrBlank() -> File(raw.trim()).canonicalFile
            inputSource.trim().startsWith("content://") -> File("/storage/emulated/0/Download/AI-Limbs-Packager")
            else -> input.parentFile
        }
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
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
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
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> canonicalJson(value.get(index)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun officialManifest(packageName: String, version: String): Pair<PackagerArtifactType, JSONObject>? =
        OfficialPackageProfiles.resolve(packageName, version)

    private fun signerIdFor(type: PackagerArtifactType): String = signingVault.signerId(type)

    private fun JSONObject.requiredRuntimeEntry(): String {
        val value = optJSONObject("runtime")?.optString("entry")?.trim().orEmpty()
        require(value.isNotBlank()) { "runtime.entry is missing" }
        require(!value.startsWith("/") && !value.contains("..") && !value.contains('\\')) { "runtime.entry is unsafe" }
        return value
    }

}
