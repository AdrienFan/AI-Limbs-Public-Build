---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# Resident Host 资源轮询与页面快照分离

2026-09-30 的 build97 现场记录显示，视觉 0.1.4 已保存在版本仓库，但 active_version 仍为 0.1.2，quarantined_versions 为空，最后错误为 RUNTIME_STOP_FAILED。16:55:46 的首条相关警告标记 Worker process_alive=true、identity_attested=true、phase=unresponsive_or_starting；随后 Host UI proxy 断联并清理聊天与 Ubuntu presentation。

源码等待关系为：activateVersionLocked 持有 PluginManager 的生命周期互斥锁，等待旧插件 stop；旧插件通过通用 Host 原语请求释放屏幕和摄像头会话；Host 原先先 refresh 页面快照，再 pollAndExecute 宿主资源请求，而 residentUiProxySnapshot 中的 manager.snapshots 又要取得同一把锁。因此资源释放无法继续，旧版停止超时，新版尚未挂载。这不是新增页面阅读能力执行时的失败。

build98 将 presentation refresh 与 Host component poll 放入同一父 Job 下的两个独立协程。Core 仍管理业务状态、身份和权限，Host 只执行已经授权的 Android 资源请求；没有新增插件 ID 分支、协议或替代执行入口。原有 presentation 连接恢复策略保留，component 单项执行错误仍通过原有 broker 结果回报。

回归用例模拟页面快照等待生命周期锁，同时要求宿主资源释放独立完成，并验证客户端退出会取消两个轮询协程。用例接入云端 canonical contribution 测试步骤。本地只进行源码和差异检查，不执行编译或测试；安装 build98 后仍需实机验证已安装视觉 0.1.4 的版本切换与启用。

作用域为基座 Resident UI_PROXY 调度。视觉插件 0.1.4、Ubuntu 和 SentinelX 源码不改动。

[DONE]
