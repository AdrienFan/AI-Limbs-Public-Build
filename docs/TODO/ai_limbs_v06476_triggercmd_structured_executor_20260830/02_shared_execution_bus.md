# 02 · 共享执行总线

## 旧实现

RDC 自己持有 Policy Engine 与 Dispatcher。TRIGGERcmd Provider 没有执行入口，因此即使 Transport 收到请求，也不能复用 Resolver、权限链和领域服务。

## 新实现

- `AiLimbsRemoteInvocationExecutor` 持有一个 transport-scoped Execution Session、Policy Engine 与 Dispatcher
- RDC 与 TRIGGERcmd 分别建立独立 session，收据和 next_action 按真实 Transport 描述
- AI Limbs core invoke_id 直接进入 Dispatcher
- Resolver 目录匹配的 native、ToolPkg 与 MCP invoke_id 统一封装为 `ai_limbs.host_tool.execute`
- 未知工具仍进入 Dispatcher，返回统一的 `UNKNOWN_CAPABILITY`，不在 Transport 层猜测替代工具
- host 工具最终仍由 ToolExecutionManager 与 ToolPermissionSystem 执行，保留 ALLOW、ASK、FORBID

## 调用边界

TRIGGERcmd 只负责 framing、投递与 Result 回传。能力发现、收据、可用性、权限、持久化索引、Ubuntu 生命周期和领域终态检查仍由 AI Limbs 原生模块负责。

[DONE]
