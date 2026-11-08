# Privileged backend protocol relay

`host.privileged.runtime@1` adds the compatible `rpc` operation. Existing status/pair/prepare/stop/select operations keep their interfaces. `status` advertises `backend_rpc=true` when this operation is implemented.

The same existing owner/scope checks apply. Kernel uses only the already verified backend connection and does not return its raw Binder. The plugin supplies:

```json
{"operation":"rpc","descriptor":"plugin.owned.protocol.v1","transaction":4278608,"payload":{"operation":"snapshot"}}
```

The descriptor must match `[a-zA-Z0-9_.]{1,160}`. Transaction must be in `0x100000..0xffffff` (an application-private range). Payload is a JSON object limited to 65,536 UTF-16 code units. Kernel writes the interface token and a single JSON string, makes a synchronous transaction, reads the exception acknowledgement and JSON string, and rejects responses larger than 524,288 Parcel bytes. Parcel resources are released in all paths.

Both the operation binding and Kernel supported-operation inventory include rpc. Ownership matches the existing CROSS_PROCESS_BACKEND operations; there is no plugin-specific business routing. Missing live connection or unsupported remote protocol is an explicit error. When the acknowledged Resident Core owns the live connection, Host routes the same payload through the existing authenticated Core control wire, checks the active session, and returns a bounded response with the Core peer identity. RPC reads allow 15 seconds; other diagnostic requests keep their existing deadline. No raw Binder crosses into plugin code.

Backend protocol schema, app identities, authorization decisions and client process management belong to the plugin/backend. The kernel implements no third-party application allowlist or authorization database.

Source version is marked build122/versionCode217; the clone package is `.ailimbs.build122` for side-by-side comparison. Stable identity remains the explicit stable build variant. No Gradle build, tests or device validation were executed in this iteration.
