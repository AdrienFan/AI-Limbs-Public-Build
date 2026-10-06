---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/bridge-receiver-output-pages
---

# AI Limbs-ChatGPT 0.0.10 access observations

The 0.0.9 listener is online, but the current ChatGPT conversation advertises the former demo tools. Unknown tool replies are delivered successfully without appearing as protocol failures in the overview. Transport connectivity and reply acknowledgement therefore do not establish usable tool access or successful business execution.

Keep the six tool names, extension identity, Bridge API 5 and working tunnel transport. Add current-listener observations, actionable unknown-tool errors and separate invocation outcome counters. Follow OpenAI's explicit metadata refresh and new-conversation procedure rather than assuming the server can update the client's tool catalog.

Scope is the ChatGPT child, tests, package version and documentation. Host permissions, lifecycle and parent notification rendering stay authoritative.

Implementation: [01-observations.md](01-observations.md). Validation: [02-validation.md](02-validation.md).
