---
feature: SentinelX Bridge Child v0.1.0
repo: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
status: active
---

# SentinelX Bridge Child v0.1.0

## 现状
AI Limbs Bridge 已有 RDC 与 TRIGGERcmd 两个 Android child Provider，均直接寄生于 AI Limbs Host，并通过 `BridgeRemoteIngress` 进入统一 Dispatcher / Policy Engine。

## 目标
新增与 RDC / TRIGGERcmd 平级的 SentinelX Bridge child Provider，不依赖 Ubuntu、PRoot、Python 或外部常驻子系统。

SentinelX 只负责 transport；不得复制一套 AI Limbs 权限系统。通过合法 SentinelX 身份与桥协议校验后的 `tool + args` 原样进入 `BridgeRemoteIngress`，最终 ALLOW / ASK / FORBID 只由 AI Limbs 决定。

## 必须具备
- Android/Kotlin 原生 WebSocket + SentinelX 1.10.0 wire protocol。
- Bridge 页面 Provider UI：状态、Host ID、Hub、设备名、授权 Token 输入、保存连接、清除绑定、重新授权。
- Bridge 常驻通知 UI：状态、Host ID、最后心跳、连接/停止/重连等动作。
- 授权信息不可写死；Token 使用 Android 加密存储，Host ID / Hub / Label 为可维护元数据。
- 支持换手机后在 UI 内重新 enrollment，不修改插件代码。
- 通用 `AIL_SENTINEL_BRIDGE_V1` 请求信封，不设 capability allowlist。
