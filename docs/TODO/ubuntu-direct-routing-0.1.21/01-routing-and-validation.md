# Implementation and acceptance


Install the Ubuntu-distributed arm64 proxychains-ng 4.17 routing library as a child runtime asset. It preloads only inside GNU/Linux login shells, after Android and PRoot process launch. A strict single SOCKS5 route delegates destination DNS and outbound TCP to the Host DIRECT proxy. Loopback is exempt so local RPC, the Host listener and explicit phone proxy remain reachable. The Host sync refreshes the shared route configuration when the physical network or endpoint changes. New child execs read that configuration; existing connections do not migrate.


If a physical route is absent, the child publishes a refused loopback route rather than silently using the system VPN. Local shell/file commands remain usable. Missing routing assets prevent an unconfigured network session. Failed DIRECT targets are explicit failures; no automatic retry through Laner-net is added.


The default policy covers dynamically linked TCP clients that use libc, including the live curl and Python urllib acceptance cases. It is not a root-level routing table change and does not establish coverage for static programs, UDP, ICMP or programs bypassing the libc hooks. GUI browser and program-specific compatibility need their own acceptance. Do not label all Ubuntu traffic as verified DIRECT.


Laner-net 0.3.0 removes the child's routing preload for its probes and executed commands, publishes VPN mode, rejects a cached proxy that points to the Host DIRECT endpoint, and excludes that endpoint from listener discovery. Its nested shell preserves explicit VPN mode instead of re-applying DIRECT in the login profile. The `env` command also removes DIRECT state for subsequent child processes. Unproxied-system probes are labelled honestly because that route may be the VPN.


Version is Ubuntu 0.1.21, versionCode 22 and payload application ID com.ai.limbs.payload.systemenvironment.ubuntu.v0121. The extension identity and public command/session contracts stay stable. The Linux dependency is from Ubuntu noble arm64, not an Android native executable. Upstream source, Debian packaging, copyright and GPL license accompany its provenance.


Live acceptance on 2026-10-06 passes: a fresh GNU/Linux login shell exports DIRECT; plain curl and Python urllib reach AWS checkip with IP 58.17.152.139. An explicit Laner-net nested login shell keeps VPN mode, removes the DIRECT preload and reaches the same target with IP 118.166.21.123. With the DIRECT preload active, a loopback HTTP/RPC service returns HTTP 200. An isolated unavailable-route configuration refuses external TCP with curl exit code 7 while /bin/true remains usable. Laner-net status excludes DIRECT port 39065 and retains the working phone proxy at 127.0.0.1:7890. ChatGPT Bridge 0.0.13 reports ONLINE and healthy polling.


Existing open sessions retain their launch environment and must be reopened; existing connections do not migrate. Runtime acceptance installed the child scripts/library/profile without changing the Android base or VPN settings. Ubuntu Android package version remains 0.1.20 until the cloud-built 0.1.21 package is deployed; package initialization and Host-sync configuration refresh still require post-deployment acceptance.


Eight cloud regressions cover stale/missing DIRECT snapshots, proxy environment removal, cached/direct endpoint exclusion and nested explicit VPN shells. Python static syntax, shell static syntax and git diff whitespace checks pass. Compilation and unit regressions run only in the cloud workflow; they have not been run locally or reported as passed.


[DONE] Child implementation and live TCP split-routing acceptance complete. Cloud compilation/regressions and Android package deployment remain separate stages.
