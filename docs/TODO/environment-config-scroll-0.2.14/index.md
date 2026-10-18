# Environment configuration scrolling — 0.2.14

The parent configuration page scrolls as a whole, so arbitrary child-owned configuration cards remain accessible.
No Ubuntu identity, setting, network selection or business logic is added to the center.
Signed parent delivery uses the existing trusted package/signature implementation.

## Host activation failure
Observed on the installed 0.2.13 parent: FAILED, Required service is not available: system.plugin_center.ui_accessories.
The manifest's mandatory presentation service dependency blocked Host before the parent could publish its child point.
0.2.14 removes that dependency and the obsolete imperative register/unregister calls, retaining provider entry metadata.
No Host-specific dependency exemption or exception-swallowing path is introduced.
