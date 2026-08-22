# Ubuntu 空闲策略工具 [DONE]

- `ubuntu.idle.get` 返回模式、自定义分钟数和实际超时。
- `ubuntu.idle.set` 接受 `mode`；`CUSTOM` 模式必须传 `custom_minutes`，范围 1–1440。
- 两个工具沿用 `ToolExecutionManager` 的 ALLOW / ASK / FORBID 权限链。
- 接入策略告知 AI 正式调用方式。
