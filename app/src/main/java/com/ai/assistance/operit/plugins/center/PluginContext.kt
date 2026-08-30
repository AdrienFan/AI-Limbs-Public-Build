package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

data class PluginStorageEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long
)

interface PluginSandboxDirectory {
    fun exists(relativePath: String = ""): Boolean
    fun readBytes(relativePath: String): ByteArray
    fun writeBytes(relativePath: String, data: ByteArray)
    fun delete(relativePath: String): Boolean
    fun list(relativePath: String = ""): List<PluginStorageEntry>
}

internal class FilePluginSandboxDirectory(rootDir: File) : PluginSandboxDirectory {
    private val root = rootDir.apply { mkdirs() }.canonicalFile

    override fun exists(relativePath: String): Boolean = resolve(relativePath).exists()
    override fun readBytes(relativePath: String): ByteArray {
        val target = resolve(relativePath)
        if (!target.isFile) {
            throw PluginInstallException("PLUGIN_STORAGE_NOT_FILE", "Not a plugin file: $relativePath")
        }
        return target.readBytes()
    }

    override fun writeBytes(relativePath: String, data: ByteArray) {
        val target = resolve(relativePath, allowRoot = false)
        target.parentFile?.mkdirs()
        target.writeBytes(data)
    }

    override fun delete(relativePath: String): Boolean {
        val target = resolve(relativePath, allowRoot = false)
        return if (target.isDirectory) target.deleteRecursively() else !target.exists() || target.delete()
    }

    override fun list(relativePath: String): List<PluginStorageEntry> {
        val directory = resolve(relativePath)
        if (!directory.isDirectory) return emptyList()
        val prefix = normalize(relativePath).trimEnd('/')
        return directory.listFiles().orEmpty().sortedBy { it.name }.map { child ->
            PluginStorageEntry(
                relativePath = if (prefix.isBlank()) child.name else "$prefix/${child.name}",
                isDirectory = child.isDirectory,
                size = if (child.isFile) child.length() else 0L
            )
        }
    }

    private fun resolve(relativePath: String, allowRoot: Boolean = true): File {
        val normalized = normalize(relativePath)
        if (normalized.startsWith('/') || DRIVE_PREFIX.containsMatchIn(normalized)) {
            throw invalidPath(relativePath)
        }
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "." || it == ".." }) throw invalidPath(relativePath)
        if (!allowRoot && segments.isEmpty()) throw invalidPath(relativePath)
        val candidate = segments.fold(root) { current, segment -> File(current, segment) }.canonicalFile
        val insideRoot = candidate == root || candidate.path.startsWith(root.path + File.separator)
        if (!insideRoot) throw invalidPath(relativePath)
        return candidate
    }

    private fun normalize(relativePath: String): String = relativePath.trim().replace('\\', '/')

    private fun invalidPath(raw: String) =
        PluginInstallException("PLUGIN_STORAGE_PATH_INVALID", "Path escapes plugin sandbox: $raw")

    private companion object {
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}

fun interface PluginCapabilityInvoker {
    suspend fun invoke(capabilityId: String, parameters: JSONObject): JSONObject
}

internal interface PluginCapabilityInvokerFactory {
    fun create(ownerPluginId: String): PluginCapabilityInvoker
}

fun interface PluginServiceEndpoint {
    suspend fun invoke(operation: String, parameters: JSONObject): JSONObject
}

data class PluginResolvedService internal constructor(
    val serviceId: String,
    val apiVersion: Int,
    val metadata: Map<String, String>,
    private val endpoint: PluginServiceEndpoint
) {
    suspend fun invoke(operation: String, parameters: JSONObject = JSONObject()): JSONObject =
        endpoint.invoke(operation, JSONObject(parameters.toString()))
}

interface PluginServiceResolver {
    fun resolve(serviceId: String, minApi: Int? = null): PluginResolvedService?
}

internal class ScopedPluginServiceResolver(
    private val manifest: PluginManifest,
    private val contributions: PluginContributionRegistry
) : PluginServiceResolver {
    override fun resolve(serviceId: String, minApi: Int?): PluginResolvedService? {
        val dependency = manifest.dependencies.services.firstOrNull { it.serviceId == serviceId }
            ?: throw PluginInstallException(
                "SERVICE_ACCESS_NOT_DECLARED",
                "${manifest.pluginId} did not declare service dependency: $serviceId"
            )
        val record = contributions.find(PluginContributionKind.SERVICE, serviceId) ?: return null
        val actualApi = record.apiVersion ?: 0
        val requiredApi = maxOf(minApi ?: 0, dependency.minApi ?: 0)
        if (actualApi < requiredApi) return null
        val endpoint = record.payload as? PluginServiceEndpoint
            ?: throw PluginInstallException(
                "SERVICE_NOT_CALLABLE",
                "Service $serviceId does not expose the controlled PluginServiceEndpoint contract"
            )
        return PluginResolvedService(
            serviceId = serviceId,
            apiVersion = actualApi,
            metadata = record.metadata.toMap(),
            endpoint = endpoint
        )
    }
}

fun interface PluginEventHandler {
    fun onEvent(payload: JSONObject)
}

interface PluginEventBus {
    fun publish(topic: String, payload: JSONObject = JSONObject()): Int
    fun subscribe(topic: String, handler: PluginEventHandler): AutoCloseable
}

