package com.ai.limbs.extensions.sentinelx.runtime

import android.util.Base64
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class SentinelXMediaException(val code: String, message: String) : IllegalArgumentException(message)

/** Only media returned by an admitted tool is readable. Paths are handles, never filesystem paths. */
internal class SentinelXMediaStore {
    private data class Attachment(val path: String, val mime: String, val data: ByteArray,
        val sha256: String, val createdAt: Long)
    private data class Export(val attachmentPath: String, val chunkSize: Int,
        val numChunks: Int, val createdAt: Long, var nextIndex: Int = 0)
    data class Chunk(val metadata: JSONObject, val frame: ByteArray)

    private val attachments = LinkedHashMap<String, Attachment>()
    private val exports = LinkedHashMap<String, Export>()

    @Synchronized
    fun store(images: JSONArray): JSONArray {
        expire()
        val now = System.currentTimeMillis()
        // Decode the whole batch before publishing any handles. A malformed batch changes no cache entries.
        val incoming = (0 until images.length()).map { index ->
            val item = images.getJSONObject(index)
            val mime = item.getString("mimeType")
            val encoded = item.getString("data")
            checkMedia(encoded.length <= MAX_ENCODED_BYTES, "media_too_large", "Encoded image is too large")
            val bytes = try { Base64.decode(encoded, Base64.NO_WRAP) }
                catch (error: IllegalArgumentException) {
                    throw SentinelXMediaException("invalid_media", "Image is not valid Base64")
                }
            checkMedia(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES,
                "media_too_large", "Decoded image exceeds the attachment limit")
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            val valid = when (mime) {
                "image/png" -> bytes.size >= png.size && png.indices.all { bytes[it] == png[it] }
                "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() &&
                    bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
                else -> false
            }
            checkMedia(valid, "invalid_media", "Image bytes do not match the declared PNG/JPEG type")
            checkMedia(Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded,
                "invalid_media", "Image Base64 must be canonical and unwrapped")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            checkMedia(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 32L * 1024 * 1024,
                "invalid_media", "Image dimensions are invalid or exceed the media limit")
            val suffix = if (mime == "image/png") "png" else "jpg"
            Attachment(MEDIA_PREFIX + UUID.randomUUID().toString() + "." + suffix,
                mime, bytes, sha256(bytes), now)
        }
        val bytesRequired = incoming.sumOf { it.data.size }
        checkMedia(incoming.size <= MAX_ATTACHMENTS && bytesRequired <= MAX_CACHE_BYTES,
            "media_too_large", "Media batch exceeds the cache limit")
        val pinned = exports.values.map { it.attachmentPath }.toSet()
        val victims = mutableListOf<String>()
        var size = attachments.values.sumOf { it.data.size }
        var count = attachments.size
        for ((path, attachment) in attachments) {
            if (count + incoming.size <= MAX_ATTACHMENTS && size + bytesRequired <= MAX_CACHE_BYTES) break
            if (path !in pinned) { victims.add(path); count--; size -= attachment.data.size }
        }
        checkMedia(count + incoming.size <= MAX_ATTACHMENTS && size + bytesRequired <= MAX_CACHE_BYTES,
            "media_cache_busy", "Active transfers fill the media cache; image handles were not published")
        victims.forEach { attachments.remove(it) }
        incoming.forEach { attachments[it.path] = it }
        return JSONArray().apply {
            incoming.forEach { attachment ->
                put(JSONObject().put("path", attachment.path).put("mimeType", attachment.mime)
                    .put("size_bytes", attachment.data.size).put("sha256", attachment.sha256)
                    .put("expires_at_ms", attachment.createdAt + TTL_MS)
                    .put("delivery", "binary")
                    .put("read_tool", "sentinel_read_media"))
            }
        }
    }

