# AI Limbs Laner Chat Plugin (v0.2.8)

Laner Chat owns its durable mailbox, sessions, priority, Assistant Turn, and chat-mode presentation. The Host supplies generic chat slots, selection, composer, and history delivery through the chat-mode protocol.

The configuration card receives its own Compose click and calls `performClick()` on the plugin root View. The Host attaches the same selection listener to any chat-mode card; no plugin identity or business widget is compiled into the Host. This fixes touches consumed by an embedded ComposeView before they reach an outer Host Compose clickable.

`turn.reply` and `reply` persist the reply in the plugin, then publish it through `host.chat@1/publish_assistant`. The Host owns generic chat history storage and refresh; the compatibility route only exposes the plugin result. If publishing fails, the caller can retry with the same reply ID and content. Install on base build86 or newer.

Version 0.2.7 adds a quick chat overlay surface. The title bar moves the whole window. The circle shows the unread assistant count; opening it acknowledges the visible batch, and a successful reply clears those bubbles. The same `submit` path publishes user messages into the Host chat history. The plugin stores a per-chat read cursor and treats pre-upgrade history as read.

Version 0.2.8 lets the collapsed circle detect a drag while keeping a tap to open. The plugin sends pixel deltas through `host.window.overlay@1/update`; the Host moves the same window used by the expanded title bar. This gesture requires base build93 or newer.
