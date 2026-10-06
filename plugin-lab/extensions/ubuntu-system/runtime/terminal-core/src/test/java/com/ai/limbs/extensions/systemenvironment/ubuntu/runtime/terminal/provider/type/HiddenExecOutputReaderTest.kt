package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type

import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HiddenExecOutputReaderTest {
    @Test fun everyByteBoundaryPreservesChineseEmojiAndShellMarkers() {
        val expected = "__OPERIT_HIDDEN_BEGIN__:one\n汉😀兰儿\n__OPERIT_HIDDEN_END__:one:0\n" +
            "__OPERIT_HIDDEN_BEGIN__:two\n" +
            (0 until 800).joinToString("\n") { "PAGE_%04d 汉😀".format(it) } +
            "\n__OPERIT_HIDDEN_END__:two:7\n"
        for (size in listOf(1, 2, 3, 4, 7, 4095, 4096, 4097)) {
            val stream = object : ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    super.read(bytes, offset, minOf(length, size))
            }
            val actual = HiddenExecOutputReader(stream).use { reader ->
                buildString {
                    while (true) append(reader.readChunk() ?: break)
                }
            }
            assertEquals("byte read size=$size", expected, actual)
            assertFalse(actual.contains('\uFFFD'))
        }
    }

    @Test fun incompleteCharacterWaitsForItsRemainingBytesWithoutReplacement() {
        val input = PipedInputStream()
        val output = PipedOutputStream(input)
        val executor = Executors.newSingleThreadExecutor()
        val reader = HiddenExecOutputReader(input)
        try {
            val bytes = "😀".toByteArray(Charsets.UTF_8)
            output.write(bytes, 0, 2)
            val pending = executor.submit<String?> { reader.readChunk() }
            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                pending.get(100, TimeUnit.MILLISECONDS)
            }
            output.write(bytes, 2, bytes.size - 2)
            assertEquals("😀", pending.get(2, TimeUnit.SECONDS))
        } finally {
            output.close()
            reader.close()
            executor.shutdownNow()
        }
    }

    @Test fun promptWithoutNewlineIsDeliveredWhileTheShellStaysOpen() {
        val input = PipedInputStream()
        val output = PipedOutputStream(input)
        val executor = Executors.newSingleThreadExecutor()
        val reader = HiddenExecOutputReader(input)
        try {
            val pending = executor.submit<String?> { reader.readChunk() }
            output.write("兰儿😀> ".toByteArray(Charsets.UTF_8))
            assertEquals("兰儿😀> ", pending.get(2, TimeUnit.SECONDS))
            // A second command still uses the same decoder, without closing the shell.
            output.write("继续".toByteArray(Charsets.UTF_8))
            assertEquals("继续", executor.submit<String?> { reader.readChunk() }.get(2, TimeUnit.SECONDS))
        } finally {
            output.close()
            reader.close()
            executor.shutdownNow()
        }
    }
}
