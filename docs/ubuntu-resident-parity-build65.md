# Stable build65 and Ubuntu 0.1.10

The stable Android APK keeps the `com.ai.assistance.operit.ailimbs.stable` package ID and existing signing configuration. Build65 increases `versionCode` from 159 to 160 so it can update build64 in place. No Host terminal feature code changes are required: the Ubuntu child extension owns the terminal presentation and uses the existing Host capability bridge.

The companion Ubuntu 0.1.10 `.ailx` is built from `fix/ubuntu-resident-terminal-parity` in this repository. Install/update that extension separately to receive the resident terminal frame, input ordering, and viewport fixes. An APK-only update cannot apply the extension fix. The current phone installation is Ubuntu 0.1.9, whose exact source was unavailable; 0.1.10 is built from the last verified published Ubuntu 0.1.7 source plus the parity patch. Validate Ubuntu behavior before treating the pair as a production release. Existing Ubuntu rootfs and user data must remain untouched.
