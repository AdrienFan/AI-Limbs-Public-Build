# 共享 Capability Catalog [DONE]

- 新增 Provider 中立的 `ToolCapabilityCatalog`，统一规范工具名称、描述、参数、来源、关键字、Schema 和启用状态。
- `CliToolModeSupport` 继续保留原有 `search` / `proxy` 协议与输出格式，但目录构建和排序改为消费共享 Catalog。
- Catalog 合并 `SystemToolPrompts`、`AIToolHandler` 运行时注册表、ToolPkg 元数据、可见 Skill 和 MCP 缓存。
- Ubuntu 生命周期等只存在于运行时注册表、尚未进入 `SystemToolPrompts` 的工具也会被发现。
- ToolPkg 由现有 `PackageManager` 解析与缓存，不重复扫描 APK 或重新解释 JavaScript。
