package com.ai.assistance.operit.plugins.self

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Host lifecycle substrate only. Blank module data remains opaque to the host. */
internal class SelfModuleStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private const val FORMAT = "AIL_SELF_V1"
        private const val MAX_BYTES = 64L * 1024 * 1024
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        private fun copyJson(value: JSONObject) = JSONObject(value.toString())
    }
    private var executingRequestId: String? = null
    private var humanMigrationAuthorized = false
    private val authorization by lazy { SelfModuleAuthorizationPolicy(root, clock, ::save) }
    private val slot get() = File(root, "slot.json")
    private val requests get() = File(root, "requests")
    private fun fail(code: String): Nothing = throw IllegalStateException(code)
    private fun requireSelf(value: Boolean, code: String) { if (!value) fail(code) }
    private fun <T> locked(block: () -> T): T {
        requireSelf(root.mkdirs() || root.isDirectory, "SELF_STORE_UNAVAILABLE")
        return synchronized(locks.computeIfAbsent(root.canonicalPath) { Any() }) {
            RandomAccessFile(File(root, "lifecycle.lock"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }
    }
    // Atomic pointer replacement is the transaction commit. Immutable versions and old data survive.
    private fun atomic(file: File, bytes: ByteArray) {
        requireSelf(file.parentFile.mkdirs() || file.parentFile.isDirectory, "SELF_STORE_UNAVAILABLE")
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Persist directory entries as well as contents before reporting a durable commit.
            var directory: File? = file.parentFile
            val boundary = root.canonicalFile
            while (directory != null && (directory.canonicalFile == boundary || directory.canonicalPath.startsWith(boundary.path + File.separator))) {
                java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
                directory = directory.parentFile
            }
        } finally { temp.delete() }
    }
    private fun save(file: File, json: JSONObject) {
        if ((file == slot || file.parentFile == File(root, "departures")) && executingRequestId != null) json.put("last_completed_request_id", executingRequestId)
        atomic(file, json.toString().toByteArray())
    }
    private fun state(): JSONObject? {
        if (!slot.exists()) return null
        val value = JSONObject(slot.readText())
        // A crash after the durable retirement intent must finish the same slot release.
        if (value.optBoolean("source_uninstalled")) { commitRetirement(value); return null }
        return value
    }
    private fun active(): JSONObject = (state() ?: fail("SELF_NOT_INSTALLED")).also {
        requireSelf(it.getString("lifecycle_state") == "ACTIVE", "SELF_NOT_ACTIVE")
    }
    private fun identity(value: String): String = UUID.fromString(value).toString().also {
        requireSelf(it == value, "SELF_IDENTITY_INVALID")
    }
    private fun key(): JSONObject {
        val file = File(root, "device-key.json")
        if (file.exists()) return JSONObject(file.readText())
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        return JSONObject().put("public", Base64.getEncoder().encodeToString(pair.public.encoded))
            .put("private", Base64.getEncoder().encodeToString(pair.private.encoded)).also { save(file, it) }
    }
    private fun deviceId(publicKey: String) = hash(Base64.getDecoder().decode(publicKey))
    private fun sign(body: JSONObject): JSONObject {
        val keys = key()
        val payload = body.toString()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.getString("private")))))
        signer.update(payload.toByteArray())
        return JSONObject().put("body", payload).put("public_key", keys.getString("public"))
            .put("signature", Base64.getEncoder().encodeToString(signer.sign()))
    }
    private fun verify(receipt: JSONObject, expectedDevice: String): JSONObject {
        val public = receipt.getString("public_key")
        requireSelf(deviceId(public) == expectedDevice, "SELF_RECEIPT_DEVICE_MISMATCH")
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(public))))
        verifier.update(receipt.getString("body").toByteArray())
        requireSelf(verifier.verify(Base64.getDecoder().decode(receipt.getString("signature"))), "SELF_RECEIPT_SIGNATURE_INVALID")
        return JSONObject(receipt.getString("body"))
    }
    private data class Pack(val manifest: JSONObject, val files: Map<String, ByteArray>, val digest: String, val bytes: ByteArray)
    private fun readPack(file: File): Pack {
        requireSelf(file.isFile && file.name.endsWith(".ails", true), "SELF_PACKAGE_EXTENSION_INVALID")
        requireSelf(file.length() in 1..MAX_BYTES, "SELF_PACKAGE_SIZE_INVALID")
        val rawBytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                requireSelf(output.size().toLong() + count <= MAX_BYTES, "SELF_PACKAGE_TOO_LARGE")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val snapshot = File(root, "validation/${UUID.randomUUID()}.ails")
        atomic(snapshot, rawBytes)
        val files = linkedMapOf<String, ByteArray>()
        var bytes = 0L
        try { ZipFile(snapshot).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                requireSelf(!entry.isDirectory && !name.startsWith("/") && !name.contains('\\') &&
                    name.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                    !name.contains(':') && files.size < 2048 && !files.containsKey(name), "SELF_ARCHIVE_ENTRY_INVALID")
                val data = zip.getInputStream(entry).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        requireSelf(bytes <= MAX_BYTES, "SELF_PACKAGE_TOO_LARGE")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                files[name] = data
            }
        }
        } finally { snapshot.delete() }
        val manifest = JSONObject(String(files.remove("self.json") ?: fail("SELF_MANIFEST_MISSING"), Charsets.UTF_8))
        requireSelf(manifest.getString("format") == FORMAT && manifest.getString("module_type") == "self" &&
            manifest.getInt("package_schema_version") == 1, "SELF_PACKAGE_TYPE_INVALID")
        val mode = manifest.optString("identity_mode", "BOUND")
        requireSelf(mode in setOf("CREATE", "BOUND"), "SELF_IDENTITY_MODE_INVALID")
        if (mode == "CREATE") requireSelf(manifest.getString("package_kind") == "module" && manifest.has("identity_id") && manifest.isNull("identity_id"), "SELF_TEMPLATE_IDENTITY_INVALID")
        else identity(manifest.getString("identity_id"))
        requireSelf(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$").matches(manifest.getString("module_version")), "SELF_VERSION_INVALID")
        val integrity = manifest.getJSONObject("integrity")
        requireSelf(integrity.getString("algorithm") == "sha256", "SELF_INTEGRITY_ALGORITHM_INVALID")
        val checks = integrity.getJSONObject("entries")
        requireSelf(checks.keys().asSequence().toSet() == files.keys, "SELF_INTEGRITY_MANIFEST_MISMATCH")
        files.forEach { (name, data) -> requireSelf(hash(data) == checks.getString(name), "SELF_INTEGRITY_FAILED") }
        when (manifest.getString("package_kind")) {
            "module" -> {
                // Program resources are opaque to the kernel; consumers define their own schemas.
                requireSelf(files.keys.all { it == "program/blank.json" || it.startsWith("resources/") } &&
                    files.containsKey("program/blank.json"), "SELF_BLANK_PROGRAM_ONLY")
                val program = JSONObject(String(files.getValue("program/blank.json"), Charsets.UTF_8))
                requireSelf(program.getString("entry") == "blank" && program.getString("module_version") == manifest.getString("module_version"), "SELF_PROGRAM_INVALID")
                requireSelf(manifest.getInt("state_schema_version") > 0, "SELF_SCHEMA_INVALID")
                val schemas = manifest.getJSONArray("compatible_state_schemas")
                requireSelf((0 until schemas.length()).any { schemas.getInt(it) == manifest.getInt("state_schema_version") }, "SELF_SCHEMA_INCOMPATIBLE")
            }
            "migration" -> requireSelf(files.keys.all { it == "program.ails" || it.startsWith("versions/") || it.startsWith("data/") } &&
                files.containsKey("program.ails") && files.containsKey("data/state.json"), "SELF_MIGRATION_CONTENT_INVALID")
            else -> fail("SELF_PACKAGE_KIND_INVALID")
        }
        return Pack(manifest, files, hash(rawBytes), rawBytes)
    }
    private fun program(state: JSONObject) = File(root, "versions/${state.getString("identity_id")}/${state.getString("module_version")}.ails")
    private fun data(state: JSONObject) = File(root, "data/${state.getString("data_id")}")
    private fun programBinding(value: JSONObject, digest: String) = JSONObject()
        .put("identity_id", value.getString("identity_id")).put("data_id", value.getString("data_id"))
        .put("module_version", value.getString("module_version")).put("generation", value.getString("generation"))
        .put("package_sha256", digest)
    private fun checkProgramBinding(binding: JSONObject?) {
        if (binding == null) return
        val value = state() ?: fail("SELF_PROGRAM_BINDING_STALE")
        for (field in listOf("identity_id", "data_id", "module_version", "generation"))
            requireSelf(binding.optString(field).isNotEmpty() && binding.getString(field) == value.getString(field), "SELF_PROGRAM_BINDING_STALE")
        requireSelf(binding.getString("package_sha256") == hash(program(value).readBytes()), "SELF_PROGRAM_BINDING_STALE")
    }
    /** Generic verified package-resource primitive. No HTML, UI schema or presentation semantics. */
    fun resources(args: JSONObject = JSONObject()): JSONObject = locked {
        checkProgramBinding(args.optJSONObject("program_binding"))
        val value = state() ?: return@locked JSONObject().put("success", true).put("available", false)
        val pack = readPack(program(value))
        requireSelf(pack.manifest.getString("package_kind") == "module" && pack.manifest.getString("identity_id") == value.getString("identity_id") &&
            pack.manifest.getString("module_version") == value.getString("module_version"), "SELF_RESOURCE_IDENTITY_MISMATCH")
        val paths = pack.files.keys.filter { it.startsWith("resources/") }
        val result = JSONObject().put("success", true).put("available", paths.isNotEmpty())
            .put("binding", programBinding(value, pack.digest)).put("paths", JSONArray(paths)).put("encoding", "base64")
        val requested = args.optJSONArray("paths")
        if (paths.isNotEmpty() && requested != null) {
            requireSelf(requested.length() in 1..16, "SELF_RESOURCE_REQUEST_INVALID")
            val selected = (0 until requested.length()).map { requested.getString(it) }
            requireSelf(selected.toSet().size == selected.size && selected.all { it in paths }, "SELF_RESOURCE_MISSING")
            // Bound individual RPC payloads; large bundles can be read in separate explicit calls.
            requireSelf(selected.sumOf { pack.files.getValue(it).size.toLong() } <= 512 * 1024L, "SELF_RESOURCE_RESPONSE_TOO_LARGE")
            val content = JSONObject()
            selected.forEach { path -> content.put(path, Base64.getEncoder().encodeToString(pack.files.getValue(path))) }
            result.put("resources", content)
        }
        result
    }
    /** Bind a fresh-program template to one durable instance; versions remain identity-bound. */
    private fun bindProgram(pack: Pack, id: String): Pack {
        if (pack.manifest.optString("identity_mode", "BOUND") != "CREATE") return pack
        val manifest = copyJson(pack.manifest).put("identity_mode", "BOUND").put("identity_id", identity(id))
        val file = File(root, "validation/${UUID.randomUUID()}.ails")
        return try { writePack(file, manifest, pack.files); readPack(file) } finally { file.delete() }
    }
    private fun newInstance(pack: Pack): Pack {
        if (pack.manifest.optString("identity_mode", "BOUND") != "CREATE") return pack
        // Persist allocation before installation, so retrying a crashed install keeps its identity.
        val allocation = File(root, "allocations/${pack.digest}.json")
        val allocated = if (allocation.exists()) identity(JSONObject(allocation.readText()).getString("identity_id")) else null
        val id = if (allocated != null && departures().none { it.getString("identity_id") == allocated }) allocated
        else UUID.randomUUID().toString().also { save(allocation, JSONObject().put("identity_id", it)) }
        return bindProgram(pack, id)
    }
    private fun persistProgram(pack: Pack) {
        val target = File(root, "versions/${pack.manifest.getString("identity_id")}/${pack.manifest.getString("module_version")}.ails")
        if (target.exists()) requireSelf(hash(target.readBytes()) == pack.digest, "SELF_VERSION_DIGEST_CONFLICT")
        else atomic(target, pack.bytes)
    }
    private fun metadata(manifest: JSONObject, dataId: String) = JSONObject()
        .put("identity_id", manifest.getString("identity_id"))
        .put("module_version", manifest.getString("module_version"))
        .put("state_schema_version", manifest.getInt("state_schema_version"))
        .put("lifecycle_state", "ACTIVE").put("migration_id", JSONObject.NULL)
        .put("generation", UUID.randomUUID().toString()).put("data_id", dataId)
    private fun departures(): List<JSONObject> = File(root, "departures").listFiles()?.filter { it.extension == "json" }
        ?.map { JSONObject(it.readText()) }?.filter { !it.optBoolean("cancelled") } ?: emptyList()
    fun status(): JSONObject = locked {
        val pending = requests.listFiles()?.filter { it.extension == "json" }?.map { file ->
            val request = JSONObject(file.readText())
            if (request.optString("status") == "GRANTING") {
                request.put("status", if (authorization.applied(request.getString("request_id"))) "APPROVED_RECOVERED" else "RECOVERY_REQUIRED")
                save(file, request)
            }
            if (request.optString("status") == "EXECUTING") {
                request.put("status", if (state()?.optString("last_completed_request_id") == request.getString("request_id") || departures().any { it.optString("last_completed_request_id") == request.getString("request_id") }) "COMPLETED_RECOVERED" else "RECOVERY_REQUIRED")
                save(file, request)
            }
            request
        } ?: emptyList()
        val policy = authorization.snapshot(state())
        JSONObject().put("authorizations", policy).put("success", true).put("device_id", deviceId(key().getString("public")))
            .put("module", state() ?: JSONObject.NULL).put("outgoing_migrations", JSONArray(departures())).put("requests", JSONArray(pending))
            .put("migration_package_path", state()?.let { value -> if (value.optString("direction") == "outbound" && !value.isNull("migration_id")) File(root, "exports/${value.getString("migration_id")}.ails").absolutePath else JSONObject.NULL } ?: JSONObject.NULL)
            .put("versions", JSONArray(state()?.let { value -> File(root, "versions/${value.getString("identity_id")}").listFiles()?.map { it.nameWithoutExtension } } ?: emptyList<String>()))
    }
    fun install(file: File): JSONObject = locked {
        requireSelf(state() == null, "SELF_SLOT_OCCUPIED")
        val incoming = readPack(file)
        requireSelf(incoming.manifest.getString("package_kind") == "module", "SELF_MIGRATION_REQUIRES_PROTOCOL")
        requireSelf(incoming.manifest.optString("identity_mode") != "BOUND", "SELF_BOUND_PACKAGE_REQUIRES_EXISTING_IDENTITY")
        val pack = newInstance(incoming)
        requireSelf(departures().none { it.getString("identity_id") == pack.manifest.getString("identity_id") }, "SELF_IDENTITY_ALREADY_DEPARTED")
        persistProgram(pack)
        val value = metadata(pack.manifest, UUID.randomUUID().toString())
        save(File(data(value), "state.json"), JSONObject().put("identity_id", value.getString("identity_id"))
            .put("state_schema_version", value.getInt("state_schema_version")))
        save(slot, value)
        copyJson(value).put("success", true)
    }
    private fun switchVersion(args: JSONObject, rollback: Boolean): JSONObject {
        val old = active()
        val file = if (rollback) {
            val version = args.getString("target_version")
            requireSelf(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$").matches(version), "SELF_VERSION_INVALID")
            File(root, "versions/${old.getString("identity_id")}/$version.ails")
        } else File(args.getString("package_path"))
        requireSelf(file.isFile, "SELF_HISTORY_UNAVAILABLE")
        val incoming = readPack(file)
        val pack = bindProgram(incoming, old.getString("identity_id"))
        val manifest = pack.manifest
        requireSelf(manifest.getString("package_kind") == "module" && manifest.getString("identity_id") == old.getString("identity_id"), "SELF_IDENTITY_MISMATCH")
        fun version(raw: String) = raw.split('.').map { it.toLong() }
        val target = version(manifest.getString("module_version")); val current = version(old.getString("module_version"))
        val comparison = target.zip(current).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
        requireSelf(if (rollback) comparison < 0 else comparison > 0, "SELF_VERSION_DIRECTION_INVALID")
        val schemas = manifest.getJSONArray("compatible_state_schemas")
        requireSelf((0 until schemas.length()).any { schemas.getInt(it) == old.getInt("state_schema_version") }, "SELF_SCHEMA_INCOMPATIBLE")
        persistProgram(pack)
        val next = copyJson(old).put("module_version", manifest.getString("module_version"))
            .put("generation", UUID.randomUUID().toString()).put("previous_version", old.getString("module_version"))
        save(slot, next)
        return copyJson(next).put("success", true)
    }
    private fun writePack(file: File, manifest: JSONObject, files: Map<String, ByteArray>) {
        val checks = JSONObject(); files.forEach { (name, bytes) -> checks.put(name, hash(bytes)) }
        manifest.put("integrity", JSONObject().put("algorithm", "sha256").put("entries", checks))
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (mapOf("self.json" to manifest.toString().toByteArray()) + files).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L }); zip.write(bytes); zip.closeEntry()
            }
        }
        requireSelf(output.size() <= MAX_BYTES, "SELF_PACKAGE_TOO_LARGE")
        atomic(file, output.toByteArray())
    }
    private fun departureFile(id: String) = File(root, "departures/${identity(id)}.json")
    private fun migrationState(args: JSONObject): JSONObject {
        if (!args.has("migration_id")) return state() ?: fail("SELF_NOT_INSTALLED")
        val id = identity(args.getString("migration_id"))
        state()?.let { if (it.optString("migration_id") == id) return it }
        val file = departureFile(id)
        requireSelf(file.isFile, "SELF_MIGRATION_UNKNOWN")
        return JSONObject(file.readText()).also { requireSelf(!it.optBoolean("cancelled"), "SELF_MIGRATION_CANCELLED") }
    }
    private fun saveMigration(old: JSONObject, next: JSONObject) {
        if (old.optBoolean("source_uninstalled")) save(departureFile(old.getString("migration_id")), next)
        else save(slot, next)
    }
    private fun migrate(args: JSONObject): JSONObject = when (args.getString("phase")) {
        "export" -> {
            val old = active()
            val target = args.getString("target_device_id")
            requireSelf(Regex("^[a-f0-9]{64}$").matches(target) && target != deviceId(key().getString("public")), "SELF_MIGRATION_TARGET_INVALID")
            val migrationId = UUID.randomUUID().toString()
            val manifest = copyJson(readPack(program(old)).manifest).put("package_kind", "migration")
                .put("migration_id", migrationId).put("source_device_id", deviceId(key().getString("public")))
                .put("target_device_id", target).put("state_schema_version", old.getInt("state_schema_version"))
            val files = linkedMapOf("program.ails" to program(old).readBytes())
            File(root, "versions/${old.getString("identity_id")}").listFiles()?.filter { it.extension == "ails" }?.forEach { history ->
                files["versions/${history.name}"] = readPack(history).bytes
                requireSelf(files.values.sumOf { item -> item.size.toLong() } <= MAX_BYTES, "SELF_PACKAGE_TOO_LARGE")
            }
            val dataRoot = data(old)
            dataRoot.walkTopDown().onEnter { directory ->
                requireSelf(!Files.isSymbolicLink(directory.toPath()), "SELF_DATA_SYMLINK_FORBIDDEN"); true
            }.filter { it.isFile }.forEach {
                requireSelf(!Files.isSymbolicLink(it.toPath()), "SELF_DATA_SYMLINK_FORBIDDEN")
                files["data/${it.relativeTo(dataRoot).invariantSeparatorsPath}"] = it.readBytes()
                requireSelf(files.values.sumOf { item -> item.size.toLong() } <= MAX_BYTES, "SELF_PACKAGE_TOO_LARGE")
            }
            val output = File(root, "exports/$migrationId.ails")
            writePack(output, manifest, files)
            val sealed = copyJson(old).put("human_migration_authorized", humanMigrationAuthorized).put("lifecycle_state", "SEALED").put("migration_id", migrationId)
                .put("direction", "outbound").put("target_device_id", target).put("package_sha256", hash(output.readBytes()))
                .put("transfer_committed", false).put("generation", UUID.randomUUID().toString())
            if (args.has("export_uri")) sealed.put("export_uri", args.getString("export_uri"))
            sealed.remove("abort"); sealed.remove("saved_export"); sealed.remove("registration_receipt")
            save(slot, sealed)
            JSONObject().put("success", true).put("package_path", output.absolutePath).put("module", sealed)
        }
        "prepare" -> {
            requireSelf(state() == null, "SELF_SLOT_OCCUPIED")
            val file = File(args.getString("package_path")); val pack = readPack(file); val m = pack.manifest
            requireSelf(m.getString("package_kind") == "migration" && m.getString("target_device_id") == deviceId(key().getString("public")), "SELF_MIGRATION_TARGET_MISMATCH")
            identity(m.getString("migration_id"))
            val temp = File(root, "imports/${m.getString("migration_id")}.ails")
            atomic(temp, pack.files.getValue("program.ails"))
            val module = readPack(temp)
            requireSelf(module.manifest.getString("identity_id") == m.getString("identity_id") && module.manifest.getString("module_version") == m.getString("module_version"), "SELF_MIGRATION_PROGRAM_MISMATCH")
            val schemas = module.manifest.getJSONArray("compatible_state_schemas")
            requireSelf((0 until schemas.length()).any { schemas.getInt(it) == m.getInt("state_schema_version") }, "SELF_SCHEMA_INCOMPATIBLE")
            val dataState = JSONObject(String(pack.files.getValue("data/state.json"), Charsets.UTF_8))
            requireSelf(dataState.getString("identity_id") == m.getString("identity_id") && dataState.getInt("state_schema_version") == m.getInt("state_schema_version"), "SELF_MIGRATION_DATA_MISMATCH")
            val history = pack.files.filterKeys { it.startsWith("versions/") }.map { (name, bytes) ->
                val candidate = File(root, "imports/${UUID.randomUUID()}.ails")
                atomic(candidate, bytes)
                val verified = try { readPack(candidate) } finally { candidate.delete() }
                requireSelf(verified.manifest.getString("package_kind") == "module" && verified.manifest.getString("identity_id") == m.getString("identity_id") && name == "versions/${verified.manifest.getString("module_version")}.ails", "SELF_MIGRATION_HISTORY_MISMATCH")
                verified
            }
            history.forEach { persistProgram(it) }
            persistProgram(module)
            val next = metadata(m, UUID.randomUUID().toString()).put("human_migration_authorized", humanMigrationAuthorized).put("lifecycle_state", "SEALED")
                .put("migration_id", m.getString("migration_id")).put("direction", "inbound")
                .put("source_device_id", m.getString("source_device_id")).put("package_sha256", pack.digest)
                .put("target_nonce", UUID.randomUUID().toString())
            pack.files.filterKeys { it.startsWith("data/") }.forEach { (name, bytes) -> atomic(File(data(next), name.removePrefix("data/")), bytes) }
            val receipt = sign(JSONObject().put("kind", "PREPARED").put("migration_id", next.getString("migration_id"))
                .put("package_sha256", pack.digest).put("target_nonce", next.getString("target_nonce")))
            next.put("prepared_receipt", receipt); save(slot, next)
            JSONObject().put("success", true).put("receipt", receipt).put("module", next)
        }
        "commit" -> {
            val old = migrationState(args)
            requireSelf(old.getString("lifecycle_state") == "SEALED" && old.getString("direction") == "outbound", "SELF_MIGRATION_NOT_OUTBOUND")
            if (old.optBoolean("transfer_committed")) {
                // Re-read a durable release, never issue a second transfer to another target.
                JSONObject().put("success", true).put("release", old.getJSONObject("release"))
            } else {
                val receipt = verify(args.getJSONObject("receipt"), old.getString("target_device_id"))
                requireSelf(receipt.getString("kind") == "PREPARED" && receipt.getString("migration_id") == old.getString("migration_id") && receipt.getString("package_sha256") == old.getString("package_sha256"), "SELF_RECEIPT_MISMATCH")
                val release = sign(copyJson(receipt).put("kind", "RELEASED").put("target_device_id", old.getString("target_device_id")))
                saveMigration(old, copyJson(old).put("transfer_committed", true).put("release", release)
                    .put("generation", UUID.randomUUID().toString()))
                JSONObject().put("success", true).put("release", release)
            }
        }
        "activate" -> {
            val old = state() ?: fail("SELF_NOT_INSTALLED")
            requireSelf(old.getString("lifecycle_state") == "SEALED" && old.getString("direction") == "inbound", "SELF_MIGRATION_NOT_INBOUND")
            val release = verify(args.getJSONObject("release"), old.getString("source_device_id"))
            requireSelf(release.getString("kind") == "RELEASED" && release.getString("migration_id") == old.getString("migration_id") &&
                release.getString("package_sha256") == old.getString("package_sha256") && release.getString("target_nonce") == old.getString("target_nonce") &&
                release.getString("target_device_id") == deviceId(key().getString("public")), "SELF_RELEASE_MISMATCH")
            val next = copyJson(old).put("lifecycle_state", "ACTIVE").put("migration_id", JSONObject.NULL)
                .put("generation", UUID.randomUUID().toString())
            next.remove("prepared_receipt"); next.remove("direction"); next.remove("human_migration_authorized")
            save(slot, next); copyJson(next).put("success", true)
        }
        "cancel" -> {
            val old = migrationState(args)
            requireSelf(old.getString("lifecycle_state") == "SEALED" && !old.optBoolean("transfer_committed"), "SELF_COMMITTED_MIGRATION_CANNOT_CANCEL")
            requireSelf(old.getString("direction") == "outbound", "SELF_TARGET_CANCEL_REQUIRES_SOURCE_ABORT")
            if (old.optBoolean("source_uninstalled")) requireSelf(state() == null, "SELF_SLOT_OCCUPIED")
            val next = copyJson(old).put("lifecycle_state", "ACTIVE").put("migration_id", JSONObject.NULL)
                .put("generation", UUID.randomUUID().toString())
            val abort = sign(JSONObject().put("kind", "ABORTED").put("migration_id", old.getString("migration_id"))
                .put("package_sha256", old.getString("package_sha256")))
            next.put("abort", abort)
            next.remove("source_uninstalled"); next.remove("registration_receipt"); next.remove("saved_export"); next.remove("export_uri"); next.remove("direction"); next.remove("transfer_committed"); next.remove("human_migration_authorized"); save(slot, next)
            if (old.optBoolean("source_uninstalled")) save(departureFile(old.getString("migration_id")), copyJson(old).put("cancelled", true))
            JSONObject().put("success", true).put("abort", abort).put("module", next)
        }
        "discard" -> {
            val old = state() ?: fail("SELF_NOT_INSTALLED")
            requireSelf(old.getString("lifecycle_state") == "SEALED" && old.getString("direction") == "inbound", "SELF_MIGRATION_NOT_INBOUND")
            val abort = verify(args.getJSONObject("abort"), old.getString("source_device_id"))
            requireSelf(abort.getString("kind") == "ABORTED" && abort.getString("migration_id") == old.getString("migration_id") && abort.getString("package_sha256") == old.getString("package_sha256"), "SELF_ABORT_MISMATCH")
            // Preserve staged data; only release the inactive slot after signed source abort.
            save(File(root, "aborted/${old.getString("migration_id")}.json"), old)
            requireSelf(slot.delete(), "SELF_SLOT_RELEASE_FAILED")
            JSONObject().put("success", true).put("discarded", true)
        }
        else -> fail("SELF_MIGRATION_PHASE_INVALID")
    }
    private fun execute(operation: String, args: JSONObject): JSONObject = when (operation) {
        "upgrade" -> switchVersion(args, false)
        "rollback" -> switchVersion(args, true)
        "migrate" -> migrate(args)
        else -> fail("SELF_OPERATION_INVALID")
    }
    fun autonomous(operation: String, args: JSONObject): JSONObject = locked { execute(operation, args) }
    private fun pin(operation: String, args: JSONObject, requestId: String): JSONObject {
        requireSelf(operation in setOf("upgrade", "rollback", "migrate"), "SELF_OPERATION_INVALID")
        val pinned = copyJson(args)
        when (operation) {
            "upgrade" -> requireSelf(pinned.has("package_path"), "SELF_PACKAGE_REQUIRED")
            "rollback" -> requireSelf(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$").matches(pinned.getString("target_version")), "SELF_VERSION_INVALID")
            "migrate" -> when (pinned.getString("phase")) {
                "export" -> {
                    requireSelf(Regex("^[a-f0-9]{64}$").matches(pinned.getString("target_device_id")), "SELF_MIGRATION_TARGET_INVALID")
                    if (pinned.has("export_uri")) requireSelf(pinned.getString("export_uri").startsWith("content://"), "SELF_EXPORT_DOCUMENT_REQUIRED")
                }
                "prepare" -> requireSelf(pinned.has("package_path"), "SELF_PACKAGE_REQUIRED")
                "commit" -> pinned.getJSONObject("receipt")
                "activate" -> pinned.getJSONObject("release")
                "discard" -> pinned.getJSONObject("abort")
                "cancel" -> Unit
                else -> fail("SELF_MIGRATION_PHASE_INVALID")
            }
        }
        if (pinned.has("package_path")) {
            val pack = readPack(File(pinned.getString("package_path")))
            val staged = File(root, "requests/$requestId.ails")
            atomic(staged, pack.bytes)
            pinned.put("package_path", staged.absolutePath).put("package_sha256", pack.digest)
        }
        return pinned
    }
    private fun operationRequest(operation: String, args: JSONObject, reason: String = ""): JSONObject {
        val requestId = UUID.randomUUID().toString()
        return JSONObject().put("request_id", requestId).put("operation", operation)
            .put("parameters", pin(operation, args, requestId)).put("status", "PENDING")
            .put("request_kind", "OPERATION").put("requested_mode", "ONE_TIME").put("reason", reason)
            .put("created_at_ms", clock()).put("state_fingerprint", hash((if (operation == "migrate" && args.has("migration_id")) migrationState(args).toString() else state()?.toString() ?: "EMPTY").toByteArray()))
    }
    fun request(operation: String, args: JSONObject, binding: JSONObject? = null): JSONObject = locked {
        checkProgramBinding(binding)
        val value = operationRequest(operation, args)
        save(File(requests, "${value.getString("request_id")}.json"), value)
        copyJson(value).put("success", true)
    }
    /** One selected permission = one decision. Validate the entire form before making requests visible. */
    fun requestBundle(args: JSONObject): JSONObject = locked {
        checkProgramBinding(args.optJSONObject("program_binding"))
        val items = args.getJSONArray("items")
        requireSelf(items.length() in 1..3, "SELF_APPLICATION_ITEMS_INVALID")
        val mode = args.getString("mode")
        requireSelf(mode in setOf("ONE_TIME", "TIMED", "LONG"), "SELF_AUTHORIZATION_MODE_INVALID")
        val reason = args.getString("reason").trim()
        requireSelf(reason.isNotEmpty() && reason.length <= 2000, "SELF_APPLICATION_REASON_REQUIRED")
        val duration = if (mode == "TIMED") args.getLong("duration_seconds") else 0L
        requireSelf(mode != "TIMED" || duration in 1..31536000L, "SELF_AUTHORIZATION_DURATION_INVALID")
        val current = if (mode != "ONE_TIME") active() else state()
        val seen = mutableSetOf<String>()
        val batchId = UUID.randomUUID().toString()
        val values = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val operation = item.getString("operation")
            requireSelf(operation in setOf("upgrade", "rollback", "migrate") && seen.add(operation), "SELF_APPLICATION_ITEMS_INVALID")
            val value = if (mode == "ONE_TIME") operationRequest(operation, item.getJSONObject("parameters"), reason)
            else JSONObject().put("request_id", UUID.randomUUID().toString()).put("operation", operation)
                .put("request_kind", "AUTHORIZATION").put("status", "PENDING").put("parameters", JSONObject())
                .put("identity_id", requireNotNull(current).getString("identity_id")).put("data_id", current.getString("data_id"))
                .put("created_at_ms", clock()).put("reason", reason)
            value.put("batch_id", batchId).put("requested_mode", mode)
            if (mode == "TIMED") value.put("requested_duration_seconds", duration)
            value
        }
        values.forEach { save(File(requests, "${it.getString("request_id")}.json"), it) }
        JSONObject().put("success", true).put("batch_id", batchId).put("requests", JSONArray(values))
    }
    fun revoke(operation: String, reason: String): JSONObject = locked {
        requireSelf(operation in setOf("upgrade", "rollback", "migrate"), "SELF_OPERATION_INVALID")
        authorization.revoke(operation, reason)
    }
    /** Ongoing grants are checked again under the same lock as the lifecycle commit. */
    fun humanExecute(operation: String, args: JSONObject, binding: JSONObject? = null): JSONObject = locked {
        checkProgramBinding(binding)
        requireSelf(operation in setOf("upgrade", "rollback", "migrate"), "SELF_OPERATION_INVALID")
        val current = if (operation == "migrate" && args.has("migration_id")) migrationState(args) else state()
        val continuation = operation == "migrate" && args.optString("phase") in setOf("commit", "activate", "cancel", "discard") &&
            current?.optBoolean("human_migration_authorized") == true && current.optString("lifecycle_state") == "SEALED"
        val grants = authorization.snapshot(current).getJSONArray("effective_grants")
        requireSelf(continuation || (0 until grants.length()).any { grants.getJSONObject(it).getString("operation") == operation }, "SELF_HUMAN_AUTHORIZATION_REQUIRED")
        val value = operationRequest(operation, args).put("authorized_by", if (continuation) "MIGRATION_TRANSACTION" else "ONGOING_GRANT")
        val file = File(requests, "${value.getString("request_id")}.json")
        save(file, value)
        performApproved(file, value)
    }
    fun cancelRequest(id: String, binding: JSONObject? = null): JSONObject = locked {
        checkProgramBinding(binding)
        val file = File(requests, "${identity(id)}.json"); val value = JSONObject(file.readText())
        requireSelf(value.getString("status") == "PENDING", "SELF_REQUEST_TERMINAL")
        value.put("status", "CANCELLED"); save(file, value); copyJson(value).put("success", true)
    }
    fun review(id: String, approve: Boolean, decision: JSONObject = JSONObject()): JSONObject = locked {
        val file = File(requests, "${identity(id)}.json")
        val request = JSONObject(file.readText())
        requireSelf(request.getString("status") == "PENDING", "SELF_REQUEST_TERMINAL")
        val reason = decision.optString("reason")
        requireSelf(reason.length <= 2000, "SELF_APPROVAL_REASON_TOO_LONG")
        request.put("review_reason", reason).put("reviewed_at_ms", clock())
        if (!approve) {
            request.put("status", "REJECTED"); save(file, request); copyJson(request).put("success", true)
        } else if (request.optString("request_kind") == "AUTHORIZATION") {
            val current = active()
            requireSelf(current.getString("identity_id") == request.getString("identity_id") && current.getString("data_id") == request.getString("data_id"), "SELF_REQUEST_STALE")
            val requested = request.getString("requested_mode")
            val mode = decision.optString("mode", requested)
            requireSelf(mode in setOf("TIMED", "LONG") && (requested == "LONG" || mode == "TIMED"), "SELF_APPROVAL_SCOPE_EXCEEDED")
            val duration = if (mode == "TIMED") decision.optLong("duration_seconds", request.optLong("requested_duration_seconds", 0)) else 0L
            requireSelf(mode != "TIMED" || duration in 1..31536000L, "SELF_AUTHORIZATION_DURATION_INVALID")
            requireSelf(requested != "TIMED" || duration <= request.getLong("requested_duration_seconds"), "SELF_APPROVAL_SCOPE_EXCEEDED")
            request.put("status", "GRANTING").put("approved_mode", mode)
            if (mode == "TIMED") request.put("approved_duration_seconds", duration)
            save(file, request)
            val grant = authorization.approve(current, request.getString("operation"), mode, if (mode == "TIMED") duration else null, id, reason)
            request.put("status", "APPROVED").put("grant", grant)
            save(file, request)
            JSONObject().put("success", true).put("request", request).put("grant", grant)
        } else {
            requireSelf(!decision.has("mode") || decision.getString("mode") == "ONE_TIME", "SELF_APPROVAL_SCOPE_EXCEEDED")
            performApproved(file, request)
        }
    }
    private fun performApproved(file: File, request: JSONObject): JSONObject {
        val id = request.getString("request_id")
        val args = request.getJSONObject("parameters")
        val relevant = if (request.getString("operation") == "migrate" && args.has("migration_id")) migrationState(args) else state()
        requireSelf(request.getString("state_fingerprint") == hash((relevant?.toString() ?: "EMPTY").toByteArray()), "SELF_REQUEST_STALE")
        if (args.has("package_path")) requireSelf(hash(File(args.getString("package_path")).readBytes()) == args.getString("package_sha256"), "SELF_REQUEST_PACKAGE_CHANGED")
        request.put("status", "EXECUTING"); save(file, request)
        executingRequestId = id
        humanMigrationAuthorized = request.getString("operation") == "migrate"
        try {
            val result = execute(request.getString("operation"), args)
            request.put("status", "COMPLETED").put("result", result); save(file, request)
            return JSONObject().put("success", true).put("request", request).put("result", result)
        } catch (error: Exception) {
            request.put("status", if (state()?.optString("last_completed_request_id") == id || departures().any { it.optString("last_completed_request_id") == id }) "COMPLETED_RECOVERED" else "FAILED").put("error", error.message)
            save(file, request); throw error
        } finally { executingRequestId = null; humanMigrationAuthorized = false }
    }
    // Ordinary plugin interfaces reject the module even when presented its durable identity.
    fun migrationPackage(): File = locked {
        val value = state() ?: fail("SELF_NOT_INSTALLED")
        requireSelf(value.optString("direction") == "outbound" && value.getString("lifecycle_state") == "SEALED", "SELF_MIGRATION_NOT_OUTBOUND")
        val file = File(root, "exports/${identity(value.getString("migration_id"))}.ails")
        requireSelf(file.isFile && hash(file.readBytes()) == value.getString("package_sha256"), "SELF_MIGRATION_PACKAGE_CORRUPT")
        file
    }
    fun migrationRegistration(): JSONObject = locked {
        val value = state() ?: fail("SELF_NOT_INSTALLED")
        requireSelf(value.optString("direction") == "outbound" && value.optString("lifecycle_state") == "SEALED", "SELF_MIGRATION_NOT_OUTBOUND")
        val file = File(root, "exports/${identity(value.getString("migration_id"))}.ails")
        requireSelf(file.isFile && hash(file.readBytes()) == value.getString("package_sha256"), "SELF_MIGRATION_PACKAGE_CORRUPT")
        JSONObject().put("protocol", "AIL_SELF_REGISTRATION_V1").put("identity_id", value.getString("identity_id"))
            .put("migration_id", value.getString("migration_id")).put("package_sha256", value.getString("package_sha256"))
            .put("package_size_bytes", file.length()).put("module_version", value.getString("module_version"))
            .put("state_schema_version", value.getInt("state_schema_version"))
            .put("source_device_id", deviceId(key().getString("public"))).put("target_device_id", value.getString("target_device_id"))
            .put("saved_export", value.optBoolean("saved_export")).put("success", true)
    }
    fun markMigrationSaved(id: String, digest: String, uri: String): JSONObject = locked {
        val value = state() ?: fail("SELF_NOT_INSTALLED")
        requireSelf(value.optString("direction") == "outbound" && value.optString("lifecycle_state") == "SEALED" &&
            value.getString("migration_id") == identity(id) && value.getString("package_sha256") == digest, "SELF_EXPORT_STALE")
        requireSelf(uri.startsWith("content://"), "SELF_EXPORT_DOCUMENT_REQUIRED")
        save(slot, copyJson(value).put("saved_export", true).put("export_uri", uri))
        JSONObject().put("success", true).put("package_sha256", digest).put("identity_id", value.getString("identity_id"))
    }
    /** Only the trusted control plane invokes this after a verified HTTP acknowledgment. */
    fun completeRegistration(receipt: JSONObject): JSONObject = locked {
        val value = state() ?: fail("SELF_NOT_INSTALLED")
        requireSelf(value.optString("direction") == "outbound" && value.optString("lifecycle_state") == "SEALED" &&
            value.optBoolean("saved_export"), "SELF_EXPORT_SAVE_REQUIRED")
        requireSelf(receipt.getString("protocol") == "AIL_SELF_REGISTRATION_V1" && receipt.getBoolean("received"), "SELF_UPLOAD_NOT_CONFIRMED")
        for (field in listOf("identity_id", "migration_id", "package_sha256"))
            requireSelf(receipt.getString(field) == value.getString(field), "SELF_UPLOAD_RECEIPT_MISMATCH")
        requireSelf(receipt.getString("receipt_id").isNotBlank() && receipt.getString("receipt_id").length <= 256, "SELF_UPLOAD_RECEIPT_INVALID")
        val file = File(root, "exports/${identity(value.getString("migration_id"))}.ails")
        requireSelf(file.isFile && hash(file.readBytes()) == value.getString("package_sha256"), "SELF_MIGRATION_PACKAGE_CORRUPT")
        val retired = copyJson(value).put("source_uninstalled", true).put("registration_receipt", receipt)
        save(slot, retired)
        commitRetirement(retired)
        JSONObject().put("success", true).put("source_uninstalled", true).put("migration_id", value.getString("migration_id"))
    }
    private fun commitRetirement(value: JSONObject) {
        // Moving the durable slot releases installation without destroying the only recovery copy.
        val destination = departureFile(value.getString("migration_id"))
        requireSelf(destination.parentFile.mkdirs() || destination.parentFile.isDirectory, "SELF_STORE_UNAVAILABLE")
        Files.move(slot.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        java.nio.channels.FileChannel.open(root.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        java.nio.channels.FileChannel.open(destination.parentFile.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }
    fun isReservedIdentity(id: String): Boolean {
        if (!Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$").matches(id)) return false
        return locked { state()?.optString("identity_id") == id || departures().any { it.optString("identity_id") == id } }
    }
}
