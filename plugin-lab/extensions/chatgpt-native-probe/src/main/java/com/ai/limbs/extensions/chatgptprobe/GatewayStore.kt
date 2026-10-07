package com.ai.limbs.extensions.chatgptprobe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal interface GatewayBlobStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
    fun names(): List<String>
}

/** Receipts contain shard tokens and capability results, so they never go to plain preferences. */
internal class GatewayEncryptedStore(context: Context) : GatewayBlobStore {
    private val directory = File(context.noBackupFilesDir, "chatgpt-gateway").apply { mkdirs() }
    private val key: SecretKey by lazy {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid storage identifier" }
        return AtomicFile(File(directory, name))
    }

    @Synchronized override fun read(name: String): String? {
        val target = file(name)
        if (!target.baseFile.exists() && !File(directory, "$name.bak").exists()) return null
        val bytes = target.openRead().use { it.readBytes() }
        require(bytes.size >= 28) { "Encrypted gateway record is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    @Synchronized override fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val target = file(name)
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    @Synchronized override fun delete(name: String) = file(name).delete()
    @Synchronized override fun names(): List<String> = gatewayCommittedRecordNames(
        directory.listFiles().orEmpty().filter { it.isFile }.map { it.name })

    companion object { private const val KEY_ALIAS = "ai_limbs_chatgpt_gateway_records_v1" }
}

/** AtomicFile may leave only its committed .bak after a process dies during replacement. */
internal fun gatewayCommittedRecordNames(names: List<String>): List<String> = names.mapNotNull {
    when {
        it.endsWith(".bak") -> it.removeSuffix(".bak")
        !it.contains('.') -> it
        else -> null
    }
}.distinct()

internal fun gatewayHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun canonicalJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
    }
    is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    null, JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    is Number -> JSONObject.numberToString(value)
    else -> value.toString()
}

/** A durable receipt is claimed before execution; only recorded responses may be replayed. */
internal class GatewayReceipts(private val store: GatewayBlobStore, private val now: () -> Long = System::currentTimeMillis) {
    private val records = JSONObject()
    private val encodedSizes = mutableMapOf<String, Int>()
    private var storedBytes = 0L

    init {
        // A state transition writes one encrypted receipt, not the whole 24-hour ledger.
        store.names().filter { it.startsWith(RECORD_PREFIX) }.forEach { name ->
            val id = name.removePrefix(RECORD_PREFIX)
            require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid receipt identifier" }
            val text = requireNotNull(store.read(name)) { "Receipt disappeared during recovery" }
            records.put(id, JSONObject(text))
            encodedSizes[id] = text.toByteArray(Charsets.UTF_8).size
            storedBytes += encodedSizes.getValue(id)
        }
        // Forward migration is restartable: persisted per-record state is authoritative.
        // No business request is accepted until all legacy receipts have been preserved.
        store.read("receipts")?.let { text ->
            val legacy = JSONObject(text)
            legacy.keys().forEach { id ->
                val record = legacy.getJSONObject(id)
                if (records.has(id)) {
                    require(records.getJSONObject(id).getString("fingerprint") == record.getString("fingerprint")) {
                        "Legacy receipt identity conflict"
                    }
                } else commit(id, record)
            }
            store.delete("receipts")
        }
        require(records.length() <= MAX_RECORDS && storedBytes <= MAX_BYTES) { "Gateway receipt storage limit reached" }
    }

    @Synchronized fun claim(binding: String, command: JSONObject): Pair<String, Boolean> {
        val id = gatewayHash(binding + "\n" + command.getString("channel") + "\n" + command.getString("request_id"))
        val fingerprint = gatewayHash(canonicalJson(JSONObject(command.toString()).apply { remove("shard_token") }))
        val old = records.optJSONObject(id)
        if (old != null) {
            require(old.getString("fingerprint") == fingerprint) { "Request ID reused with different input" }
            val updated = JSONObject(old.toString()).put("command", receiptCommand(command))
            if (updated.getString("phase") == "ACKED" || updated.getString("phase") == "DELIVERY_FAILED") updated.put("phase", "READY")
            commit(id, updated)
            return id to false
        }
        val expired = records.keys().asSequence().filter {
            val record = records.getJSONObject(it)
            record.getString("phase") in setOf("ACKED", "DELIVERY_FAILED") && now() - record.getLong("created") > RETENTION_MS
        }.toList()
        expired.forEach { expiredId ->
            store.delete(RECORD_PREFIX + expiredId)
            storedBytes -= encodedSizes.remove(expiredId)!!
            records.remove(expiredId)
        }
        require(records.length() < MAX_RECORDS) { "Gateway receipt capacity reached; no action executed" }
        commit(id, JSONObject().put("binding", binding).put("fingerprint", fingerprint)
            .put("command", receiptCommand(command)).put("created", now()).put("phase", "EXECUTING"))
        return id to true
    }

