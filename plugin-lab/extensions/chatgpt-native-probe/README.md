# AI Limbs-ChatGPT 0.0.31

This Android child extension attaches to `plugin.system.bridge` through `ai_limbs.bridge.provider@5`. Host capability resolution, permissions, prerequisites and lifecycle remain authoritative. No ChatGPT-specific Host protocol has been added.

## Official connection workflow

This is a private, custom MCP connection through OpenAI Secure MCP Tunnel. It does not claim public plugin-directory approval. OpenAI's public submission requirements are a separate workflow and require a public HTTPS endpoint; the private tunnel alone does not satisfy them. See [Build an MCP server](https://developers.openai.com/plugins/build/mcp-server) and [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels), checked on 2026-10-06.

When tool names, descriptions, schemas or annotations change, keep the bridge running, open its custom MCP connection in ChatGPT Plugins, select Refresh, verify the new metadata and start a new conversation. Follow [Connect and test your plugin](https://developers.openai.com/plugins/deploy/connect-chatgpt). The bridge does not force refresh the client's catalog. Its connection-check action is a transport check, not ChatGPT's Refresh control.

Ordinary Host capability additions are discovered through the stable search and describe entries without changing the eight MCP tools. Refresh is needed when their names or metadata change. The current three demo entries belong to old client metadata; they are absent from the server catalog. Refresh updates the connection metadata, and a new conversation is required to retest. Clearing Android binding, execution receipts or saved results does not remove ChatGPT metadata.

Unknown tools return a JSON-RPC error with `data.gateway_error_code: TOOL_NOT_ADVERTISED`, the eight advertised names, the metadata digest, official refresh steps and the documentation URL. Stale metadata is identified as a possible cause, not a proven diagnosis. Arbitrary input tool names and arguments are not echoed. Old demo tools are not mapped to new tools.

## Access observations

`status.success` describes successful status retrieval, not overall connection readiness. The `access` object reports successful current-listener polling, directory requests, accepted advertised tool calls and successful Host capability results separately. A status, search or describe call does not establish business invocation success. A valid cached tool call can be observed without a new tools/list request. `client_catalog_refresh_verified` remains false: these observations do not prove that a particular ChatGPT conversation refreshed its cache or displayed a result.

`state.access_observations` contains current-listener counters and timestamps. They reset at each start, including reconnect; old successful results do not establish readiness for a new listener. The original tunnel request and acknowledged reply counters retain their existing process-lifetime meaning. Duplicate requests may increase transport totals but do not repeat invocation accounting. Observations are memory-only and are not a durable business audit trail.

Protocol errors, unknown tools, capability invocation requests, successful results, failed/refused results, uncertain outcomes and result preparation failures are distinct. ASK/FORBID results are unsuccessful requests, not proof that a business action executed. `execution_state: UNKNOWN` is not a successful result. Successful Host results remain known if later result preparation fails: the tool returns an explicit delivery error with `execution_state: RESULT_RECEIVED`, `host_result_received`, `host_result_failed` and `automatic_reexecution: false`. Host policy remains attached when present. Interrupted calls from an obsolete listener cannot update the new listener's evidence; consult the encrypted receipts and domain state for those outcomes.

In 0.0.11, oversized-result cache limits, write failures and unavailable freshly cached pages reach the engine's preparation-error handler. The adapter no longer converts these failures into a nominally successful tool result. Host outcome counters and policy remain separate from the delivery error, and duplicate requests replay the saved error without another Host invocation.

The eight stable tools have human-readable titles, explicit input schemas, object output schemas and conservative annotations. Dynamic Host results and paged envelopes intentionally retain extensible object schemas. Unsupported MCP versions are still rejected. Events use the separate discovery path described below. Skills imports and automatic catalog updates are not advertised by this release.

## Panel

The child keeps all presentation in the existing Bridge API 5 panel contract. The installed extension name, provider selection label, panel heading and MCP server title are `AI Limbs-ChatGPT`. Existing protocol IDs, signing identity, field IDs and tool names stay unchanged; version 0.0.30 adds the batch tool.

The overview shows a Chinese connection indicator, request/delivery counts, successful communication time and pending delivery warnings. Routine controls are chosen for the current phase. Settings, key replacement and diagnostics open on demand; the normal overview contains no secret input. First-time setup shows the tunnel ID and Runtime Key, with the control-plane URL behind Advanced Settings. Leaving forms clears the transient secret field. Clearing binding has its own confirmation view.

Overview and diagnostics also show current-listener access evidence and capability outcomes, separately from acknowledged replies. Diagnostics provide the official metadata refresh steps when current tools have not been observed or an unknown-tool request suggests stale metadata.

In 0.0.12, the overview and diagnostics expose a Tool catalog guide with official Refresh steps and the six current names derived directly from the engine catalog. It distinguishes dynamic business discovery from outer MCP metadata changes and explains migration from echo, server_info and uppercase. Panel and notification controls use Tunnel connected, Reconnect tunnel and Check tunnel labels; none claims to refresh ChatGPT metadata. MCP listChanged remains false because no notification delivery and client auto-refresh have been verified.

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
- `ai_limbs_message_context`: read a fresh frame from an already active camera once at the start of a new user message. Does not start capture or request permission.

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

The existing tunnel wire version `2026-08-25` and `/v1/tunnels/...` endpoints are retained. Wire version and MCP version are separate. The published legacy initialize versions through `2025-11-25` remain supported. The new `server/discover` path advertises `2026-07-28` with tools and events capabilities. Event methods travel through the same authenticated tunnel endpoint. Domain-specific task APIs remain owned by their plugins.

The cloud Android workflow runs `:chatgpt-native-probe-extension:testDebugUnitTest`, builds the APK and signs the `.ailx` with the existing signing secret. Tests cover result reconstruction, media delivery, policy errors, expiry, tunnel isolation, durable receipts, disk failure, concurrent controls, duplicate delivery, cancellation and interrupted-process recovery. JVM tests simulate the tunnel with MockWebServer; actual Android Keystore, screen-off survival and ChatGPT catalog refresh still require device validation after installation. Build and test locally only when explicitly authorized by the project workflow.

## 0.0.13 capability ID normalization

The gateway now learns `capability_id -> invoke_id` mappings from live search/describe results. `ai_limbs_capability_invoke` accepts catalog capability IDs such as `native.list_files`, resolves unseen IDs through `capability.describe`, and caches both the catalog ID and exact invoke ID for 60 seconds. Resolution happens before business execution is marked started, so resolver failures are not misclassified as uncertain domain execution.

## 每轮相机画面

基座 build110、视觉插件 0.2.3、ChatGPT 桥 0.0.19 新增 `ai_limbs_message_context`。相机由用户明确开启后，模型在每条新消息开始时调用一次，直接收到该镜头的新帧。未开相机返回 INACTIVE；失败不会返回旧图、重拍或请求权限。该调用是工具反馈，无法替原生 ChatGPT 用户消息加附件，也不能强制上游模型执行。更新后在 ChatGPT 网页/PC 刷新 MCP 工具目录并开启新对话。

## 外部唤醒首轮验收

0.0.20 实现官方 [MCP Events](https://developers.openai.com/plugins/build/mcp-events) 的事件发现、订阅、续期、退订和签名 HTTPS 投递，文档核对日期 2026-10-07。本轮仅开放 `ai_limbs.wake_requested`、`source_id: manual`；没有相机定时采样或画面变化检测。基座 build110 和视觉插件 0.2.3 无需修改。

支持入口按官方要求使用网页版 Work、桌面版 Work 的 Cloud 或 dots。当前私有 Secure MCP Tunnel 是否完整转发 Events，以及当前账户能否创建事件任务，必须实机验证；本地模拟接收端或 webhook 的 2xx 均不能证明 ChatGPT 已响应。

1. 安装本轮桥并连接，网页或桌面端 Refresh 连接元数据，开启新的受支持会话。
2. 告诉 ChatGPT：订阅 `ai_limbs.wake_requested`，参数 `source_id=manual`，收到后回复“收到手机唤醒测试”，无需读取相机。
3. 在 AI Limbs 的桥面板打开“外部唤醒”，确认有效订阅至少一条，再点击“发送唤醒测试”。也可通过现有通用能力调用入口执行 `plugin.chatgpt_native_probe.wake.test`，参数为空。
4. 分别检查订阅回调验证、事件待发/接收状态和对话中实际出现的回复。只有最后一项完成才算端到端通过。无有效订阅时按钮禁用，能力调用返回明确错误。

订阅身份按当前认证隧道绑定、回调 URL、事件名及规范化参数确定。本桥为单手机私有连接，隧道绑定是认证主体边界，不宣称支持同一隧道下多个独立账户的授权隔离。暂停或卸载桥停止投递；换隧道不发送原绑定事件。订阅、签名密钥和待发事件沿用 Keystore AES-GCM 私有文件，不展示回调地址、密钥或原始错误。

回调校验要求 HTTPS、公网地址、TLS 主机名校验和签名随机挑战，不跟随重定向。每次实际 DNS 连接检查所有目标地址，IP 字面量单独检查。相同身份和密钥的验证缓存最多五分钟；更换密钥重新验证，六十秒内使用新旧双签名，窗口过后删除旧密钥。订阅默认六小时，最长二十四小时，最短一分钟；不接受非空重放 cursor。

整个存储最多十六条有效订阅、三十二项待发投递、十六份诊断。事件寿命十五分钟，投递最多四次；临时失败进行有限指数退避，遵守有上限的 Retry-After，410/413 不重试。重试沿用同一事件 ID 和序列化内容，更新签名时间，不重做手机操作或取图。到期条目由运行中的投递循环或下一次订阅/排队清理；停止期间不运行清理计时器。回调验证最多接受两项并发请求，使用独立通道，不占业务执行槽位。

`status.events` 只记录本机可证实的阶段和计数。`webhook_accepted` 意味着回调返回 2xx；`model_response_verified` 始终为 false，因为桥没有 ChatGPT 对话回复的确认接口。后续相机及其他外部事件源应在本轮上游验收成功后接入，继续沿用订阅、签名、队列和生命周期。

## 0.0.21 回调失败诊断

0.0.20 的实机事件发现已通过：ChatGPT 工具目录和事件源都能识别本桥。但首个订阅返回 -32015，手机显示 verification_transport_failed，有效订阅为零。这只证明请求抛出异常；旧代码丢弃了异常类型，无法区分具体网络原因。

0.0.21 修复两处已确认的代码问题：回调异常按安全类别与实际网络阶段记录；结果适配器不再递归删除名为 events 的业务字段。状态工具现在能交付 status.events，包括 last_error 和 last_diagnostic，面板同时显示失败阶段、异常分类或 HTTP 状态码。事件列表和大型事件数据继续使用原有图片处理、缓存容量和分页规则。

网络类别包括 dns_resolution_failed、non_public_destination、connection_failed、timeout、tls_handshake_failed、tls_peer_verification_failed、tls_failed、http_protocol_failed、io_failed 和 transport_exception。超时结合 stage 区分 DNS、连接、TLS、请求和响应读取。非 2xx 校验响应记录 verification_http_failed 及状态码；2xx 挑战不匹配仍是 challenge_failed。分类数据不含回调地址、密钥、原始异常消息或响应内容。

这版不改变 DNS 解析路线、HTTPS、证书、公网地址检查、重定向和代理策略，不将 private/fake IP 当作公网目标，也不自动更改手机 VPN。非公网目标提示用户核对 VPN DNS / 假 IP 设置只是排查方向，不能据此断言本次故障由 VPN 引起。

安装后保持桥连接，再尝试创建一次手动唤醒订阅；若失败，通过状态工具读取新的分类与阶段。若成功，再点击手机面板测试按钮并核对实际 ChatGPT 回复。0.0.21 的目的为补齐根因证据和修复诊断交付，不能以构建或模拟测试通过宣称公网回调已经修复。MCP 工具名与事件定义未变，此次安装后无需为诊断字段单独刷新目录。


## 0.0.22 动态回调解析

回调域名通过 DNS over HTTPS 获取真实公网 IP，再进行已有的公网地址校验。默认服务为 https://cloudflare-dns.com/dns-query；服务域名使用当前手机网络和系统 DNS 连接，保留正常 TLS 信任，不设置固定 VPN 节点、callback IP 或 bootstrap IP。VPN fake-ip 只用于到解析服务的正常 HTTPS 访问，不能成为事件回调目标。

面板路径：外部唤醒 → 回调网络设置，或连接设置 → 回调网络设置。填写支持 RFC 8484 POST 的公网 HTTPS DNS 服务地址，点击保存；下一次回调使用新配置，无需替换隧道凭据、刷新工具目录或修改手机 VPN。输入地址不可包含账号、查询参数或 fragment。

每次回调建立新连接并重新查询，不持久缓存地址，不设置 HTTP DNS 响应缓存。解析服务与回调禁止重定向，解析请求有 6 秒 deadline，最多 4 个并行请求；停止桥时取消 DNS 与回调请求。保留 A/AAAA 回答中任何非公网地址即拒绝整个回答的规则，证书校验和原域名 SNI 保持不变。服务不可用时报告 dns_https 阶段的安全分类，不自动切换服务、使用系统 DNS 回退或重试业务操作。

状态工具增加 callback_dns：解析模式、provider_host、成功/失败计数、最近成功时间和安全错误类别；不返回 callback 域名、URL、DNS 服务路径或签名密钥。工具及事件元数据、ABI5 和之前的连接配置兼容，旧配置自动获得默认解析服务。

云端测试包含真实 TLS 的 DNS wire-format POST、更换服务/返回 IP 后重新查询、fake-ip 拒绝、HTTP 错误/重定向不降级和 TLS 信任验证。公网回调验证及 ChatGPT 实际回复仍需安装后复验。



## 0.0.23 TLS 内部原因诊断

实机 0.0.22 已完成一次 HTTPS DNS 查询，success=1、failure=0；回调进入 TLS 后出现 SSLHandshakeException，测试订阅没有保存。DNS 修复实机生效，不等于回调和唤醒通过。

0.0.23 保留至多 8 层异常类名及标准证书验证枚举，不保存异常消息、URL、域名、证书正文或签名密钥。分类区分证书过期/尚未生效、信任链验证、协议问题、EOF 关闭与套接字中断，面板和状态诊断交付相同字段。此次仅补齐证据，不调整 TLS 信任、IP 校验、DNS/代理路线或超时，不能作为回调故障已修复的结论。


## 0.0.24 当前网络路由筛选

0.0.23 实机订阅未通过：DoH success=1，connect 阶段出现 NoRouteToHostException。手机 VPN tun0 只有 IPv4 源地址，IPv6 路由表有 unreachable default；旧代码将 A/AAAA 回答直接交给关闭连接重试的客户端，没有按当前网络路由筛选。

生产桥在连接前重新读取调用进程默认网络的 LinkProperties；所有 DNS 回答先完成公网校验，再按源地址族、目标最长前缀路由和 unicast 类型选择可路由地址。没有可用地址时明确返回 route 阶段；不借用其他 Wi-Fi/蜂窝网络、不绑定节点、固定 IP 或失败后改道。默认网络支持 IPv6 时保留 IPv6。API 26–32 使用系统仅公开的 unicast 路由；API 33+ 同时判断显式 unreachable/throw。

callback_dns 新增 route_selection 候选/选中计数及 last_connection_family，不返回回调地址。面板展示相同信息。保持 DNS、原域名 SNI、证书和签名校验，以及禁止重定向/连接重试的策略。修复明确的路由选择缺陷，之前 TLS 失败是否仍存在须安装后验证。

本轮只提交 ChatGPT 组件云端测试、编译和打包；提交后遵照用户要求停止轮询，云端结果和实机问题由用户反馈。未运行本地项目测试或构建，未宣称云端测试或公网唤醒已通过。

## External wake switch in 0.0.25

Open the external-wake secondary panel and select Enable external wake / Disable external wake.
The persistent switch defaults to OFF, including the first upgrade from a version without this setting.
Disabling rejects new wake requests and clears pending deliveries for every tunnel binding. Enabling
retains verified subscriptions and admits only fresh events. It does not replay the cleared queue.
Subscription validation/refresh remains available while OFF; verification alone is not a wake event.
The MCP listener and ordinary tools remain available.

Switch changes, event admission and delivery share one mutex. Disable waits for an already-started
callback to finish, then clears queued events before returning. Events accepted upstream may still
produce a response; the phone cannot retract a ChatGPT run. Restarting while OFF also clears pending
records before any delivery. Storage errors propagate without reporting a saved switch.

The panel switch is a direct user UI action. It does not invoke the AI wake-test capability or request
an additional ASK. The installation manifest and entry contain no plugin-specific ASK declaration.
The Host retains authoritative tool permissions. Its build110 source already carries explicit UI
authorization for provider selection. An installation-time ASK has not been reproduced in this edit;
no global permission default or AI invocation policy was changed to suppress an unidentified prompt.

New regression tests cover disabled admission, all-binding queue cleanup, restart recovery, in-flight
serialization and failed setting persistence. They are submitted for cloud execution; no local project
build or tests were run.

## Delivery snapshot race in 0.0.26

Cloud run 37569375137 compiled 0.0.25, but the concurrency test timed out at GatewayEngineTest.kt:193
while waiting for exactly five business responses. The cloud log does not record the actual response
count. Code review identified a real race: deliveryLoop iterates a detached READY list; an earlier POST
can ACK a receipt and remove its in-flight job before the iterator reaches that stale entry. It could
then submit an acknowledged response again.

The scheduler now reads the authoritative READY receipt after checking the in-flight map. An ACKED,
failed, executing, absent or different-binding receipt is not admitted. A refreshed duplicate token
still uses the current record, and business execution is not repeated. No delivery persistence format,
retry policy or concurrency limit changed.

Deterministic ledger tests cover the stale-list/ACK ordering, binding and phase isolation, fresh tokens
and detached reads. The integration concurrency test waits for all distinct expected IDs and all six
ACKs, then still asserts exactly five business responses and four maximum concurrent business calls.
It does not accept duplicate deliveries by changing the assertion to >= five.

The newly added wake-switch tests passed in run 37569375137. This repair is submitted for cloud testing,
compilation and packaging only; no local project tests or build were run, and the new run is not polled.

## Request processing diagnostics in 0.0.27

The bounded gateway-status timing samples now identify the resolved capability and separate
capability resolution, the Host invocation, result adaptation, and cached page/media reads.
Result adaptation additionally reports monitor wait, adapter work, cache scan and write costs,
scan passes, cumulative entries and plaintext bytes scanned, image Base64 characters, and
structured text bytes. Durations are monotonic milliseconds.

Diagnostics retain only public capability identifiers, durations and aggregate sizes/counts.
They never expose parameters, result contents, record names, callback URLs or signing keys.
Missing stages remain absent or null; measurements are recorded on exceptional paths as well.
No operation is re-executed for measurement.

This release is diagnostic only. It retains the existing image settings, cache expiry and
capacity checks, encryption, delivery semantics and wire protocol. The observed 10–17 second
interaction latency has not been consistently reproduced; this version does not claim a fix.
Compare host_invoke_ms with result_adapt_ms, then result_adapter_wait_ms, cache_scan_ms and
cache_write_ms, alongside the existing response_post_ms to attribute the next slow request.

## Response POST diagnostics in 0.0.28

Version 0.0.27 reproduced a 10.8-second tool call: Host invocation took 1.52 s,
result adaptation 0.13 s, and the response POST 5.68 s. Smaller stored previews did
not consistently return faster than larger ones. This identifies a slow transport
phase, but does not yet distinguish local upload from connection setup or waiting
for response headers. The full PNG-to-JPEG screenshot path remains another suspect.

The latest POST attempt adds numeric fields to the same bounded request timings:
- `post_request_start_ms`: call start to first request headers; includes dispatch,
  connection selection and any DNS/connect/TLS work before that request.
- `post_dns_ms`, `post_connect_ms`, `post_tls_ms`: cumulative observed phases, including
  failed connection attempts. Connect includes TLS, and all three overlap the first
  field; do not add them together. A reused connection omits unobserved setup phases.
- `post_upload_ms`, `post_body_bytes`: body writes and byte count across HTTP attempts.
  Socket write completion does not prove the remote server has received all bytes.
- `post_wait_headers_ms`: body write completion to response header completion;
  includes network transit, remote processing and header reading. It is not a pure
  server processing measurement. An interrupted phase records time until failure.
- `post_completion_ms`: final response headers to local response close/cleanup.
- `post_connect_attempts`: connections attempted by this call, when observed.

HTTP-level retries accumulate within a POST; a later gateway delivery attempt
replaces its previous network fields. No URL, host, IP, proxy, headers, request IDs,
body contents or credentials enter these metrics. Pools, dispatcher, routing, TLS,
timeouts, retries, wire format and screenshot quality remain unchanged. This release
adds evidence and does not claim a latency fix.

## 0.0.30 连续 UI 操作

新增 `ai_limbs_capability_batch`，需刷新自定义 MCP 工具目录。输入 `steps` 为 1..8 条预先确定的原生 UI 操作，每项含 `capability_id` 和 `parameters`；可传 `timeout_ms`。仅接受 tap、long_press、click_element、swipe、set_input_text、press_key、start_app 和明确 `screen_action:true` 的 execute_shell。
先解析全部地址并确认基座支持最终反馈读取，再顺序执行。每一步仍经过原有 Host 权限；中间步骤传 screen_feedback:false，结束或首次失败后调用 ai_limbs.operation_feedback.read 取一次已有共享屏的新图。未开启共享不会自动启动。
失败/不确定时停止后续步骤，已执行的动作不撤销，不自动重做。返回每步结果、尝试/成功数量、失败序号及单独的 feedback_result；反馈失败不改写已收到的业务结果。去重及持久化投递仍以整条请求为单位。一条 batch 按一次桥调用统计，Host 对其中每一步独立执行权限检查。
配套基座 build111、视觉工作台 0.2.4。只合并不依赖中间画面判断的步骤；新页面、动态内容或不确定路径仍应逐步看图决定。保留既有单能力工具和协议。

## Result cache accounting

0.0.30 scans cached media/result bodies only on the adapter cold path and accounts subsequent saves using expiry/size metadata. This change is transport-only; visual frame and tap capabilities live in the universal Visual Manager plugin.

## 0.0.31 MCP image A/B diagnostic
Only the existing ai_limbs_media_read tool is extended. Optional `response_variant` may be
`both` (default, unchanged) or `content_only` (omits `structuredContent` entirely).
Each variant reads the same immutable media_id from the same cached JPEG, without running
the originating capability again. It retains the same `content` array and media metadata,
and the original Host policy/Bridge boundaries. This is a temporary diagnostic, not a fix.
After installing, refresh the custom MCP plugin catalog in ChatGPT and start a new chat.
