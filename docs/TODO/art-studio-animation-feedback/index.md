---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-runtime-pending
---

# 动画复制与提交反馈

基于c01e6ba1及已部署0.2.82。按阿伟要求先优化现有延迟，再补批量受影响帧反馈。本轮只修改画室插件，保持公开参数、全部258项能力与工程格式；版本0.2.83/code86/独立applicationId v0283。

1. [复制与反馈锁](01-copy-and-lock.md)：减少反复JSON编码/解析、紧凑快照先投影，资源捕获后锁外生成图片
2. [批量缩图](02-frame-sheet.md)：每次有效提交反馈，批量总览列全目标帧
3. [验证边界](03-validation.md)：源码/静态与云端/实机分别说明

无PR创建；开发分支提交，不推送、不启动新编译。性能与图片验收等待后续部署。
