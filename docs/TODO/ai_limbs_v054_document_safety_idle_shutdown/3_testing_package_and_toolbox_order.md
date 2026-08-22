---
title: Isolate the V0.5.4 package and prioritize AI Limbs tools
---

# Isolate the V0.5.4 package and prioritize AI Limbs tools

## Existing behavior

V0.5.3 and V0.5.4 debug builds share the `com.ai.assistance.operit.ailimbs.v05` application ID, so Android treats V0.5.4 as an update to the stable V0.5.3 installation. The toolbox lists frequently used AI Limbs entries among less frequently used tools.

## Intended behavior

- Give V0.5.4 the independent `com.ai.assistance.operit.ailimbs.v054` application ID
- Keep the V0.5.3 application and its data untouched when V0.5.4 is installed
- Identify the rebuilt package as V0.5.4 build 2
- Order the first toolbox entries as Command Terminal, Command Executor, Laner Access Manager, and Bridge Center
- Preserve the existing relative order of all remaining toolbox entries

## Verification

- The debug application ID differs from V0.5.3
- The four requested entry orders are unique and lower than every other toolbox order
- The standard `assembleDebug` workflow continues to build the isolated V0.5.4 APK

[DONE]
