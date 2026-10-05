---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/bridge-receiver-output-pages
---

# AI Limbs-ChatGPT 0.0.9 notification

The ChatGPT child publishes no notification contribution, while SentinelX and RDC already use the Bridge API 5 notification surface.

Add a compact connection summary and two context-sensitive controls through the existing parent-owned notification contract. Keep Host rendering, notification lifecycle, provider selection and action revision checks authoritative.

Scope is the ChatGPT child, package version and documentation. No Host, parent Bridge or ABI change is planned. Configuration clearing and credential editing stay in the plugin panel.

Implementation and validation: [01-notification.md](01-notification.md).
