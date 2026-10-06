# Implementation and validation

Panel and notification online states now say Tunnel connected; connection controls explicitly operate on the tunnel. The overview and diagnostics open a dedicated catalog guide. It shows official Refresh steps, the six tool names read from the same engine definitions used by tools/list, and explains why adding Host capabilities does not require an outer catalog change.

Three demo entries are absent from the server catalog and have no legacy aliases. Their removal from an account requires refreshing that custom ChatGPT connection and retesting in a new conversation. Neither clearing device caches nor deleting encrypted execution receipts addresses client metadata. Current conversation metadata cannot be removed by the child. An unsigned-in browser currently prevents verification of the account-side Refresh; this is not reported as completed.

Version is 0.0.12 with versionCode 12 and payload application ID `com.ai.limbs.payload.chatgptprobe.v012`. Existing field/action IDs remain stable; the guide adds one navigation action. The MCP tool schemas, annotations and names remain unchanged, as does listChanged false. No Host or SDK change is needed.

Validate JSON, version consistency and source diff statically. Run the existing 31 regression definitions and Android packaging only in the cloud workflow. Device UI and fresh ChatGPT discovery require acceptance after deployment. No local compilation or test run is authorized.

[DONE] Source and documentation changes complete. Account metadata refresh and cloud build outcome are not yet verified.
