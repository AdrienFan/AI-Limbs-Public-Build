package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type

import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader

/** Stateful UTF-8 decoding for a live shell; reads do not wait for a newline or process exit. */
internal class HiddenExecOutputReader(input: InputStream) : Closeable {
    private val reader = InputStreamReader(input, Charsets.UTF_8)
    private val buffer = CharArray(4096)

    fun readChunk(): String? {
        val count = reader.read(buffer)
        return if (count < 0) null else String(buffer, 0, count)
    }

    override fun close() = reader.close()
}