    @Synchronized fun ready(id: String, payload: JSONObject) {
        commit(id, JSONObject(records.getJSONObject(id).toString()).put("response", payload).put("phase", "READY"))
    }
    @Synchronized fun mark(id: String, phase: String, shardToken: String? = null) {
        if (shardToken != null && records.getJSONObject(id).getJSONObject("command").getString("shard_token") != shardToken) return
        commit(id, JSONObject(records.getJSONObject(id).toString()).put("phase", phase))
    }
    @Synchronized fun delivered(id: String, shardToken: String): Boolean {
        val current = records.getJSONObject(id)
        // A duplicate may refresh its shard token while the preceding POST is in flight.
        if (current.getJSONObject("command").getString("shard_token") == shardToken) {
            val compact = JSONObject(current.toString()).put("phase", "ACKED")
            // Delivered images already have immutable cache handles. Keep compact dedup receipts
            // instead of retaining megabytes of base64 for every completed screenshot for 24 hours.
            val result = compact.optJSONObject("response")?.optJSONObject("resp_json")?.optJSONObject("result")
            val content = result?.optJSONArray("content")
            if (content != null) for (index in 0 until content.length()) {
                val image = content.optJSONObject(index) ?: continue
                if (image.optString("type") != "image") continue
                val mediaId = image.optJSONObject("_meta")?.optString("ai_limbs_media_id").orEmpty()
                if (mediaId.isBlank()) continue
                content.put(index, JSONObject().put("type", "text").put("text", "Image was delivered previously. Read cached media_id $mediaId with ai_limbs_media_read; media expires after ten minutes. Do not repeat the original action."))
                result?.optJSONObject("structuredContent")?.put("delivery_replay", "Retrieve previously delivered images using their cache handles")
            }
            commit(id, compact)
            return true
        }
        return false
    }
    @Synchronized fun list(binding: String, phase: String): List<Pair<String, JSONObject>> = records.keys().asSequence()
        .filter { records.getJSONObject(it).getString("binding") == binding && records.getJSONObject(it).getString("phase") == phase }
        .map { it to JSONObject(records.getJSONObject(it).toString()) }.toList()
    /** READY lists are detached snapshots; a completed POST may invalidate an entry before admission. */
    @Synchronized fun readyForDelivery(binding: String, id: String): JSONObject? {
        val record = records.optJSONObject(id) ?: return null
        if (record.getString("binding") != binding || record.getString("phase") != "READY") return null
        return JSONObject(record.toString())
    }
    @Synchronized fun counts(): JSONObject = JSONObject().apply {
        records.keys().forEach { val phase = records.getJSONObject(it).getString("phase"); put(phase, optInt(phase) + 1) }
    }
    @Synchronized fun countsFor(binding: String): JSONObject = JSONObject().apply {
        records.keys().forEach {
            val record = records.getJSONObject(it)
            if (record.getString("binding") == binding) {
                val phase = record.getString("phase")
                put(phase, optInt(phase) + 1)
            }
        }
    }
    private fun commit(id: String, record: JSONObject) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid receipt identifier" }
        val text = record.toString()
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val previousBytes = if (records.has(id)) encodedSizes.getValue(id) else 0
        val nextBytes = storedBytes - previousBytes + bytes
        require(nextBytes <= MAX_BYTES) { "Gateway receipt storage limit reached" }
        store.write(RECORD_PREFIX + id, text)
        records.put(id, record)
        encodedSizes[id] = bytes
        storedBytes = nextBytes
    }
    private fun receiptCommand(command: JSONObject): JSONObject = JSONObject()
        .put("request_id", command.getString("request_id")).put("channel", command.getString("channel"))
        .put("shard_token", command.getString("shard_token")).put("command_type", command.getString("command_type"))
        .apply {
            command.optJSONObject("jsonrpc")?.let { rpc ->
                val identity = JSONObject().put("jsonrpc", rpc.opt("jsonrpc")).put("method", rpc.opt("method"))
                if (rpc.has("id")) identity.put("id", rpc.opt("id"))
                put("jsonrpc", identity)
            }
        }
    companion object {
        private const val RECORD_PREFIX = "receipt_"
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val MAX_RECORDS = 4096
        private const val MAX_BYTES = 32 * 1024 * 1024
    }
}
