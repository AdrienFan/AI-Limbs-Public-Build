# 02 验证记录

实机已确认 0.0.20 事件发现成功，首次订阅因 verification_transport_failed 失败，未保存任务。用户截图给出宽泛错误，但无法从旧版定位 DNS/TCP/TLS/读取阶段。

0.0.21 仅修复异常诊断与结果交付，不改变网络路线。具体回调故障仍需安装新版、重新创建订阅后依据新的 reason/stage 判断。

云端 Actions #508 成功完成测试、编译、签名与上传，只构建 ChatGPT 桥：

- Run：https://github.com/AdrienFan/AI-Limbs-Public-Build/actions/runs/37556277074
- 来源提交：53b59816358022fa8a08c6afc6abb66e8d68b7c3
- 7 份 XML 报告合计 76 项测试，失败、错误、跳过均为 0。
- 安装产物：chatgpt-native-probe-v0.0.21-508，artifact 11454951823。
- AILX SHA-256：3723a824aa2fbfca12c5196b2ca463f2a9aadb8462e599c2ccfa25be5e3ff7c9。
- 已核对来源元数据、版本 0.0.21、包摘要、payload 摘要和非空签名项；签名步骤由云端构建完成。

## 2026-10-07 09:26—09:29 实机复验（Asia/Shanghai）

- 实机确认桥 0.0.21 ONLINE，状态工具已完整交付 events。
- 重新发现 ai_limbs.wake_requested，source_id=manual；仅创建一次测试订阅。服务报 ERROR，peek 确认未保存测试任务，不重复创建。
- 最新失败：non_public_destination，stage=dns，exception_type=GatewayNonPublicDestination；有效订阅 0，尚无事件发送或模型回复验收。
- 手机当前 VPN 为 com.github.metacubex.clash.meta，DNS 为 172.19.0.2。只读网络检查：api.openai.com → 198.18.0.4，chatgpt.com → 198.18.0.20。
- 源码明确拒绝 198.18.0.0/15 等非公网目标；传输使用系统 InetAddress 解析，全部检查通过后才连接。

判定：诊断分类和状态交付的两处修复已在实机生效；公网回调和唤醒仍失败。已确认本机 DNS 在使用假 IP；这是当前回调失败的重要兼容性线索，但回调自身解析地址未记录，不能仅凭其他域名结果断言其具体地址。

没有修改 VPN/DNS、放宽地址检查、关闭 TLS 校验或新增网络回退。下一步需让回调解析获得真实公网 IP 后复验（可研究针对该回调域名的 fake-ip 排除，或明确配置 redir-host 模式）；该复验完成前不宣称唤醒通过。
