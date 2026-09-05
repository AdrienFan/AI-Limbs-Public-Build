---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: completed
---

# Bridge 1.3.4 Provider Runtime 唯一归属

## 原本状况

Bridge 插件能够接收 RDC 与 TRIGGERcmd 子插件贡献并创建 PluginBridgeManager，但宿主基座仍运行旧管理器。插件管理器也没有在贡献重建后统一恢复已保存的连接意愿。

网络变化、息屏信号和 WakeLock 仍由基座旧管理器承担。通知动作只携带业务动作名，切换 Provider 时存在旧按钮操作新 Provider 的窗口。

## 意图和结果

Bridge 插件拥有所有子插件 Provider 的连接生命周期，并在管理器建立后恢复期望连接。插件自行接收网络、屏幕与 Doze 信号，按全部在线 Provider 的需求管理 WakeLock。

通知继续由当前 Provider 生成，但只在桥承担后台职责时发布，并给动作绑定当前 Provider 和状态修订号，过期动作会被拒绝。

## 作用域

- BridgeRuntime 增加 Host 信号与 WakeLock 控制
- PluginBridgeManager 建立后调用 startIfDesired
- 通知状态携带 Provider 身份与修订号
- Bridge 版本升级为 1.3.4
- RDC 与 TRIGGERcmd 子插件契约不变
