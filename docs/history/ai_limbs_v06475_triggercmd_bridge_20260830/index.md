# AI Limbs V0.6.4.7.5 · TRIGGERcmd Second Bridge

## 本阶段目标

- 将已通过 Android Lab Ping/Pong 验证的 TRIGGERcmd Transport 集成为正式 Bridge Provider。
- RDC 与 TRIGGERcmd runtime 可并行常驻；Provider Selector 只切换控制焦点，不停止另一座桥。
- TRIGGERcmd Token 仅保存在 Android 加密私有存储，不进入日志、通知、Capability Resolver 或源码。
- 自动创建 Computer、注册唯一 `AI Limbs Bridge` Command、订阅 Sails/Socket.IO room，并回传 Result。
- 当前正式验收仅开放 `Ping -> Pong`；其他参数明确返回 `BRIDGE_EXECUTOR_NOT_WIRED`。

## 架构边界

TRIGGERcmd 是 Transport Provider，不是新的 Capability Provider。
本阶段不复制 Capability Registry，不增加第二套 Policy，也不提供 params -> shell 执行路径。
后续结构化调用必须统一进入 RemoteInvocationExecutor -> Execution Policy Engine -> Dispatcher。

## 兼容性收口

- `runtimeState` 保留 primary RDC 语义，供既有 Policy / Laner Chat / RDC recovery 使用。
- `controlState` 表示 Bridge Center / 通知栏当前操作的 Provider。
- BridgeAction 支持显式 providerId；RDC 自救固定定向 RDC，避免 UI 焦点切到 TRIGGERcmd 后重连错桥。
- immutable System Access Bootstrap 动态公开当前 Work Manual read capability，避免无历史上下文时主动猜测或搜索手册入口。

## 后续阶段

- [V0.6.4.7.6 · Structured Executor](../ai_limbs_v06476_triggercmd_structured_executor_20260830/index.md)
