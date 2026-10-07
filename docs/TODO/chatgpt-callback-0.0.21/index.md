---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: cloud-validated-awaiting-device-callback
---

# 回调异常分类与诊断交付

2026-10-07 实机刷新后发现工具和 ai_limbs.wake_requested 事件。首个手动测试订阅未保存，桥记录 -32015，用户手机面板显示 verification_transport_failed；有效订阅和回调接收计数均为零。

已确认代码根因：GatewayEvents 丢弃网络异常类型，GatewayResults 不分语义删除所有 events 字段，导致状态工具无法交付回调诊断。公网回调实际失败原因尚不能确定。

作用域仅 ChatGPT 桥 0.0.21。保留七个 MCP 工具、事件定义、ABI5 和网络安全要求；基座和视觉插件无修改。继续当前分支，没有创建 PR。

- [01 分类与交付](01-failure-and-results.md)
- [02 验证记录](02-validation.md)
