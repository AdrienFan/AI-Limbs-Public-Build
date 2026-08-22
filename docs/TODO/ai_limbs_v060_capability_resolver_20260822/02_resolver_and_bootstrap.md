# Resolver 协议与 Bootstrap [DONE]

- 新增只读协议工具 `capability.search` 与 `capability.describe`。
- `capability.search` 接受 `query` 和可选 `limit`，最多返回五条紧凑能力卡。
- `capability.describe` 接受 `capability_id`，按需返回完整参数、JSON Schema、权限、前置条件、可用状态、调用示例和 `source_locator`。
- Resolver 不暴露 `capability.invoke`；真实能力继续使用搜索结果中的 `invoke_id` 进入原 Dispatcher。
- 无匹配结果时触发 ToolPkg 元数据强制刷新后重查；发现流程不会启动 Ubuntu 或 MCP。
- `[AI Limbs system access policy]` 固定注入模块名 `AI Limbs Capability Resolver`、Provider `ai_limbs_core` 和两个稳定调用地址。
- Bootstrap 属于编译进核心的不可编辑策略；用户自定义接入提示仍单独追加。
