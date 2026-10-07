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

实机仍运行 0.0.20，尚未重试订阅。76 项测试通过不代表公网回调或模型唤醒通过；需安装 0.0.21 后读真实失败分类/阶段，或在成功订阅后发送一次手动事件并核对实际对话回复。
