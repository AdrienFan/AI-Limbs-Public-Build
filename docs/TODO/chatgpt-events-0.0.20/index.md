---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: cloud-validated-awaiting-device
---

# ChatGPT 外部唤醒首轮验收

原桥 0.0.19 仅处理被动工具调用，既无事件订阅也无后台触发 ChatGPT 的协议入口。用户选择沿用 ChatGPT 官方 MCP Events，并以外部唤醒作为以后视觉及其他事件的基础。

先实现手机手动事件、回调验证和持久投递，验证 ChatGPT 在用户不发新消息时确实响应，再接画面变化。首次验收不启用相机采样，避免在上游未支持时制造无效帧和流量。

作用域仅 ChatGPT 子插件 0.0.20，保留已有七个工具和 ABI5。基座 build110、视觉 0.2.3 无代码修改。沿用当前开发分支；本轮没有创建 PR。

- [01 协议与投递](01-protocol-and-delivery.md)
- [02 面板与验收](02-panel-and-validation.md)

参考官方 [MCP Events](https://developers.openai.com/plugins/build/mcp-events) 和 [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)，核对日期 2026-10-07。
