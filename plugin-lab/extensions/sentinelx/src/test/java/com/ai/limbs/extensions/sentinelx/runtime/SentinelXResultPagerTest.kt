package com.ai.limbs.extensions.sentinelx.runtime

import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelXResultPagerTest {
    @Test
    fun concurrentEvictionDoesNotLoseOrMixFirstPages() {
        val pager = SentinelXResultPager()
        val workers = 12
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until workers).map { worker ->
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    repeat(100) { iteration ->
                        val content = ("worker=" + worker + ";iteration=" + iteration + "\n") +
                            "正文".repeat(7000)
                        val first = pager.store(content)
                        val metadata = first.getJSONObject("bridge_result")
                        assertEquals(content.substring(0, 4000), first.getString("output"))
                        assertEquals(content.length, metadata.getInt("total_chars"))
                        assertEquals(digest(content), metadata.getString("sha256"))
                    }
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            jobs.forEach { it.get(45, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun completeUnicodePayloadRoundTripsWithDigest() {
        val content = "a".repeat(3999) + "🥰" + "长段落\n".repeat(6000)
        val pager = SentinelXResultPager()
        var page = pager.store(content)
        val cursor = page.getJSONObject("bridge_result").getString("cursor")
        val reconstructed = StringBuilder()
        while (true) {
            val chunk = page.getString("output")
            assertFalse(chunk.isNotEmpty() && chunk.last().isHighSurrogate())
            reconstructed.append(chunk)
            val metadata = page.getJSONObject("bridge_result")
            assertEquals(digest(content), metadata.getString("sha256"))
            if (metadata.isNull("next_offset")) break
            page = pager.page(cursor, metadata.getInt("next_offset"))!!
        }
        assertEquals(content, reconstructed.toString())
    }

    @Test
    fun evictedCursorFailsExplicitlyWhileReturnedFirstPageRemainsCorrect() {
        val pager = SentinelXResultPager()
        val content = "first".repeat(3000)
        val first = pager.store(content)
        val cursor = first.getJSONObject("bridge_result").getString("cursor")
        repeat(4) { pager.store(("later" + it).repeat(3000)) }
        assertNull(pager.page(cursor, 0))
        assertEquals(content.substring(0, 4000), first.getString("output"))
    }

    @Test
    fun invalidAndClearedCursorsAreRejected() {
        val pager = SentinelXResultPager()
        val content = "a".repeat(3999) + "🥰" + "b".repeat(20000)
        val cursor = pager.store(content).getJSONObject("bridge_result").getString("cursor")
        assertNull(pager.page(cursor, -1))
        assertNull(pager.page(cursor, content.length + 1))
        assertNull(pager.page(cursor, 4000))
        pager.clear()
        assertNull(pager.page(cursor, 0))
    }

    private fun digest(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
