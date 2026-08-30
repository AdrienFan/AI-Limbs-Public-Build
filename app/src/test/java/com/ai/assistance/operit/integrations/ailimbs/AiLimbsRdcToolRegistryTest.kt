package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsRdcToolRegistryTest {
    @Test
    fun registersMultipleNamesThroughOneRegistry() {
        val registry =
            AiLimbsRdcToolRegistry()
                .register(listOf("read_a", "read_b")) { JSONObject() }

        assertEquals(setOf("read_a", "read_b"), registry.names)
    }

    @Test
    fun mapsRdcCompatibilityNamesToCanonicalHostTools() {
        assertEquals("file_info", aiLimbsRdcHostAlias("get_file_info"))
        assertEquals("make_directory", aiLimbsRdcHostAlias("create_directory"))
        assertEquals(null, aiLimbsRdcHostAlias("start_search"))
    }

    @Test
    fun duplicateToolNamesAreRejected() {
        val registry =
            AiLimbsRdcToolRegistry()
                .register("same_name") { JSONObject() }

        var rejected = false
        try {
            registry.register("same_name") { JSONObject() }
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
