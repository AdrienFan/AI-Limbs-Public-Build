---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/ubuntu-child-ownership-v0118
---


# Ubuntu DIRECT routing 0.1.21


The build101 Host already offers a SOCKS5 proxy bound to a validated NOT_VPN physical network. Ubuntu 0.1.20 copied that endpoint into its Host listener snapshot but did not apply it to normal commands. A single api.ipify.org failure was incorrectly treated as evidence that the entire DIRECT proxy could not access the Internet.


Live checks on 2026-10-06 establish DIRECT egress 58.17.152.139 through both AWS checkip and Cloudflare trace; the unproxied system/VPN egress is 118.166.21.123. DIRECT HTTPS to example.com returns 200. The api.ipify.org DIRECT request still fails, while its VPN request succeeds. Do not infer a broken proxy from that target-specific result.


Scope is the Ubuntu child process policy, Laner-net's explicit VPN wrapper, package version, cloud regressions and bundled Linux routing dependency. Keep host.network@1 and the deployed base 0.8.0.16-build101 unchanged. Host owns network selection, socket binding and DNS. The child owns GNU/Linux process routing, dependency deployment and helper policy.


See [Implementation and acceptance](01-routing-and-validation.md).
