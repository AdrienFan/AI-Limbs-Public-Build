package com.ai.assistance.operit.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleasedProviderModelKeyDecoderTest {
    @Test
    fun `toolpkg provider id preserves underscores when separating the model`() {
        assertEquals(
            ReleasedProviderModelKey(
                storedProviderModel = "TOOLPKG_example_openai_compatible_provider:deepseek-chat",
                provider = "Example OpenAI Compatible Provider",
                model = "deepseek-chat",
            ),
            ReleasedProviderModelKeyDecoder.decode(
                "TOOLPKG_example_openai_compatible_provider_deepseek-chat",
                mapOf("TOOLPKG_example_openai_compatible_provider" to "Example OpenAI Compatible Provider"),
            ),
        )
    }

    @Test
    fun `future toolpkg providers use the same exact identity rule`() {
        assertEquals(
            ReleasedProviderModelKey(
                storedProviderModel = "TOOLPKG_future_provider_with_underscores:model_with_underscores",
                provider = "Future Provider",
                model = "model_with_underscores",
            ),
            ReleasedProviderModelKeyDecoder.decode(
                "TOOLPKG_future_provider_with_underscores_model_with_underscores",
                mapOf("TOOLPKG_future_provider_with_underscores" to "Future Provider"),
            ),
        )
    }

    @Test
    fun `toolpkg provider id takes precedence over a shorter display name`() {
        assertEquals(
            ReleasedProviderModelKey(
                storedProviderModel = "TOOLPKG_future_provider:model",
                provider = "Future Provider",
                model = "model",
            ),
            ReleasedProviderModelKeyDecoder.decode(
                "TOOLPKG_future_provider_model",
                mapOf(
                    "TOOLPKG" to "ToolPkg",
                    "TOOLPKG_future_provider" to "Future Provider",
                ),
            ),
        )
    }

    @Test
    fun `legacy provider names are decoded when they are no longer registered`() {
        assertEquals(
            ReleasedProviderModelKey(
                storedProviderModel = "示例供应商:deepseek-chat",
                provider = "示例供应商",
                model = "deepseek-chat",
            ),
            ReleasedProviderModelKeyDecoder.decode("示例供应商_deepseek-chat"),
        )
        assertEquals(
            ReleasedProviderModelKey(
                storedProviderModel = "unknown:provider_model",
                provider = "unknown",
                model = "provider_model",
            ),
            ReleasedProviderModelKeyDecoder.decode("unknown_provider_model"),
        )
    }

    @Test
    fun `malformed released keys still fail with a precise error`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleasedProviderModelKeyDecoder.decode("unknownprovidermodel")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasedProviderModelKeyDecoder.decode("_model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasedProviderModelKeyDecoder.decode("provider_")
        }
    }
}
