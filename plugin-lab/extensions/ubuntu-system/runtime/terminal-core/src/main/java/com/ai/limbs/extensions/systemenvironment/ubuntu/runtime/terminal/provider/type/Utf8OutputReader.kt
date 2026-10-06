package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type

import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader

/** Stateful UTF-8 decoding for a live shell; reads do not wait for a newline or process exit. */
internal class Utf8OutputReader(input: InputStream) : Closeable {
    private val reader = InputStreamReader(input, Charsets.UTF_8)
    private val buffer = CharArray(4097)

    fun readChunk(): String? {
        var count = reader.read(buffer, 0, 4096)
        if (count < 0) return null
        // Keep a decoded supplementary character intact at the character-buffer boundary too.
        if (count > 0 && Character.isHighSurrogate(buffer[count - 1])) {
            val next = reader.read()
            if (next >= 0) buffer[count++] = next.toChar()
        }
        return String(buffer, 0, count)
    }

    override fun close() = reader.close()
}
