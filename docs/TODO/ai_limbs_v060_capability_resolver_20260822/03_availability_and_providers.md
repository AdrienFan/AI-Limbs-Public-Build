# 可用性、Provider 与权限 [DONE]

- Provider 分类覆盖 `ai_limbs_core`、`native`、`toolpkg`、`ubuntu`、`mcp` 与 `activation`。
- ToolPkg 和 MCP 的禁用状态会在搜索结果中直接报告，并提供启用建议。
- UI ToolPkg 能力复用 `AiLimbsUiCapabilityService`，按当前后端、无障碍授权和视觉子代理配置报告状态。
- Ubuntu 能力读取正式生命周期状态；查询不启动 Ubuntu，停止能力只在运行态报告可用。
- Resolver 读取 `ToolPermissionSystem` 的主策略与工具覆盖项；`FORBID` 报告为不可用，`ASK` 报告为可用但需要用户确认。
- `capability.search` / `capability.describe` 自身是核心只读导航工具，不触发执行权限弹窗，也不改变任何 Provider 状态。
- 截图、页面结构、点击、滑动、文本输入、按键和 Ubuntu 生命周期提供稳定语义 ID 与中文检索别名；其他能力使用机械稳定 ID。
