---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: completed
---

# V0.7.3.6 Bridge 运行权与常驻通知收口

## 原本状况

V0.7.3.5 的前台服务仍创建旧 AiLimbsBridgeManager，并直接启动基座内 RDC 和 TRIGGERcmd Provider。Bridge 插件同时从 .ailx 子插件创建另一套 Provider Runtime，形成重复运行入口。

Bridge 插件已经通过 host.notification@1 发布当前 Provider 的通知状态，但宿主把它渲染成独立通知，因此和 AI Limbs 前台服务通知并列。

## 意图和结果

基座退出具体 Bridge Provider 的启动、重连、网络与息屏运行职责。Bridge 插件成为子插件 Provider 的唯一运行管理者。

host.notification@1 改为 AI Limbs 常驻通知的受控内容入口。Bridge 切换 Provider 后，同一张本体通知同步更新内容和动作。

## 作用域

- AIForegroundService 移除旧 Bridge Runtime 生命周期
- PluginNotificationHost 输出受控前台通知状态
- OperitApplication 根据插件通知职责启动前台服务
- 基座版本升级为 0.7.3.6，并使用独立 applicationId
- 保留现有公开调用接口，避免无关功能改动
