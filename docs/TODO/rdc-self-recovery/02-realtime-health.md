# 02 Realtime health proof

## Old behavior

The native transport considered a periodic Phoenix heartbeat healthy when `WebSocket.send()` returned true. A half-open socket can accept a local send even when the hosted Realtime service can no longer reply.

## New behavior

- Every health heartbeat receives a unique Phoenix ref.
- The client waits for the matching `phx_reply` with `status=ok`.
- A missing acknowledgement fails the transport and enters reconnect/recovery handling.
- REST `status=online`, `last_seen`, and `transport_broadcast_v1` are published only after the Realtime heartbeat round trip succeeds.
- Manual recovery force-cancels the stale socket before the replacement transport is installed.
- Transport ownership is checked when an old cancelled worker cleans up, so it cannot close a newer socket.

## Acceptance

`status=online` is not sufficient. Final acceptance requires a real remote RDC tool call from ChatGPT after the V0.6.3.8 cloud build is installed.

[DONE]
