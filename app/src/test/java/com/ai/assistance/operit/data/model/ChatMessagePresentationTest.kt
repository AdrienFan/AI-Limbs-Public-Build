package com.ai.assistance.operit.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessagePresentationTest {
    @Test
    fun semanticToneNormalizesSupportedValues() {
        assertEquals(
            ChatMessageSemanticTone.DANGER,
            ChatMessagePresentation.semanticTone("""{"semantic_tone":"DANGER"}""")
        )
        assertEquals(
            ChatMessageSemanticTone.INFO,
            ChatMessagePresentation.semanticTone("""{"semantic_tone":" info "}""")
        )
        assertEquals(
            ChatMessageSemanticTone.SUCCESS,
            ChatMessagePresentation.semanticTone("""{"semantic_tone":"success"}""")
        )
        assertEquals(
            ChatMessageSemanticTone.WARNING,
            ChatMessagePresentation.semanticTone("""{"semantic_tone":"warning"}""")
        )
    }

    @Test
    fun semanticToneRejectsUnknownOrMalformedPresentation() {
        assertNull(ChatMessagePresentation.semanticTone(""))
        assertNull(ChatMessagePresentation.semanticTone("not-json"))
        assertNull(ChatMessagePresentation.semanticTone("""{"semantic_tone":"plugin-private"}"""))
    }

    @Test
    fun normalizeKeepsOnlyGenericSemanticTone() {
        assertEquals(
            """{"semantic_tone":"success"}""",
            ChatMessagePresentation.normalize(
                """{"semantic_tone":"SUCCESS","plugin_specific":"must-not-cross-host-contract"}"""
            )
        )
    }
}
