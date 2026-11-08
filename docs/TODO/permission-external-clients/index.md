---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: implementation-complete-validation-pending
---

# 第三方 App 权限服务接入

原状：权限服务端仅接受 AI Limbs Host UID；无第三方授权库、申请回调和 UserService。连接状态卡片右侧尚无管理入口。

目标：在原有权限服务内提供兼容 Shizuku API v13 的应用接入和逐 UID 授权，在连接状态卡片右侧提供管理入口，保留既有配对、启动、停止、后端选择及无障碍功能。

作用域：permission-service 插件、独立 permission-server、上游 server-shared 的局部生命周期 hook，以及基座中通用的有界协议转发。应用名单和授权决定不进入基座。

- [x] [服务端、授权身份与生命周期](01-backend.md)
- [x] [管理页面及 UI 状态传输](02-manager.md)
- [x] 插件/服务 APK 版本及云构建产物标签更新为 v0.2.0
- [x] 配套基座源码标记为 build122，比较包使用独立 applicationIdSuffix
- [x] 源码差异和补丁适用性检查
- [ ] 明确请求后执行编译和测试
- [ ] Host / Resident 真机安装、客户端授权、UserService 和撤销验证

[使用及协议说明](../../../plugin-lab/plugins/permission-service/README.md)
