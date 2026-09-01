package com.ai.limbs.plugins.extensionhub

import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionBinder
import com.ai.limbs.plugin.runtime.ChildExtensionBinding
import com.ai.limbs.plugin.runtime.ChildExtensionEntry
import com.ai.limbs.plugin.runtime.ChildExtensionHandle
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import com.ai.limbs.plugin.runtime.ChildExtensionLifecycle
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ExtensionHubEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val service = ExtensionHubServiceImpl(host)
        service.start()
        host.registerProvider(InProcessSystemIds.EXTENSION_HUB_PROVIDER, service,
            mapOf("format" to ExtensionPackage.FORMAT, "package_extension" to ExtensionPackage.SUFFIX))
        return InProcessPluginHandle { service.stop() }
    }
}

private object ExtensionPackage {
    const val FORMAT = "AIL_EXTENSION_V1"
    const val SCHEMA = 1
    const val SUFFIX = ".ailx"
    const val MANIFEST = "extension.json"
}

private data class ExtensionManifest(
    val extensionId: String,
    val version: String,
    val displayName: String,
    val description: String?,
    val target: ChildExtensionTarget,
    val entry: String,
    val entryClass: String,
    val requestedCapabilities: Set<String>,
    val roles: Set<String>,
    val rawJson: String
)

private data class PointRegistration(
    val ownerPluginId: String,
    val point: String,
    val apiVersion: Int,
    val title: String,
    val allowedHostCapabilities: Set<String>,
    val binder: ChildExtensionBinder,
    val token: String
)

private data class StoredExtension(
    val manifest: ExtensionManifest,
    var enabled: Boolean,
    var lifecycle: ChildExtensionLifecycle,
    var lastError: String? = null
)

private data class ActiveChild(
    val handle: ChildExtensionHandle,
    val bindingHandle: AutoCloseable?,
    val scope: CoroutineScope
)

