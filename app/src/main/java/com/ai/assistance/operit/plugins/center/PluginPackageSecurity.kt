package com.ai.assistance.operit.plugins.center

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object PluginPackagePaths {
    fun requireSafeRelativePath(raw: String): String {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') || value.contains('\\')) {
            throw PluginManifestException("PACKAGE_PATH_INVALID", "Unsafe package path: $raw")
        }
        val parts = value.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains(':') }) {
            throw PluginManifestException("PACKAGE_PATH_INVALID", "Unsafe package path: $raw")
        }
        return parts.joinToString("/")
    }
}

data class PluginInstallLimits(
    val maxEntries: Int = 4096,
    val maxManifestBytes: Long = 512L * 1024L,
    val maxSingleEntryBytes: Long = 256L * 1024L * 1024L,
    val maxTotalExtractedBytes: Long = 512L * 1024L * 1024L
)

data class VerifiedPluginPackage(
    val manifest: PluginManifest,
    val packageSha256: String,
    val extractedBytes: Long,
    val entryCount: Int
)

internal class PluginPackageVerifier(
    private val identityRegistry: OfficialPluginIdentityRegistry,
    private val limits: PluginInstallLimits = PluginInstallLimits()
) {
    fun verifyAndExtract(managedPackage: File, contentDir: File): VerifiedPluginPackage {
        if (!managedPackage.isFile) throw PluginInstallException("PACKAGE_MISSING", "Managed .ailp package is missing")
        val digest = sha256(managedPackage)
        if (contentDir.exists()) contentDir.deleteRecursively()
        require(contentDir.mkdirs()) { "Could not create plugin extraction directory" }
        var entryCount = 0
        var totalBytes = 0L
        var manifestRaw: String? = null
        val executableEntries = linkedSetOf<String>()
        val fileEntries = linkedSetOf<String>()
        val seen = HashSet<String>()
        ZipFile(managedPackage).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                if (entryCount > limits.maxEntries) throw PluginInstallException("PACKAGE_TOO_MANY_ENTRIES", "Plugin package has too many entries")
                val normalized = PluginPackagePaths.requireSafeRelativePath(entry.name.removeSuffix("/"))
                if (!seen.add(normalized)) throw PluginInstallException("PACKAGE_DUPLICATE_ENTRY", "Duplicate entry: $normalized")
                if (entry.isDirectory) {
                    safeOutputFile(contentDir, normalized).mkdirs()
                    continue
                }
                fileEntries += normalized
                val lowerName = normalized.lowercase()
                if (FORBIDDEN_EXECUTABLE_SUFFIXES.any(lowerName::endsWith)) {
                    executableEntries += normalized
                }
                val declaredSize = entry.size
                if (declaredSize > limits.maxSingleEntryBytes) throw PluginInstallException("PACKAGE_ENTRY_TOO_LARGE", "Entry too large: $normalized")
                val output = safeOutputFile(contentDir, normalized)
                output.parentFile?.mkdirs()
                var entryBytes = 0L
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(output).buffered().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            if (entryBytes > limits.maxSingleEntryBytes) throw PluginInstallException("PACKAGE_ENTRY_TOO_LARGE", "Entry too large: $normalized")
                            if (totalBytes > limits.maxTotalExtractedBytes) throw PluginInstallException("PACKAGE_TOO_LARGE", "Plugin package expands beyond the allowed limit")
                            out.write(buffer, 0, read)
                        }
                    }
                }
                if (normalized == PluginAbi.MANIFEST_ENTRY) {
                    if (entryBytes > limits.maxManifestBytes) throw PluginInstallException("MANIFEST_TOO_LARGE", "plugin.json is too large")
                    manifestRaw = output.readText(Charsets.UTF_8)
                }
            }
        }
        val raw = manifestRaw ?: throw PluginInstallException("MANIFEST_MISSING", "Root plugin.json is required")
        val manifest = PluginManifestParser.parse(raw)
        validateIntegrity(manifest, contentDir, fileEntries)
        validateExecutablePayloads(manifest, executableEntries)
        manifest.runtime.entry?.let { entry ->
            val runtimeFile = safeOutputFile(contentDir, entry)
            if (!runtimeFile.isFile) throw PluginInstallException("RUNTIME_ENTRY_MISSING", "Runtime entry does not exist: $entry")
            if (manifest.runtime.kind == "android_inprocess" && !runtimeFile.setReadOnly()) {
                throw PluginInstallException("RUNTIME_ENTRY_READONLY_FAILED", "Could not make dynamic runtime APK read-only: $entry")
            }
        }
        manifest.display.iconEntry?.let { icon ->
            if (!safeOutputFile(contentDir, icon).isFile) throw PluginInstallException("ICON_ENTRY_MISSING", "Display icon does not exist: $icon")
        }
        manifest.signature?.let { signature ->
            if (!safeOutputFile(contentDir, signature.signatureEntry).isFile) throw PluginInstallException("SIGNATURE_ENTRY_MISSING", "Signature entry does not exist")
        }
        return VerifiedPluginPackage(manifest, digest, totalBytes, entryCount)
    }


    private fun validateIntegrity(
        manifest: PluginManifest,
        contentDir: File,
        fileEntries: Set<String>
    ) {
        val integrity = manifest.integrity ?: return
        val signatureEntry = manifest.signature?.signatureEntry
        val payloadEntries =
            fileEntries
                .filterNot { it == PluginAbi.MANIFEST_ENTRY || it == signatureEntry }
                .toSet()
        if (integrity.entries.keys != payloadEntries) {
            val missing = payloadEntries - integrity.entries.keys
            val extra = integrity.entries.keys - payloadEntries
            throw PluginInstallException(
                "INTEGRITY_COVERAGE_INVALID",
                "Integrity map must cover every payload entry exactly; missing=$missing extra=$extra"
            )
        }
        integrity.entries.forEach { (path, expected) ->
            val file = safeOutputFile(contentDir, path)
            if (!file.isFile) {
                throw PluginInstallException("INTEGRITY_ENTRY_MISSING", "Missing payload entry: $path")
            }
            val actual = sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw PluginInstallException("INTEGRITY_HASH_MISMATCH", "SHA-256 mismatch: $path")
            }
        }
        manifest.runtime.entry?.let { runtimeEntry ->
            if (runtimeEntry !in integrity.entries) {
                throw PluginInstallException(
                    "RUNTIME_NOT_IN_INTEGRITY_MAP",
                    "Runtime entry must be integrity-protected"
                )
            }
        }
    }

    private fun validateExecutablePayloads(manifest: PluginManifest, entries: Set<String>) {
        if (entries.isEmpty()) return
        val entry = manifest.runtime.entry
        val valid = manifest.runtime.kind == OfficialPluginIdentityRegistry.RUNTIME_ANDROID_INPROCESS &&
            identityRegistry.isApproved(manifest) &&
            entry != null && entry.lowercase().endsWith(".apk") &&
            entries == setOf(entry)
        if (!valid) {
            throw PluginInstallException(
                "PACKAGE_EXECUTABLE_FORBIDDEN",
                "Executable payloads are restricted to the declared APK of approved system plugins"
            )
        }
    }
    private fun safeOutputFile(root: File, relative: String): File {
        val normalized = PluginPackagePaths.requireSafeRelativePath(relative)
        val candidate = File(root, normalized).canonicalFile
        val canonicalRoot = root.canonicalFile
        val prefix = canonicalRoot.path + File.separator
        if (candidate.path != canonicalRoot.path && !candidate.path.startsWith(prefix)) {
            throw PluginInstallException("PACKAGE_PATH_ESCAPE", "Package path escapes plugin root: $relative")
        }
        return candidate
    }

    companion object {
        private val FORBIDDEN_EXECUTABLE_SUFFIXES =
            setOf(".apk", ".class", ".dex", ".jar", ".so")
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
