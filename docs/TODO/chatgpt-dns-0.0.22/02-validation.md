# 02 验证记录

2026-10-07 Actions #509 成功完成测试、编译、签名与上传，仅构建 ChatGPT 桥。

- Run：https://github.com/AdrienFan/AI-Limbs-Public-Build/actions/runs/37558542522
- 来源提交：a76711e830bceec391a0c3eeac1cc720ca07a97c
- 8 份 XML 报告合计 84 项测试，失败、错误、跳过均为 0。
- 安装产物：chatgpt-native-probe-v0.0.22-509，artifact 11455762240。
- AILX SHA-256：0f7ef222aeeaf37defbb4ccd0d9c0c24d960fa5a9121c79dd050938ecd04f6b8。
- 已核对来源元数据、版本、包摘要、payload 摘要、非空签名项；云端签名步骤成功。

新增回归覆盖真实 TLS 的 DNS wire-format POST、新 IP 和新服务地址生效、fake-ip 不进入回调连接、HTTP 错误和重定向不回退、服务 TLS 信任校验，以及外层 DNS 异常包装下的具体 TLS 分类。兼容旧配置默认值和手动输入校验。

使用项目实际 OkHttp 4.12.0 的官方 DnsOverHttps 源码核对接口。服务连接不设置 bootstrap IP；客户端默认走当前手机网络，回调客户端不保留空闲连接。DNS 失败和停止取消不会重放 Host 业务操作。

Ubuntu 经 laner-net 的只读联网查询确认默认服务能将 api.openai.com 解析为 172.66.0.243、162.159.140.245，未返回 198.18 假 IP。该检查不等于 Android 新版桥的回调验收；实机仍需安装 0.0.22，重新订阅并核对回调验证和实际对话回复。


## 实机复验更新

0.0.22 已于 2026-10-07 09:52 实机确认 ONLINE，HTTPS DNS 成功 1 次、失败 0 次。事件回调进入 TLS 后失败，测试任务未保存；动态 DNS 修复生效，但完整唤醒未通过。后续证据见 [TLS 验证记录](../chatgpt-tls-0.0.23/02-validation.md)。
