# ChatGPT result cache adaptation0.0.30

Scope: transport result-cache accounting only. Visual acquisition, frame geometry and frame-bound taps remain in generic Host primitives and the Visual Manager plugin.

Replace per-save encrypted-body rescans with one cold scan and an in-memory expiry/byte index owned by the existing adapter. Warm saves only update metadata after successful writes. Preserve ten-minute expiry, binding isolation, byte/entry bounds and explicit failures. Cloud tests check warm reads, full capacity, expiry reclamation and failed-write semantics.

No device latency improvement is claimed before same-condition measurements. Other bridges can invoke universal visual capabilities; their own attachment delivery paths require separate verification.
