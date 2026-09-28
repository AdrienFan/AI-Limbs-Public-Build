---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# Quick chat overlay

The Host declares `host.window.overlay@1` but leaves it unbound. This change binds a generic Android window surface to a Host-local presentation provider. The Host owns permission, focus, position, drag header and lifecycle; Laner Chat owns content and read state. Plugin Center contributes a generic top-bar action.

Scope: Host primitive and presentation ABI in AI Limbs; overlay action in Plugin Center; quick inbox and composer in Laner Chat. The first window is intentionally small and uses the existing chat submit path.

Expected: top-bar action opens the floating chat; its header moves the entire window. Unread assistant messages are counted and shown until a reply, while the formal conversation receives the same sent and received messages.
