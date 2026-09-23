# Bridge receiver compatibility (2026-09-23)

AI Limbs runs Android child receivers for Remote Desktop Commander and SentinelX. Their versions are independent of the public MCP plugin and the official SentinelX Python agent.

## Observed on build 66

RDC connected as 1.2.7 and its source package was 1.2.7. Text edit_block was forwarded as an unregistered Host tool and returned POLICY_TARGET_NOT_REGISTERED.

SentinelX connected with agent handshake 0.1.1 while its source package was 0.1.2. It advertised ping, capabilities, state and exec. sentinel_help returned unsupported_op.

Official sentinelx-cloud-core had published 0.19.2 on 2026-09-22. This Python agent targets Linux/macOS/Windows and cannot replace the Android child APK. The official ChatGPT RDC plugin page labels its own service 1.0.0; this is not a version of the AI Limbs Android receiver.

## Source changes

- RDC package 1.2.8 maps text edit_block to the registered edit_file capability. The Dispatcher still handles permission decisions. This adapter accepts one nonempty text replacement and rejects range/Excel edit modes it cannot represent.
- SentinelX child 0.1.3 adds a bounded help operation and advertises only its implemented operations. It remains a transport bridge for AI Limbs capability calls; it does not implement the official agent's read/list/edit/script/service operations.
- Neither source update is an installed receiver until a child package is built, signed, installed and checked through the live connectors.

Further official operation parity requires an explicit Android adaptation of each upstream contract, including response schema and access policy. Do not claim parity by changing the agent version string.
