package com.ai.limbs.extensions.rdc.runtime

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RdcResultPagerTest {
    private fun body(response: JSONObject) = JSONObject(response.getJSONArray("content").getJSONObject(0).getString("text"))

    @Test
    fun pagingReconstructsCompleteUnicodePayloadAndDigest() {
        val text = "a".repeat(3999) + "🥰" + "内容\n".repeat(9000)
        val pager = RdcResultPager()
        var page = body(pager.response(text, "text", true))
        val content = StringBuilder()
        val cursor = page.getString("cursor")
        val digest = page.getString("sha256")
        while (true) {
            val output = page.getString("output")
            assertFalse(output.isNotEmpty() && output.last().isHighSurrogate())
            content.append(output)
            if (page.isNull("next_offset")) break
            val offset = page.getInt("next_offset")
            page = body(pager.page(cursor, offset)!!)
        }
        assertEquals(text, content.toString())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }, digest)
        pager.clear()
        assertNull(pager.page(cursor, 0))
    }

    @Test
    fun rejectsOffsetInsideSupplementaryCharacter() {
        val pager = RdcResultPager()
        val page = body(pager.response("a".repeat(3999) + "🥰" + "b".repeat(20000), "text", true))
        assertNull(pager.page(page.getString("cursor"), 4000))
        assertNull(pager.page(page.getString("cursor"), -1))
    }
}
