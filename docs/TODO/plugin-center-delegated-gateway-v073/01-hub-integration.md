# Hub and SDK integration

- [x] Replace the temporary System Host Gateway SDK surface with Service Directory types.
- [x] Declare `system.plugin_center.delegated_gateway@1` as a Hub dependency.
- [x] Require the service owner to be the trusted Plugin Center identity.
- [x] Route publisher verification through `verify_child_publisher` only.
- [x] Route child capability calls through `invoke_child_capability`.
- [x] Recheck child declaration and live parent extension-point allowlist on every call.
- [x] Do not expose keyring installation, package verification, or a raw Kernel gateway.
- [x] Update Developer Guide claims after the trust closure is real.
- [x] Perform static JSON, forbidden-symbol, and diff checks only.