internal class PluginEventBusHost {
    private data class Subscriber(
        val id: String,
        val handler: PluginEventHandler
    )

    private val subscribers = ConcurrentHashMap<String, ConcurrentHashMap<String, Subscriber>>()

    fun publish(scopedTopic: String, payload: JSONObject): Int {
        val snapshot = subscribers[scopedTopic]?.values?.toList().orEmpty()
        snapshot.forEach { subscriber ->
            runCatching { subscriber.handler.onEvent(JSONObject(payload.toString())) }
                .onFailure { AppLogger.e("PluginEventBus", "Plugin event handler failed: $scopedTopic", it) }
        }
        return snapshot.size
    }

    fun subscribe(scopedTopic: String, handler: PluginEventHandler): AutoCloseable {
        val id = UUID.randomUUID().toString()
        val bucket = subscribers.computeIfAbsent(scopedTopic) { ConcurrentHashMap() }
        bucket[id] = Subscriber(id, handler)
        return AutoCloseable {
            subscribers[scopedTopic]?.let { current ->
                current.remove(id)
                if (current.isEmpty()) subscribers.remove(scopedTopic, current)
            }
        }
    }
}

internal class ScopedPluginEventBus(
    private val ownerPluginId: String,
    private val host: PluginEventBusHost,
    private val track: (AutoCloseable) -> Unit
) : PluginEventBus {
    override fun publish(topic: String, payload: JSONObject): Int =
        host.publish(scoped(topic), JSONObject(payload.toString()))

    override fun subscribe(topic: String, handler: PluginEventHandler): AutoCloseable {
        val handle = host.subscribe(scoped(topic), handler)
        track(handle)
        return handle
    }

    private fun scoped(topic: String): String {
        val normalized = topic.trim().lowercase()
        if (normalized.isBlank() || !TOPIC.matches(normalized)) {
            throw PluginInstallException("PLUGIN_EVENT_TOPIC_INVALID", "Invalid plugin event topic: $topic")
        }
        return "$ownerPluginId::$normalized"
    }

    private companion object {
        val TOPIC = Regex("^[a-z0-9]+(?:[._:-][a-z0-9]+)*$")
    }
}

interface PluginLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, cause: Throwable? = null)
}

internal class AppPluginLogger(ownerPluginId: String) : PluginLogger {
    private val tag = "Plugin:${ownerPluginId.take(48)}"

    override fun debug(message: String) { AppLogger.d(tag, message) }
    override fun info(message: String) { AppLogger.i(tag, message) }
    override fun warn(message: String) { AppLogger.w(tag, message) }
    override fun error(message: String, cause: Throwable?) {
        if (cause == null) AppLogger.e(tag, message) else AppLogger.e(tag, message, cause)
    }
}

interface PluginSecretAccessor {
    fun get(name: String): String?
}

interface PluginSecretBroker {
    fun readApproved(ownerPluginId: String, name: String): String?
}

object NoApprovedPluginSecretBroker : PluginSecretBroker {
    override fun readApproved(ownerPluginId: String, name: String): String? = null
}

internal class ScopedPluginSecretAccessor(
    private val ownerPluginId: String,
    private val broker: PluginSecretBroker
) : PluginSecretAccessor {
    override fun get(name: String): String? {
        val normalized = name.trim()
        if (normalized.isBlank() || !SECRET_NAME.matches(normalized)) {
            throw PluginInstallException("PLUGIN_SECRET_NAME_INVALID", "Invalid secret name: $name")
        }
        return broker.readApproved(ownerPluginId, normalized)
    }

    private companion object {
        val SECRET_NAME = Regex("^[A-Za-z0-9]+(?:[._:-][A-Za-z0-9]+)*$")
    }
}

class PluginContext internal constructor(
    val pluginId: String,
    val version: String,
    val registrar: PluginRegistrar,
    val serviceResolver: PluginServiceResolver,
    val capabilityInvoker: PluginCapabilityInvoker,
    val eventBus: PluginEventBus,
    val dataDir: PluginSandboxDirectory,
    val cacheDir: PluginSandboxDirectory,
    val logger: PluginLogger,
    val secrets: PluginSecretAccessor
)

internal class PluginContextFactory(
    private val contributions: PluginContributionRegistry,
    private val eventBusHost: PluginEventBusHost,
    private val capabilityInvokerFactory: PluginCapabilityInvokerFactory,
    private val secretBroker: PluginSecretBroker
) {
    fun create(
        manifest: PluginManifest,
        mountScope: PluginMountScope,
        dataDir: File,
        cacheDir: File
    ): PluginContext =
        PluginContext(
            pluginId = manifest.pluginId,
            version = manifest.version,
            registrar = mountScope.registrar,
            serviceResolver = ScopedPluginServiceResolver(manifest, contributions),
            capabilityInvoker = capabilityInvokerFactory.create(manifest.pluginId),
            eventBus = ScopedPluginEventBus(manifest.pluginId, eventBusHost, mountScope::trackOwned),
            dataDir = FilePluginSandboxDirectory(dataDir),
            cacheDir = FilePluginSandboxDirectory(cacheDir),
            logger = AppPluginLogger(manifest.pluginId),
            secrets = ScopedPluginSecretAccessor(manifest.pluginId, secretBroker)
        )
}
