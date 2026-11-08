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


## v0.2.1 页面状态源发布顺序修复

- [x] 根据真机异常栈定位展示入口的过早 Provider 解析
- [x] 页面挂载与状态源绑定分离，观察状态源发布/替换/撤销
- [x] 为旧服务端协议提供明确的重启操作提示
- [x] 增加状态源晚发布、替换移除及错误 owner 三个回归用例
- [x] 云构建任务增加对应 JVM 单元测试
- [x] 云编译与安装后的页面恢复验证（用户确认 v0.2.1 页面已打开）


## v0.2.2 连接客户端前完成显式启动

- [x] 日志定位 Canta / MT 的 Provider 获取为空，确认失败早于授权审批
- [x] 真机对照：未运行时无法获取，显式启动后 Provider 可访问；临时省电白名单无效
- [x] 连接按已核验包/UID 查询 Launcher 并等待系统启动完成，再执行单次交接
- [x] 增加启动超时、返回值与目标核对、失败原因显示和重复提交限制
- [x] 更新 v0.2.2 产物标记，并将服务端启动结果用例接入云任务
- [ ] 云编译及安装后第三方连接、权限回调与 UserService 验证
