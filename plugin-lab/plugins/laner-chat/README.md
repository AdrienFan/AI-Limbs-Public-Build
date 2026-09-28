# AI Limbs Laner Chat Plugin (v0.2.3)

Laner Chat owns its durable mailbox, sessions, priority, Assistant Turn, and chat-mode presentation. The Host supplies generic chat slots, selection, composer, and history delivery through the chat-mode protocol.

The configuration card receives its own Compose click and calls `performClick()` on the plugin root View. The Host attaches the same selection listener to any chat-mode card; no plugin identity or business widget is compiled into the Host. This fixes touches consumed by an embedded ComposeView before they reach an outer Host Compose clickable.

`turn.reply` and `reply` persist the reply in the plugin, then publish it through `host.chat@1/publish_assistant`. The Host owns generic chat history storage and refresh; the compatibility route only exposes the plugin result. If publishing fails, the caller can retry with the same reply ID and content. Install on base build86 or newer.
