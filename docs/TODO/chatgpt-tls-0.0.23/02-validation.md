# 02 验证记录

## 0.0.22 实机结果

2026-10-07 09:52—09:54 Asia/Shanghai，桥 0.0.22 ONLINE。按现有官方事件 schema 创建一次手动测试订阅，服务报 ERROR，peek 确认未保存测试任务；没有重试创建或发送事件。

HTTPS DNS successful_lookups=1、failed_lookups=0，最近查询成功；回调验证错误 tls_handshake_failed，stage=tls，exception_type=SSLHandshakeException。有效订阅 0、待发事件 0。手机时间与当前时间一致，代码未改写回调域名，也无自定义不安全 TrustManager。设备日志无可归因的内部原因。DNS 修复已实机生效，实际 TLS 根因、回调和模型唤醒未通过。

## 0.0.23 云端验收

Actions #510 成功完成测试、编译、签名和上传，只构建 ChatGPT 桥。

- Run：https://github.com/AdrienFan/AI-Limbs-Public-Build/actions/runs/37559821446
- 来源提交：64413a8e3405a149b0cfced75052d890720bfc03
- 8 份 XML 报告合计 89 项测试；失败、错误、跳过均为 0。
- 安装产物：chatgpt-native-probe-v0.0.23-510，artifact 11455869083。
- AILX SHA-256：1381d9324a68b75cd806ae322fe067ac471a5b7e81b373a7b13f4b29d112fc8b。
- 已核对来源提交、版本、安装包摘要、payload 摘要和非空签名项；云端签名步骤成功。

新增用例覆盖证书内部原因、套接字/协议分类、DoH 的嵌套 TLS 原因、异常链限长以及固定信号不泄露原消息。真实 HTTPS DNS 证书拒绝用例同步核对新的证书分类。此版补齐根因证据，不改变 DNS/代理路线、TLS 信任、公网地址检查或超时。安装后需重新订阅，读取实际 cause_types、certificate_reason、tls_signal；不能宣称握手故障已修复。
