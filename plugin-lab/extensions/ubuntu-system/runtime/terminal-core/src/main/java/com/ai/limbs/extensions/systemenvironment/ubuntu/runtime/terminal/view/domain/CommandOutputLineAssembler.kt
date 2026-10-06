package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.view.domain

/** Keeps live previews of an unterminated line separate from completed physical lines. */
internal class CommandOutputLineAssembler {
    private var provisionalStart: Int? = null

    fun append(builder: StringBuilder, line: String, provisional: Boolean): Boolean {
        val previous = provisionalStart
        if (previous != null) builder.setLength(previous)
        if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
        val start = builder.length
        builder.append(line)
        provisionalStart = if (provisional) start else null
        return previous != null
    }

    fun clear() { provisionalStart = null }
}
