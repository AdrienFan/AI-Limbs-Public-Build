# Host ABI and caller-aware Service Bus

- [x] Remove `InProcessSystemHostGatewayV1` and all role grants to Kernel Trust.
- [x] Preserve the legacy `PluginServiceEndpoint` contract.
- [x] Add a revocable caller-aware endpoint used only by the Host adapter.
- [x] Carry caller plugin ID, roles, and persisted granted scopes.
- [x] Add System Host ABI 2 service publication and delegated caller authority.
- [x] Revalidate provider record, caller lease, and service policy on every call.
- [x] Resolve delegated parent authorization from the active installed record.
- [x] Reject Kernel/non-requestable Host primitives from delegated calls.
- [x] Restore Plugin Center before ordinary plugins; reverse that order on shutdown.
- [x] Perform static JSON, forbidden-symbol, and diff checks only.
