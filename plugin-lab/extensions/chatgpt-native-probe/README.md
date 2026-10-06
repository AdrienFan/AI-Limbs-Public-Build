# AI Limbs-ChatGPT 0.0.11

This Android child extension attaches to `plugin.system.bridge` through `ai_limbs.bridge.provider@5`. Host capability resolution, permissions, prerequisites and lifecycle remain authoritative. No ChatGPT-specific Host protocol has been added.

## Official connection workflow

This is a private, custom MCP connection through OpenAI Secure MCP Tunnel. It does not claim public plugin-directory approval. OpenAI's public submission requirements are a separate workflow and require a public HTTPS endpoint; the private tunnel alone does not satisfy them. See [Build an MCP server](https://developers.openai.com/plugins/build/mcp-server) and [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels), checked on 2026-10-06.

When tool names, descriptions, schemas or annotations change, keep the bridge running, open its custom MCP connection in ChatGPT Plugins, select Refresh, verify the new metadata and start a new conversation. Follow [Connect and test your plugin](https://developers.openai.com/plugins/deploy/connect-chatgpt). The bridge does not force refresh the client's catalog. Its connection-check action is a transport check, not ChatGPT's Refresh control.

Unknown tools return a JSON-RPC error with `data.gateway_error_code: TOOL_NOT_ADVERTISED`, the six advertised names, the metadata digest, official refresh steps and the documentation URL. Stale metadata is identified as a possible cause, not a proven diagnosis. Arbitrary input tool names and arguments are not echoed. Old demo tools are not mapped to new tools.

## Access observations

`status.success` describes successful status retrieval, not overall connection readiness. The `access` object reports successful current-listener polling, directory requests, accepted advertised tool calls and successful Host capability results separately. A status, search or describe call does not establish business invocation success. A valid cached tool call can be observed without a new tools/list request. `client_catalog_refresh_verified` remains false: these observations do not prove that a particular ChatGPT conversation refreshed its cache or displayed a result.

`state.access_observations` contains current-listener counters and timestamps. They reset at each start, including reconnect; old successful results do not establish readiness for a new listener. The original tunnel request and acknowledged reply counters retain their existing process-lifetime meaning. Duplicate requests may increase transport totals but do not repeat invocation accounting. Observations are memory-only and are not a durable business audit trail.

Protocol errors, unknown tools, capability invocation requests, successful results, failed/refused results, uncertain outcomes and result preparation failures are distinct. ASK/FORBID results are unsuccessful requests, not proof that a business action executed. `execution_state: UNKNOWN` is not a successful result. Successful Host results remain known if later result preparation fails: the tool returns an explicit delivery error with `execution_state: RESULT_RECEIVED`, `host_result_received`, `host_result_failed` and `automatic_reexecution: false`. Host policy remains attached when present. Interrupted calls from an obsolete listener cannot update the new listener's evidence; consult the encrypted receipts and domain state for those outcomes.

In 0.0.11, oversized-result cache limits, write failures and unavailable freshly cached pages reach the engine's preparation-error handler. The adapter no longer converts these failures into a nominally successful tool result. Host outcome counters and policy remain separate from the delivery error, and duplicate requests replay the saved error without another Host invocation.

The six stable tools have human-readable titles, explicit input schemas, object output schemas and conservative annotations. Dynamic Host results and paged envelopes intentionally retain extensible object schemas. Unsupported MCP versions are still rejected. Optional Events, subscriptions, skills imports and automatic catalog updates are not advertised by this release.

## Panel

The child keeps all presentation in the existing Bridge API 5 panel contract. The installed extension name, provider selection label, panel heading and MCP server title are `AI Limbs-ChatGPT`. Protocol IDs, signing identity, field IDs and tool names stay unchanged.

The overview shows a Chinese connection indicator, request/delivery counts, successful communication time and pending delivery warnings. Routine controls are chosen for the current phase. Settings, key replacement and diagnostics open on demand; the normal overview contains no secret input. First-time setup shows the tunnel ID and Runtime Key, with the control-plane URL behind Advanced Settings. Leaving forms clears the transient secret field. Clearing binding has its own confirmation view.

Overview and diagnostics also show current-listener access evidence and capability outcomes, separately from acknowledged replies. Diagnostics provide the official metadata refresh steps when current tools have not been observed or an unknown-tool request suggests stale metadata.

Diagnostics show counters, actual communication timestamps and errors. Pending receipt counts are scoped to the current tunnel. An unopened receipt journal is shown as not loaded; drawing the panel does not initialize it or claim zero pending records. Existing Plugin Center components continue to render the child-provided presentation; no Host styling code or Bridge ABI change is required.

## Notification shortcuts

The child now publishes the same Bridge API 5 notification contribution used by SentinelX and RDC. Select AI Limbs-ChatGPT in the Bridge panel and connect to show its notification under the existing parent lifecycle. The parent presents the selected provider and removes its notification when no connection or pairing transaction needs to remain active; this child does not create a separate always-visible notification or quick-settings tile.

The short title includes the Chinese connection phase. The notification shows request and acknowledged delivery counts, active request count when nonzero, and the last successful communication time. Authorization, connection and delivery problems use short guidance to the plugin settings or diagnostics. Credentials, tunnel IDs, raw request details and raw errors are never rendered in this contribution.

The 0.0.11 notification adds the access observation, capability outcome counters and safe catalog-mismatch guidance. A connected tunnel with no current tool requests is explicitly shown as awaiting discovery. Reply acknowledgements and protocol errors are labelled separately from successful capability results.

At most two controls are contributed: disconnect and reconnect while online, disconnect and connection check during startup, recovery and disconnect during connection errors, or connect and connection check when stopped if the parent notification remains present. Each control is intersected with the parent-provided available actions and checked again at dispatch, including configuration availability. Editing credentials and clearing configuration stay in the plugin panel. Tapping the notification uses the Host's existing app launch behavior; it does not add a direct settings deep link.

## Stable tools

- `ai_limbs_capability_search`: discover current capabilities without executing them.
- `ai_limbs_capability_describe`: read the schema and prerequisites of an exact capability ID.
- `ai_limbs_capability_invoke`: forward the exact capability ID and its parameters to `BridgeRemoteIngress`. An optional gateway `timeout_ms` controls the coroutine deadline, defaults to five minutes, and permits one second through thirty minutes. It is not passed into capability parameters.
- `ai_limbs_result_read`: read immutable result pages using the returned cursor and `next_offset`.
- `ai_limbs_media_read`: retrieve an immutable cached image without repeating the originating action.
- `ai_limbs_gateway_status`: inspect transport timestamps, ingress binding, receipt phases, catalog observations and delivery errors. A successful poll does not prove that ChatGPT refreshed its catalog or that a capability completed.

Search and describe before invoking when the ID or schema is unknown. Treat Host `execution_policy`, errors, prerequisites and `next_action` as authoritative. The generic invoke tool conservatively declares destructive and open-world hints; it is not labelled read-only.

## Execution and delivery

Polling, business execution and result delivery run separately. Four business requests may execute concurrently. The queue accepts at most 32 active requests before returning a durable busy response for additional business requests. Controls, notifications and result reads do not wait for business slots.

An encrypted execution receipt is committed before invoking a capability. The completed response is committed before POST delivery. The identity includes the control-plane binding, channel and tunnel request ID; a canonical payload fingerprint rejects reuse with changed input. A duplicate refreshes its shard token and replays the recorded response without invoking the capability again. Delivery acknowledgement checks the token to avoid losing a duplicate received during POST.

After process interruption, an unfinished receipt returns `execution_state: UNKNOWN` and `automatic_reexecution: false`. Cancellation reaches the ingress coroutine. Neither timeout nor cancellation proves that previous effects were undone. Inspect domain task or device state before deciding to repeat an action. Background task protocols remain owned by the relevant domain plugin.

Network failures, HTTP 408/429 and server errors retry delivery with bounded exponential backoff and jitter. Numeric Retry-After values are respected up to five minutes. Poll authentication and other permanent HTTP failures require attention. Result POST 401 retains the response and pauses delivery; other permanent response failures become `DELIVERY_FAILED` and can be retried when the same request is redelivered with a fresh token. Error bodies, credentials and shard tokens are not logged.

Receipts and cached results use AES-GCM in Android Keystore and atomic private files excluded from backup. Completed receipts are retained for at least 24 hours and expire during admission. Deduplication is bounded to that retention period; this is not an exactly-once guarantee. Limits are 4096 receipts and 32 MiB of receipt data. Capacity failure refuses admission rather than evicting pending executions. Successfully delivered image bodies are replaced in acknowledged receipts by their saved media handles; duplicate requests retrieve those images through `media_read` while available. Original image bytes are retained during pending delivery.

## Results

JSON results are returned once in `structuredContent` with a short text explanation. Explicit failure and Host ASK/FORBID outcomes set MCP `isError`; `error: null` does not. Policy fields stay intact. Native PNG/JPEG attachments are extracted recursively from `mcp_content`; binary data does not enter JSON pagination. Up to four images with a combined 2 MiB base64 budget are delivered. Android validates image MIME and dimensions without allocating decoded pixels, allowing dimensions up to 8192 and at most 32 million pixels. Invalid or oversized media produces a partial-delivery warning without changing business success.

Results larger than 12000 UTF-8 bytes use saved pages. Page offsets count UTF-16 units, and boundaries preserve surrogate pairs. Each page includes the original SHA-256, expiry, total character count and next offset. Result pages are not themselves paged again. A cached result may occupy up to 4 MiB; the cache permits 64 entries and 32 MiB, with a ten-minute lifetime. Reads enforce the original tunnel binding. Cache expiry or capacity errors are explicit and never reinvoke a capability. Oversized results that cannot be cached report completed execution with a delivery error and instruct callers to inspect existing state or artifacts.

## Host lifecycle and credentials

The provider requests screen-off CPU keepalive through Bridge API 5. The Host continues to own wakelocks and network/power monitoring. Network changes interrupt only transport polling, preserving in-flight domain work. Screen-on liveness uses successful poll timestamps. Bridge heartbeats use successful poll or response acknowledgement times, rather than the current clock while retrying. Generation checks prevent cancelled listener instances from overwriting a newer listener's state.

The panel supports Runtime Key rotation without deleting the tunnel or its execution receipts. Clearing binding stops the listener and removes credentials but retains encrypted receipts to prevent repeating old actions when the same binding is restored. Existing extension identity and secret preference names are preserved.

## Protocol and validation

The existing tunnel wire version `2026-08-25` and `/v1/tunnels/...` endpoints are retained. Wire version and MCP version are separate. Supported initialize versions are explicitly listed through `2025-11-25`; unimplemented versions are rejected rather than echoed. MCP Events, modern `server/discover`, subscriptions and domain-specific task APIs are not advertised in this release.

The cloud Android workflow runs `:chatgpt-native-probe-extension:testDebugUnitTest`, builds the APK and signs the `.ailx` with the existing signing secret. Tests cover result reconstruction, media delivery, policy errors, expiry, tunnel isolation, durable receipts, disk failure, concurrent controls, duplicate delivery, cancellation and interrupted-process recovery. JVM tests simulate the tunnel with MockWebServer; actual Android Keystore, screen-off survival and ChatGPT catalog refresh still require device validation after installation. Build and test locally only when explicitly authorized by the project workflow.