    @Synchronized
    fun initialize(payload: JSONObject): JSONObject {
        expire()
        val transferId = transferId(payload)
        val path = payload.getString("source_path")
        checkMedia(path.startsWith(MEDIA_PREFIX), "path_not_allowed",
            "Only media handles returned by this receiver may be exported; filesystem access is not supported")
        val attachment = attachments[path]
            ?: throw SentinelXMediaException("not_found", "Media handle expired or does not exist")
        checkMedia(System.currentTimeMillis() - attachment.createdAt < TTL_MS,
            "not_found", "Media handle expired")
        checkMedia(!exports.containsKey(transferId), "transfer_in_use", "Transfer ID is already active")
        checkMedia(exports.size < MAX_EXPORTS, "too_many_transfers", "Too many active media transfers")
        val chunkSize = if (payload.has("chunk_size")) integer(payload, "chunk_size") else MAX_CHUNK_BYTES
        checkMedia(chunkSize in 1..MAX_CHUNK_BYTES, "invalid_payload", "chunk_size must be 1..1048576")
        val chunks = (attachment.data.size + chunkSize - 1) / chunkSize
        exports[transferId] = Export(path, chunkSize, chunks, System.currentTimeMillis())
        return JSONObject().put("transfer_id", transferId)
            .put("filename", path.substringAfterLast('/')).put("source_path", path)
            .put("size", attachment.data.size).put("chunk_size", chunkSize).put("num_chunks", chunks)
    }

    @Synchronized
    fun chunk(payload: JSONObject): Chunk {
        expire()
        val transferId = transferId(payload)
        val export = exports[transferId]
            ?: throw SentinelXMediaException("not_found", "Media transfer expired or was not initialized")
        val attachment = attachments[export.attachmentPath]
            ?: throw SentinelXMediaException("not_found", "Media attachment expired")
        val index = integer(payload, "chunk_index")
        checkMedia(index in 0 until export.numChunks, "invalid_payload", "chunk_index is out of range")
        val offset = index * export.chunkSize
        val length = minOf(export.chunkSize, attachment.data.size - offset)
        val frame = ByteBuffer.allocate(HEADER_BYTES + length).order(ByteOrder.BIG_ENDIAN)
        for (position in 0 until 32 step 2)
            frame.put(transferId.substring(position, position + 2).toInt(16).toByte())
        frame.putInt(index).put(attachment.data, offset, length)
        if (index == export.nextIndex) export.nextIndex++
        return Chunk(JSONObject().put("transfer_id", transferId).put("chunk_index", index)
            .put("bytes", length).put("eof", index + 1 == export.numChunks), frame.array())
    }

    @Synchronized
    fun complete(payload: JSONObject): JSONObject {
        expire()
        val transferId = transferId(payload)
        val export = exports.remove(transferId)
            ?: throw SentinelXMediaException("not_found", "Media transfer expired or was not initialized")
        val attachment = attachments[export.attachmentPath]
            ?: throw SentinelXMediaException("not_found", "Media attachment expired")
        val complete = export.nextIndex == export.numChunks
        return JSONObject().put("transfer_id", transferId).put("size", attachment.data.size)
            .put("chunks_read", export.nextIndex).put("num_chunks", export.numChunks)
            .put("sha256", if (complete) attachment.sha256 else JSONObject.NULL)
            .put("sha256_complete", complete)
    }

    @Synchronized
    fun clear() { exports.clear(); attachments.clear() }

    private fun expire() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        exports.entries.removeAll { it.value.createdAt < cutoff }
        val pinned = exports.values.map { it.attachmentPath }.toSet()
        attachments.entries.removeAll { it.value.createdAt < cutoff && it.key !in pinned }
    }

    private fun transferId(payload: JSONObject): String {
        val value = payload.getString("transfer_id")
        checkMedia(Regex("[0-9a-fA-F]{32}").matches(value), "invalid_payload",
            "transfer_id must be a 32-character hex string")
        return value.lowercase(Locale.ROOT)
    }

    private fun integer(payload: JSONObject, name: String): Int {
        val value = payload.get(name)
        checkMedia(value is Number && value.toDouble().isFinite() &&
            value.toDouble() == value.toInt().toDouble(), "invalid_payload", "$name must be an integer")
        return (value as Number).toInt()
    }

    private fun checkMedia(condition: Boolean, code: String, message: String) {
        if (!condition) throw SentinelXMediaException(code, message)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MEDIA_PREFIX = "/ai-limbs/media/"
        const val MAX_CHUNK_BYTES = 1024 * 1024
        private const val HEADER_BYTES = 20
        private const val MAX_ENCODED_BYTES = 2 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = MAX_ENCODED_BYTES / 4 * 3
        private const val MAX_CACHE_BYTES = 4 * 1024 * 1024
        private const val MAX_ATTACHMENTS = 16
        private const val MAX_EXPORTS = 4
        private const val TTL_MS = 10 * 60 * 1000L
    }
}