private class ExtensionHubServiceImpl(
    private val host: InProcessPluginHost
) : ExtensionHubService {
    private val root = File(host.dataDir, "extension_store")
    private val staging = File(root, "staging")
    private val extensionsRoot = File(root, "extensions")
    private val dataRoot = File(root, "data")
    private val backupsRoot = File(root, "backups")
    private val usageFile = File(root, "usage.json")
    private val backupPolicyFile = File(root, "backup_policy.json")
    private val points = ConcurrentHashMap<String, PointRegistration>()
    private val records = ConcurrentHashMap<String, StoredExtension>()
    private val active = ConcurrentHashMap<String, ActiveChild>()
    private val mutableSnapshots = MutableStateFlow<List<ChildExtensionSnapshot>>(emptyList())
    private val mutableBackupSnapshots = MutableStateFlow<List<ChildExtensionBackupSnapshot>>(emptyList())
    private val pointFlows = ConcurrentHashMap<String, MutableStateFlow<List<ChildExtensionSnapshot>>>()
    private val usageCounts = ConcurrentHashMap<String, Long>()
    @Volatile private var autoBackupEnabled = false
    @Volatile private var highFrequencyUseCount = 10L

    suspend fun start() {
        root.mkdirs(); staging.mkdirs(); extensionsRoot.mkdirs(); dataRoot.mkdirs(); backupsRoot.mkdirs()
        staging.listFiles()?.forEach { it.deleteRecursively() }
        loadUsage()
        loadBackupPolicy()
        restoreInstalled()
        publishSnapshots()
        publishBackupSnapshots()
        reconcileAutoBackup()
    }

    suspend fun stop() {
        active.keys.toList().forEach { stopChild(it) }
        points.clear()
        publishSnapshots()
    }

    override fun publishPoint(
        ownerPluginId: String,
        point: String,
        apiVersion: Int,
        title: String,
        description: String,
        allowedHostCapabilities: Set<String>,
        binder: ChildExtensionBinder
    ): AutoCloseable {
        require(ownerPluginId.isNotBlank() && point.matches(ID_PATTERN) && apiVersion > 0)
        val token = UUID.randomUUID().toString()
        val registration = PointRegistration(ownerPluginId, point, apiVersion, title, allowedHostCapabilities.toSet(), binder, token)
        check(points.putIfAbsent(point, registration) == null) { "Extension point already published: $point" }
        host.scope.launch { reconcilePoint(point) }
        return AutoCloseable {
            val removed = points.computeIfPresent(point) { _, current -> if (current.token == token) null else current }
            if (removed == null) host.scope.launch {
                records.values.filter { it.manifest.target.point == point }.forEach { record ->
                    stopChild(record.manifest.extensionId)
                    if (record.enabled) {
                        record.lifecycle = ChildExtensionLifecycle.BLOCKED
                        record.lastError = "Parent extension point is not active: $point"
                        persistState(record)
                    }
                }
                publishSnapshots()
            }
        }
    }

    override suspend fun install(
        packageFile: File,
        expectedParentPluginId: String?,
        expectedPoint: String?
    ): ChildExtensionSnapshot {
        require(packageFile.isFile) { "Extension package is missing" }
        require(packageFile.name.lowercase().endsWith(ExtensionPackage.SUFFIX)) { "Expected ${ExtensionPackage.SUFFIX} package" }
        val stage = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val manifest = verifyAndExtract(packageFile, stage)
            expectedParentPluginId?.let { require(manifest.target.parentPluginId == it) { "Extension targets ${manifest.target.parentPluginId}, expected $it" } }
            expectedPoint?.let { require(manifest.target.point == it) { "Extension targets ${manifest.target.point}, expected $it" } }
            points[manifest.target.point]?.takeIf {
                it.ownerPluginId == manifest.target.parentPluginId && it.apiVersion == manifest.target.apiVersion
            }?.let { point -> requireAllowedCapabilities(manifest, point) }
            stopChild(manifest.extensionId)
            val destination = extensionDir(manifest.extensionId)
            val replacement = File(extensionsRoot, ".replace-${UUID.randomUUID()}")
            if (replacement.exists()) replacement.deleteRecursively()
            require(stage.renameTo(replacement)) { "Could not stage installed extension" }
            if (destination.exists()) destination.deleteRecursively()
            require(replacement.renameTo(destination)) { "Could not commit extension package" }
            val record = StoredExtension(manifest, enabled = true, lifecycle = ChildExtensionLifecycle.INSTALLED)
            records[manifest.extensionId] = record
            persistState(record)
            tryActivate(record)
            autoBackupIfEligible(record)
            publishSnapshots()
            publishBackupSnapshots()
            return snapshot(record)
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    override suspend fun uninstall(extensionId: String): Boolean {
        val record = records.remove(extensionId) ?: return false
        stopChild(extensionId)
        extensionDir(extensionId).deleteRecursively()
        File(dataRoot, extensionId).deleteRecursively()
        publishSnapshots()
        publishBackupSnapshots()
        return record.manifest.extensionId == extensionId
    }

    override suspend fun setEnabled(extensionId: String, enabled: Boolean): ChildExtensionSnapshot {
        val record = records[extensionId] ?: error("Unknown child extension: $extensionId")
        record.enabled = enabled
        if (enabled) {
            tryActivate(record)
        } else {
            stopChild(extensionId)
            record.lifecycle = ChildExtensionLifecycle.DISABLED
            record.lastError = null
            persistState(record)
        }
        publishSnapshots()
        return snapshot(record)
    }

    override suspend fun backup(extensionId: String): ChildExtensionBackupSnapshot {
        val record = records[extensionId] ?: error("Child extension is not installed: $extensionId")
        readBackup(extensionId)?.takeIf { it.version == record.manifest.version }?.let { return it }
        val temp = File(backupsRoot, ".tmp-${UUID.randomUUID()}")
        require(temp.mkdirs()) { "Could not create child backup staging directory" }
        try {
            val packageFile = File(temp, "package${ExtensionPackage.SUFFIX}")
            packInstalledExtension(record, packageFile)
            val metadata = JSONObject()
                .put("extension_id", extensionId)
                .put("version", record.manifest.version)
                .put("package_sha256", sha256(packageFile))
                .put("backed_up_at", System.currentTimeMillis())
                .put("was_enabled", record.enabled)
            File(temp, "backup.json").writeText(metadata.toString(2))
            replaceBackup(extensionId, temp)
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
        publishBackupSnapshots()
        return readBackup(extensionId) ?: error("Child extension backup could not be read after write")
    }

    override suspend fun restoreBackup(extensionId: String): ChildExtensionSnapshot {
        check(extensionId !in records) { "Child extension is already installed: $extensionId" }
        val backup = readBackup(extensionId) ?: error("Child extension backup does not exist: $extensionId")
        val packageFile = backupPackage(extensionId)
        check(sha256(packageFile) == backup.packageSha256) { "Child extension backup digest mismatch: $extensionId" }
        val point = points[backup.target.point] ?: error("Parent extension point is not active: ${backup.target.point}")
        check(point.ownerPluginId == backup.target.parentPluginId && point.apiVersion == backup.target.apiVersion) {
            "Parent/point/API contract mismatch for backup $extensionId"
        }
        var restored = install(packageFile, backup.target.parentPluginId, backup.target.point)
        if (!backup.wasEnabled) restored = setEnabled(extensionId, false)
        publishBackupSnapshots()
        return restored
    }

    override suspend fun deleteBackup(extensionId: String): Boolean {
        val dir = backupDir(extensionId)
        val existed = dir.exists()
        if (existed) dir.deleteRecursively()
        publishBackupSnapshots()
        return existed
    }

    override suspend fun setAutoBackupPolicy(enabled: Boolean, highFrequencyUseCount: Long) {
        require(highFrequencyUseCount > 0L)
        autoBackupEnabled = enabled
        this.highFrequencyUseCount = highFrequencyUseCount
        persistBackupPolicy()
        if (enabled) reconcileAutoBackup()
    }

    override fun recordUse(extensionId: String) {
        val record = records[extensionId] ?: return
        val count = (usageCounts[extensionId] ?: 0L) + 1L
        usageCounts[extensionId] = count
        persistUsage()
        publishSnapshots()
        if (autoBackupEnabled && count >= highFrequencyUseCount) {
            host.scope.launch { runCatching { autoBackupIfEligible(record) } }
        }
    }

    override fun snapshots(): StateFlow<List<ChildExtensionSnapshot>> = mutableSnapshots.asStateFlow()

    override fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>> =
        pointFlows.computeIfAbsent(point) { MutableStateFlow(filterPoint(point)) }.asStateFlow()

    override fun backupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>> =
        mutableBackupSnapshots.asStateFlow()

    private suspend fun restoreInstalled() {
        extensionsRoot.listFiles()?.filter(File::isDirectory)?.forEach { dir ->
            runCatching {
                val raw = File(dir, ExtensionPackage.MANIFEST).readText()
                val manifest = parseManifest(raw)
                val state = readState(dir)
                records[manifest.extensionId] = StoredExtension(
                    manifest = manifest,
                    enabled = state.first,
                    lifecycle = if (state.first) ChildExtensionLifecycle.BLOCKED else ChildExtensionLifecycle.DISABLED,
                    lastError = if (state.first) "Waiting for parent extension point" else null
                )
            }
        }
    }

    private suspend fun reconcilePoint(point: String) {
        for (record in records.values.filter { it.enabled && it.manifest.target.point == point }) {
            tryActivate(record)
        }
        publishSnapshots()
    }

    private suspend fun tryActivate(record: StoredExtension) {
        stopChild(record.manifest.extensionId)
        if (!record.enabled) {
            record.lifecycle = ChildExtensionLifecycle.DISABLED
            record.lastError = null
            persistState(record)
            return
        }
        val point = points[record.manifest.target.point]
        if (point == null) {
            record.lifecycle = ChildExtensionLifecycle.BLOCKED
            record.lastError = "Parent extension point is not active: ${record.manifest.target.point}"
            persistState(record)
            return
        }
        if (point.ownerPluginId != record.manifest.target.parentPluginId || point.apiVersion != record.manifest.target.apiVersion) {
            record.lifecycle = ChildExtensionLifecycle.BLOCKED
            record.lastError = "Parent/point/API contract mismatch"
            persistState(record)
            return
        }
        val deniedCapabilities = record.manifest.requestedCapabilities - point.allowedHostCapabilities
        if (deniedCapabilities.isNotEmpty()) {
            record.lifecycle = ChildExtensionLifecycle.FAILED
            record.lastError = "Parent extension point does not delegate: ${deniedCapabilities.sorted().joinToString()}"
            persistState(record)
            return
        }
        record.lifecycle = ChildExtensionLifecycle.INSTALLED
        record.lastError = null
        val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var bindingHandle: AutoCloseable? = null
        try {
            val childHost = object : ChildExtensionHost {
                override val applicationContext = host.applicationContext
                override val extensionId = record.manifest.extensionId
                override val version = record.manifest.version
                override val target = record.manifest.target
                override val scope = childScope
                override val dataDir = File(dataRoot, extensionId).apply { mkdirs() }
                override val cacheDir = File(host.cacheDir, "child/$extensionId").apply { mkdirs() }
                override fun publish(payload: Any, metadata: Map<String, String>) {
                    check(bindingHandle == null) { "Child extension may publish only one binding" }
                    bindingHandle = point.binder.bind(
                        ChildExtensionBinding(extensionId, version, target, record.manifest.displayName, metadata, payload)
                    )
                }
                override suspend fun invokeHostCapability(id: String, parametersJson: String): String {
                    check(id in record.manifest.requestedCapabilities) { "Child extension did not declare host capability: $id" }
                    recordUse(record.manifest.extensionId)
                    return host.invokeHostCapability(id, parametersJson)
                }
            }
            val apk = File(extensionDir(record.manifest.extensionId), record.manifest.entry)
            val loader = DexClassLoader(apk.absolutePath, childHost.cacheDir.absolutePath, null, host.applicationContext.classLoader)
            val instance = loader.loadClass(record.manifest.entryClass).getDeclaredConstructor().newInstance()
            val entry = instance as? ChildExtensionEntry ?: error("${record.manifest.entryClass} does not implement ChildExtensionEntry")
            val handle = entry.mount(childHost)
            check(bindingHandle != null) { "Child extension mounted without publishing its binding" }
            active[record.manifest.extensionId] = ActiveChild(handle, bindingHandle, childScope)
            record.lifecycle = ChildExtensionLifecycle.ACTIVE
            record.lastError = null
        } catch (error: Throwable) {
            runCatching { bindingHandle?.close() }
            childScope.cancel()
            record.lifecycle = ChildExtensionLifecycle.FAILED
            record.lastError = error.message ?: error::class.java.simpleName
        }
        persistState(record)
    }

    private suspend fun stopChild(extensionId: String) {
        val mounted = active.remove(extensionId) ?: return
        runCatching { mounted.bindingHandle?.close() }
        runCatching { mounted.handle.stop() }
        mounted.scope.cancel()
    }

    private fun verifyAndExtract(packageFile: File, destination: File): ExtensionManifest {
        var manifestRaw: String? = null
        val executable = linkedSetOf<String>()
        val seen = linkedSetOf<String>()
        var entries = 0
        var total = 0L
        ZipFile(packageFile).use { zip ->
            val items = zip.entries()
            while (items.hasMoreElements()) {
                val entry = items.nextElement(); entries++
                require(entries <= 256) { "Too many .ailx entries" }
                val name = safePath(entry.name.removeSuffix("/"))
                require(seen.add(name)) { "Duplicate .ailx entry: $name" }
                if (entry.isDirectory) { safeFile(destination, name).mkdirs(); continue }
                if (name.lowercase().endsWith(".apk") || name.lowercase().endsWith(".dex") ||
                    name.lowercase().endsWith(".jar") || name.lowercase().endsWith(".so") || name.lowercase().endsWith(".class")) executable += name
                val out = safeFile(destination, name); out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(out).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) { val n=input.read(buffer); if (n<0) break; total += n; require(total <= 128L*1024L*1024L) { ".ailx expands too large" }; output.write(buffer,0,n) }
                } }
                if (name == ExtensionPackage.MANIFEST) manifestRaw = out.readText()
            }
        }
        val raw = manifestRaw ?: error("Root ${ExtensionPackage.MANIFEST} is required")
        val manifest = parseManifest(raw)
        require(executable == setOf(manifest.entry)) { ".ailx may contain only its declared APK executable" }
        val runtimeApk = File(destination, manifest.entry)
        require(runtimeApk.isFile) { "Child runtime APK is missing" }
        require(runtimeApk.setReadOnly()) { "Could not make child runtime APK read-only" }
        File(destination, "package.sha256").writeText(sha256(packageFile))
        return manifest
    }

    private fun parseManifest(raw: String): ExtensionManifest {
        val root = JSONObject(raw)
        require(root.getString("format") == ExtensionPackage.FORMAT) { "Expected ${ExtensionPackage.FORMAT}" }
        require(root.getInt("schema_version") == ExtensionPackage.SCHEMA) { "Unsupported extension schema" }
        val id = root.getString("extension_id").trim(); require(ID_PATTERN.matches(id)) { "Invalid extension_id" }
        val version = root.getString("version").trim(); require(SEMVER.matches(version)) { "Invalid extension version" }
        val display = root.getJSONObject("display")
        val name = display.getString("name").trim(); require(name.isNotBlank()) { "display.name is required" }
        val description = display.optString("description").trim().ifBlank { null }
        val targetObject = root.getJSONObject("target")
        val parent = targetObject.getString("plugin_id").trim(); require(ID_PATTERN.matches(parent)) { "Invalid target plugin_id" }
        val point = targetObject.getString("extension_point").trim(); require(ID_PATTERN.matches(point)) { "Invalid extension_point" }
        val api = targetObject.getInt("api"); require(api > 0) { "target.api must be positive" }
        val runtime = root.getJSONObject("runtime")
        require(runtime.getString("kind") == "android_child") { "Child runtime.kind must be android_child" }
        val entry = safePath(runtime.getString("entry")); require(entry.lowercase().endsWith(".apk")) { "Child runtime entry must be APK" }
        val entryClass = runtime.getJSONObject("config").getString("entry_class").trim(); require(CLASS_PATTERN.matches(entryClass)) { "Invalid entry_class" }
        val requested = root.optJSONObject("permissions")?.optJSONArray("host_capabilities")?.strings() ?: emptySet()
        requested.forEach { require(ID_PATTERN.matches(it)) { "Invalid host capability id: $it" } }
        val roles = root.optJSONArray("roles")?.strings() ?: emptySet()
        roles.forEach { require(ID_PATTERN.matches(it)) { "Invalid extension role: $it" } }
        return ExtensionManifest(
            id, version, name, description, ChildExtensionTarget(parent, point, api),
            entry, entryClass, requested, roles, raw
        )
    }

    private fun backupDir(extensionId: String) = File(backupsRoot, extensionId)
    private fun backupPackage(extensionId: String) = File(backupDir(extensionId), "package${ExtensionPackage.SUFFIX}")

    private fun readBackup(extensionId: String): ChildExtensionBackupSnapshot? {
        val dir = backupDir(extensionId)
        val packageFile = backupPackage(extensionId)
        val metadataFile = File(dir, "backup.json")
        if (!packageFile.isFile || !metadataFile.isFile) return null
        return runCatching {
            val metadata = JSONObject(metadataFile.readText())
            val manifest = inspectBackupManifest(packageFile)
            require(metadata.getString("extension_id") == manifest.extensionId)
            require(metadata.getString("version") == manifest.version)
            val installed = records[manifest.extensionId]
            ChildExtensionBackupSnapshot(
                extensionId = manifest.extensionId,
                version = manifest.version,
                displayName = manifest.displayName,
                description = manifest.description,
                target = manifest.target,
                roles = manifest.roles,
                packageSha256 = metadata.getString("package_sha256"),
                backedUpAtEpochMs = metadata.getLong("backed_up_at"),
                wasEnabled = metadata.optBoolean("was_enabled", false),
                installed = installed != null,
                installedVersion = installed?.manifest?.version
            )
        }.getOrNull()
    }

    private fun inspectBackupManifest(packageFile: File): ExtensionManifest =
        ZipFile(packageFile).use { zip ->
            val entry = zip.getEntry(ExtensionPackage.MANIFEST)
                ?: error("Backup is missing ${ExtensionPackage.MANIFEST}")
            val raw = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            parseManifest(raw)
        }

    private fun packInstalledExtension(record: StoredExtension, output: File) {
        val rootDir = extensionDir(record.manifest.extensionId)
        val excluded = setOf("state.json", "package.sha256")
        val files = rootDir.walkTopDown()
            .filter(File::isFile)
            .map { file -> file to file.relativeTo(rootDir).invariantSeparatorsPath }
            .filter { (_, path) -> path !in excluded }
            .sortedBy { it.second }
            .toList()
        require(files.any { it.second == ExtensionPackage.MANIFEST }) { "Installed extension manifest is missing" }
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            files.forEach { (file, relative) ->
                val entry = ZipEntry(safePath(relative)).apply { time = 0L }
                zip.putNextEntry(entry)
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun replaceBackup(extensionId: String, staged: File) {
        val target = backupDir(extensionId)
        val previous = File(backupsRoot, ".old-${UUID.randomUUID()}")
        if (target.exists()) require(target.renameTo(previous)) { "Could not move previous child backup aside" }
        if (!staged.renameTo(target)) {
            if (previous.exists()) previous.renameTo(target)
            error("Could not commit child extension backup")
        }
        if (previous.exists()) previous.deleteRecursively()
    }

    private fun publishBackupSnapshots() {
        mutableBackupSnapshots.value = backupsRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { readBackup(it.name) }
            ?.sortedWith(compareBy({ it.displayName.lowercase() }, { it.extensionId }))
            .orEmpty()
    }

    private fun loadUsage() {
        usageCounts.clear()
        val root = runCatching { JSONObject(usageFile.readText()) }.getOrNull() ?: return
        root.keys().forEach { key -> usageCounts[key] = root.optLong(key, 0L).coerceAtLeast(0L) }
    }

    private fun persistUsage() {
        val root = JSONObject()
        usageCounts.toSortedMap().forEach { (id, count) -> root.put(id, count) }
        usageFile.writeText(root.toString(2))
    }

    private fun loadBackupPolicy() {
        val root = runCatching { JSONObject(backupPolicyFile.readText()) }.getOrNull() ?: return
        autoBackupEnabled = root.optBoolean("enabled", false)
        highFrequencyUseCount = root.optLong("high_frequency_use_count", 10L).coerceAtLeast(1L)
    }

    private fun persistBackupPolicy() {
        backupPolicyFile.writeText(
            JSONObject()
                .put("enabled", autoBackupEnabled)
                .put("high_frequency_use_count", highFrequencyUseCount)
                .toString(2)
        )
    }

    private suspend fun reconcileAutoBackup() {
        if (!autoBackupEnabled) return
        for (record in records.values) autoBackupIfEligible(record)
    }

    private suspend fun autoBackupIfEligible(record: StoredExtension) {
        if (!autoBackupEnabled) return
        val systemExtension = record.manifest.roles.any { it in SYSTEM_EXTENSION_ROLES }
        val highFrequency = (usageCounts[record.manifest.extensionId] ?: 0L) >= highFrequencyUseCount
        if (!systemExtension && !highFrequency) return
        if (readBackup(record.manifest.extensionId)?.version == record.manifest.version) return
        backup(record.manifest.extensionId)
    }

    private fun requireAllowedCapabilities(manifest: ExtensionManifest, point: PointRegistration) {
        val denied = manifest.requestedCapabilities - point.allowedHostCapabilities
        require(denied.isEmpty()) {
            "Extension requests host capabilities not delegated by ${point.point}: ${denied.sorted().joinToString()}"
        }
    }

    private fun readState(dir: File): Pair<Boolean, ChildExtensionLifecycle> {
        val file = File(dir, "state.json")
        if (!file.isFile) return true to ChildExtensionLifecycle.BLOCKED
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return true to ChildExtensionLifecycle.BLOCKED
        return root.optBoolean("enabled", true) to runCatching { ChildExtensionLifecycle.valueOf(root.optString("lifecycle")) }.getOrDefault(ChildExtensionLifecycle.BLOCKED)
    }

    private fun persistState(record: StoredExtension) {
        val dir = extensionDir(record.manifest.extensionId).apply { mkdirs() }
        File(dir, ExtensionPackage.MANIFEST).writeText(record.manifest.rawJson)
        File(dir, "state.json").writeText(JSONObject().put("enabled", record.enabled).put("lifecycle", record.lifecycle.name).put("last_error", record.lastError).toString(2))
    }

    private fun publishSnapshots() {
        val all = records.values.map(::snapshot).sortedBy { it.displayName.lowercase() }
        mutableSnapshots.value = all
        pointFlows.forEach { (point, flow) -> flow.value = all.filter { it.target.point == point } }
    }

    private fun filterPoint(point: String) = mutableSnapshots.value.filter { it.target.point == point }
    private fun snapshot(record: StoredExtension) = ChildExtensionSnapshot(
        extensionId = record.manifest.extensionId,
        version = record.manifest.version,
        displayName = record.manifest.displayName,
        description = record.manifest.description,
        target = record.manifest.target,
        lifecycle = record.lifecycle,
        enabled = record.enabled,
        roles = record.manifest.roles,
        useCount = usageCounts[record.manifest.extensionId] ?: 0L,
        lastError = record.lastError
    )
    private fun extensionDir(id: String) = File(extensionsRoot, id)
    private fun safePath(raw: String): String { val v=raw.trim(); require(v.isNotBlank() && !v.startsWith("/") && !v.contains('\\')); val p=v.split('/'); require(p.none { it.isBlank() || it=="." || it==".." || it.contains(':') }); return p.joinToString("/") }
    private fun safeFile(root: File, relative: String): File { val r=root.canonicalFile; val f=File(r,safePath(relative)).canonicalFile; require(f.path.startsWith(r.path+File.separator)); return f }
    private fun sha256(file: File): String { val d=MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val b=ByteArray(8192); while(true){val n=input.read(b); if(n<0)break; d.update(b,0,n)} }; return d.digest().joinToString(""){"%02x".format(it)} }

    companion object {
        private val SYSTEM_EXTENSION_ROLES = setOf("system", "system_extension", "system_provider")
        private val ID_PATTERN = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
        private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")
        private val CLASS_PATTERN = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
        private fun JSONArray.strings(): Set<String> = buildSet { for (i in 0 until length()) add(getString(i).trim()) }
    }
}
