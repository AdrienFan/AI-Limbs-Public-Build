package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.view.domain

import org.junit.Assert.*
import org.junit.Test

class CommandOutputLineAssemblerTest {
    @Test fun growingChineseEmojiLineDoesNotCreateNewlines() {
        val lines = CommandOutputLineAssembler()
        val output = StringBuilder()
        assertFalse(lines.append(output, "兰", true))
        assertTrue(lines.append(output, "兰儿", true))
        assertTrue(lines.append(output, "兰儿😀", true))
        assertEquals("兰儿😀", output.toString())
    }

    @Test fun newlineCompletesPreviewWithoutDuplicatingIt() {
        val lines = CommandOutputLineAssembler()
        val output = StringBuilder()
        lines.append(output, "first", false)
        lines.append(output, "兰", true)
        assertTrue(lines.append(output, "兰儿😀", false))
        assertFalse(lines.append(output, "next", false))
        assertEquals("first\n兰儿😀\nnext", output.toString())
    }

    @Test fun newCommandCannotReplaceThePreviousCommandsPreview() {
        val lines = CommandOutputLineAssembler()
        val output = StringBuilder()
        lines.append(output, "previous", true)
        lines.clear()
        output.clear()
        assertFalse(lines.append(output, "new", false))
        assertEquals("new", output.toString())
    }
}
