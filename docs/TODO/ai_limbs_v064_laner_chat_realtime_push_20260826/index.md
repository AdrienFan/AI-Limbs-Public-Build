---
For_Agent: V0.6.4 Laner Chat Queue Changed + Assistant Turn Protocol v5 source implementation
---

# V0.6.4 Laner Chat Queue Changed + Assistant Turn Protocol v5

## Problem being solved
V0.6.3.9 had a durable mailbox, but awareness was still pull-oriented and the model-facing path treated user sends too much like one-request/one-response turns. That prevented natural continuous user input while Laner was already working.

## V0.6.4 runtime ownership
AI Limbs now owns the deterministic mechanics: durable queueing, FIFO user-send persistence, queue-change metadata push, HIGH/NORMAL/LOW attention metadata, Assistant Turn claim boundaries, stop/resume state, reconnect reconciliation, and request/turn idempotency.

The model does not manage a polling loop, a prompt-side queue, or per-message checkpoints.

## User send behavior
- The Laner Bridge UI writes directly to the durable mailbox instead of opening a model-provider turn.
- The composer is released immediately after an immutable send snapshot is accepted.
- Rapid M1/M2/M3 sends enter one FIFO Channel with a single consumer, preserving durable seq order independent of IO coroutine scheduling.
- User messages may continue to arrive while an Assistant Turn is active.
- HIGH/NORMAL/LOW remains per-message metadata and does not block later sends.

## Queue Changed transport
- LanerChatBridgeService emits body-free queue-change events only after durable state changes.
- RDC broadcasts `laner_chat_queue_changed` with metadata only; message bodies are never included in the push event.
- Reconnect reconciliation uses the current durable queue snapshot rather than a prompt-side remembered cursor.
- Heartbeat advertises `laner_chat_turn_protocol_v5=true`.

## Assistant Turn Scheduler
- At most one ACTIVE Assistant Turn is owned for a session at a time.
- A claim selects unresolved requests in durable seq order and never mixes different chat IDs inside one Turn.
- Messages arriving after claim remain unresolved for a later Turn.
- `ai_limbs.chat.turn.reply` completes one managed Turn with one natural assistant reply covering all request IDs in that Turn.
- Stop cancels the active Turn but preserves its requests as unresolved and pauses scheduling.
- Resume continues scheduling without requiring a new user message; a new user send also resumes automatically.

## Legacy compatibility isolation
The old MessageProcessingPlugin remains only as a compatibility adapter. It no longer injects Laner context headers or action checkpoints. When a managed Assistant Turn claims a legacy request, its live stream is detached without canceling the durable request. Managed Turn completion does not complete the legacy stream, preventing duplicate AI chat output.

## Prompt contract
The V0.6.4 System Access Prompt states runtime facts only: AI Limbs owns queueing and Turn boundaries, realtime notifications are body-free, and managed Turns use `ai_limbs.chat.turn.reply`. It no longer asks the model to reproduce queue/scheduler mechanics in reasoning.

## Validation status
Source implementation and static checks only. No Android build, cloud build, APK install, or live-device E2E validation has been performed for this V0.6.4 work yet.

## Required live E2E acceptance
1. Send M1/M2/M3 rapidly and verify displayed/durable ordering remains M1/M2/M3.
2. While one Assistant Turn is active, send more messages and verify they remain available for a later Turn without disabling the composer.
3. Verify one Turn produces exactly one AI reply covering its claimed request IDs.
4. Press Stop and confirm the active Turn ends, requests remain unresolved, scheduler pauses, and Continue resumes them.
5. Switch chats during pending work and confirm a Turn never combines two chat IDs.
6. Verify unsolicited `laner_chat_queue_changed` metadata can arrive without message bodies.
7. Reconnect RDC and verify unresolved queue state reconciles without message loss or duplicate replies.
