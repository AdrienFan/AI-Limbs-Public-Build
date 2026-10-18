#!/usr/bin/env python3
"""Package the environment center with the existing trusted parent signer."""
import package_resident_bridge as packaging
packaging.TARGETS = (
    ("system-environment-center", "plugins/system-environment-center", "plugin.json", "payload/plugin.apk",
     "AILIMBS_PARENT_PLUGIN_PRIVATE_PEM", "ai-limbs-parent-plugin-dev-v1",
     "f9864bbfa24e7eb1324a47dc41c0fad57119a4d9ab3f516af891c6ab413c0aca",
     "System-Environment-Center", ".ailp"),
)
if __name__ == "__main__":
    packaging.main()
