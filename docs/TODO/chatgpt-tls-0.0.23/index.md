---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: implementation-awaiting-cloud-validation
---

# TLS 内部原因安全分类

实机 0.0.22 ONLINE，加密 DNS 成功 1 次、失败 0 次；回调失败已由 DNS 非公网地址推进到 TLS 握手。测试订阅创建失败且未保存。手机时间正常，已核对源码没有改写回调域名或关闭证书验证。此时不能断言是证书、协议还是握手被中断。

现有分类抛弃了 SSLHandshakeException 内部原因，设备日志无可归因证据。仅修改 ChatGPT 桥以保留安全异常链及固定信号；不修改基座、视觉插件、网络路线或 TLS 信任，不创建 PR。

- [01 安全分类](01-diagnostics.md)
- [02 验证记录](02-validation.md)
