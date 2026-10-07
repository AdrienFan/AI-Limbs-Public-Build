---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: cloud-validated-awaiting-device-callback
---

# 回调真实地址解析与手动 DNS 配置

实机 0.0.21 确认回调在 DNS 阶段拒绝非公网地址；手机 Clash Meta 对 OpenAI 域名返回 198.18 假 IP。用户要求不绑定不稳定的 VPN 地址，支持自动解析和手动填写。

仅在 ChatGPT 桥内实现 HTTPS DNS 查询，使用当前手机网络访问可配置的解析服务，再校验真实回调地址并以原域名完成 TLS。既有隧道与密钥、七个 MCP 工具、事件定义保持兼容；不修改基座或视觉插件，不创建 PR。

- [01 实现与边界](01-resolution.md)
- [02 验证记录](02-validation.md)
