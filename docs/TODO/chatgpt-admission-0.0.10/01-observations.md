# Access observations and tool contracts

Add distinct evidence for successful polling, tools/list requests, valid advertised tool calls and successful capability_invoke results. A result from search, describe or gateway status is not proof that a business capability was invoked. Reset this evidence for each listener start. Never label it as proof that a particular ChatGPT conversation refreshed its catalog.

Track JSON-RPC errors, unadvertised tool requests and invocation outcomes separately from raw tunnel request and acknowledged reply totals. Duplicate delivery must not increment invocation outcomes. Show safe observations and official refresh guidance in the panel and notification, without raw arguments or unknown tool names.

Return unknown-tool errors with the advertised names, metadata digest and a possible stale-catalog explanation. Do not route old demo names to new tools. Add human-readable tool titles and align schema constraints with existing validation. Preserve conservative invoke annotations and Host policy enforcement.

Sources checked on 2026-10-06: [Connect and test](https://developers.openai.com/plugins/deploy/connect-chatgpt), [Build an MCP server](https://developers.openai.com/plugins/build/mcp-server), and [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).

[DONE] Added current-listener observations, actionable JSON-RPC metadata errors, accurate outcome and preparation-failure accounting, explicit successful status retrieval, tool titles and shared panel/notification guidance. Kept the six tools and existing transport unchanged.
