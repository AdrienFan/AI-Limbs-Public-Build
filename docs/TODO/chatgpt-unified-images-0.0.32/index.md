---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
component: chatgpt-native-probe
version: 0.0.32
---

# Unified MCP image delivery

0.0.31 offers content_only and both on media_read. User-reported Chat testing sees
pixels only with content_only; Work sees both. Controlled Work reads of one cached
image, five samples per mode after warm-up, measured medians of 1573 ms and 1919 ms.
These small samples do not establish a transport cause or a guaranteed speedup.

Make content_only the default media_read response. Any successfully attached PNG/JPEG
in capability invocation, camera message context or final batch feedback uses the
same content envelope without structuredContent. Preserve all result metadata as
JSON text, including frame geometry, policy outcomes, media handles and pagination.
Text-only responses and result pages retain their existing structured output.
Keep explicit both for diagnostics and compatibility with 0.0.31 callers.

Scope: ChatGPT bridge adapter, tool default/schema, regression tests, package version
and documentation. No Host or Visual Manager changes, capture or re-encoding.

Cloud verification: default and explicit media reads, nested frame metadata,
paged image failure/policy preservation, existing feedback batch and text-only
regressions. Dispatch only chatgpt-native-probe tests/build/package.

[DONE] Implementation and regression definitions. Cloud results and deployed-device
Chat/Work validation are reported separately.
