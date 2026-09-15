---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/resident-runtime-build26
status: runtime-migration-in-progress
---

# Resident Core 分阶段迁移

目标是 Resident 开启期间，锁屏后 Bridge、Dispatcher、插件、Ubuntu 和设备能力继续可调用，关闭时释放工作资源。目标覆盖 Android 品牌，不以厂商名单为实现方案。

build24 的独立 Guardian 仍使用 App UID。PPID=1、oom_score_adj=-1000、进程存活和 WakeLock.isHeld 都不能证明 CPU 实际保持唤醒，也不能证明该 UID 不受冻结或网络策略限制。

本阶段 build25 提供独立 app_process Context 与同 UID IPC 验证入口、进程运行时所有权保护，以及真实状态字段。它不迁移插件，不自动用验证进程接管业务，不宣称锁屏连续工作已完成。验证入口仅接受 status/stop；没有任意命令、任意能力执行或新的门禁旁路。

## 阶段

1. [独立启动和 IPC](01-bootstrap.md)：实现并送云构建；安装后验证 Context、资源、身份、重复启动和退出
2. 分离插件运行时和界面：现有 InProcessPageProvider 返回 Android View，不能直接经 IPC 传递，需定义状态与事件契约
3. 迁移唯一业务核心：Plugin Kernel、Bridge、Dispatcher 和插件服务；Interaction Cycle、授权及回执仍由唯一入口统一处理
4. 权限与持续运行：独立检验进程冻结、实际 suspend、网络、Android 组件权限和进程归属；需要特权的设备按已授权后端能力实施，不能假设同 UID 天然豁免
5. 真机验收：长时间锁屏持续外部调用，关闭 Resident 后核对核心退出和 CPU/网络资源释放

2026-09-15：继续编写后续迁移，不再把 build25 安装验证作为源码工作的前置门槛。本轮暂不编译。候选分支已包含 ac9373b 的 Android 16 Socket 初始化顺序与 Guardian 唯一实例锁修复，后续改动必须保留。

当前源码进度见 [运行时退出与交接边界](02-runtime-retirement.md)。业务迁移、界面代理、特权后端交接仍未接通；不能将生命周期整改或 Core 诊断入口计为整个运行时已迁移。
