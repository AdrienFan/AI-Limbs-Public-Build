# Implementation

Retain extension identity and the existing search / describe / invoke tools. Add read-only result/media/status tools. Retain the current working tunnel wire endpoints.

Persist execution receipts before invocation and persist completed responses before delivery. Never repeat an execution whose completion is uncertain after a restart. Resume only delivery for completed results. Bound retained records and cached results; report capacity errors explicitly.

Declare screen-off CPU requirements through Bridge API 5 and use Host lifecycle signals. Report actual successful network timestamps. Keep protocol negotiation explicit and leave MCP Events for a later iteration.
