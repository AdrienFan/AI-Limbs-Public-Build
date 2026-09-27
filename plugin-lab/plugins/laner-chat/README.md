# AI Limbs Laner Chat Plugin (v0.2.2)

Laner Chat owns its durable mailbox, sessions, priority, Assistant Turn, and chat-mode presentation. The Host supplies generic chat slots, selection, composer, and history delivery through the chat-mode protocol.

The configuration card receives its own Compose click and calls `performClick()` on the plugin root View. The Host attaches the same selection listener to any chat-mode card; no plugin identity or business widget is compiled into the Host. This fixes touches consumed by an embedded ComposeView before they reach an outer Host Compose clickable.

`plugin.chat.laner_bridge.turn.reply` persists a reply in the plugin. The generic `ai_limbs.chat.turn.reply` compatibility route also mirrors the result into Host chat history; direct plugin capability calls do not perform that history step.
